# 画面传输全部改为本机 IPC（部分验收）

## 实现了什么

- **世界帧**：后台把 `Frame` 元数据（会话、帧号、尺寸、相机序列、实际相机与矩阵、捕获时间、时钟误差）以 UTF-8 JSON 写进 `FrameFile` 槽头，与颜色和深度一起受 CRC32 保护。原先的 WebSocket `frame` 广播已删除，Coordinator 也不再转发该类型。主端根据在线 Runtime 列表轮询每个后台的 `.frames` 文件，按（写者代数，帧号）取最新完整帧。
- **原生手部与物品图标**：`-hand.frames`、`-icon.frames` 使用同一格式，元数据为新增的 `Messages.Visual`（帧、物品引用、裁剪偏移）。原先的 `mmlf:visual` 事件与 `RemoteItems.sendVisual` 已删除；主端按所持远端物品的所属 Runtime 轮询。
- **`FrameFile` 格式 v3**：槽头扩大到 4096 字节，新增写者代数和元数据长度，格式标识改为 `0x4d464633`。新增 `FrameFile.writer`/`reader` 工厂方法和 `readLatest(after, reuse)`：先在锁内扫描三个槽头，再在锁内复制选中的槽并校验；某个槽忙或损坏时退到次新的槽。写者代数取文件中已有的最大值加一，后台重启后帧号从 1 重新计数，仍排在旧帧之后。
- **姿态文件初始化不再阻断 bridge**：`PoseFile` 的打开从初始化阶段移到 `tick`，主端和后台都每秒重试一次。失败时 WebSocket 连接、交互和日志照常工作。
- **时钟校准过滤**：新增 `FrameTiming.ClockFilter`，保留最近 8 次 ping，使用其中仍有效且误差最小的一次。单次往返超过 200 ms 的慢 ping 不再让后台停止捕获，也不再让主端拒绝所有帧。

## 发现的问题

1. 在初始化阶段，`PoseFile.publisher` 写在 `RuntimeClient` 之前。它抛异常时 `link` 一直是 null，主端整个 bridge 都会失效。
2. `RuntimeClient` 每次 ping 都用最新样本覆盖校准值。只要一次往返超过 200 ms，误差就超过 `MAX_UNCERTAINTY`，后台停止捕获、主端拒绝所有帧，直到下一次正常 ping（至少 1 秒）。
3. 帧通知通过 WebSocket 广播给所有 Runtime，每秒约 120 次。主端靠消息里的槽号去读帧；三个槽轮转较快时，槽可能在读取前已被覆盖。
4. 审查时曾认为 `PoseFile.validPose` 缺少相机坐标与角度检查。实际上 `Pose` 的构造函数已经校验这些字段，这条判断有误，未作修改。
5. `docs/` 在 `.gitignore` 中，注释为“文档与报告不入库”，属于有意设置，未作修改。

## 如何解决及取舍

- 元数据使用已有的 Gson 序列化，没有另写二进制格式。解析开销很小，`Frame` 和 `Pose` 的结构也可以直接复用。
- 主端每次准备显示帧时，每个文件需要做 3 次加锁加读槽头的操作，只有出现新帧时才复制像素。旧方案靠消息驱动，现在改为轮询，多了少量系统调用，但不再依赖消息到达的时机。
- 读取端只打开写者已经建好且大小完整的文件，文件不存在时每 500 ms 重试一次，因此主端不会提前为后台创建大文件。
- 低频的 `mmlf:latency_feedback`（每秒一次，用于后台的预测提前量）仍走 WebSocket，交互与生命周期消息也一样。

## 结果

- `./gradlew.bat -PcoreOnly :common:test --max-workers=1`：通过，13 个测试类共 30 项。`FrameFileTest` 改为 7 项，覆盖以下内容：
  - 元数据与两个平面
  - 游标与缓冲复用
  - 读取端要求文件已存在
  - 写者重启后代数更高，新帧优先
  - 同进程并发无撕裂
  - 锁占用、奇数标记、像素损坏、元数据损坏均被拒绝
  - 子进程持锁崩溃后锁被释放
  - 独立 JVM 写、轮询读

  `FrameTimingTest` 新增慢 ping 不使校准失效的测试。
- `./gradlew.bat build --max-workers=1`：成功，37 个任务中 22 个执行。
- 真实启动：`./scripts/run-demo.ps1 -SelfTest -LogLevel DEBUG`，会话 `runtime/session-14016391816456598222`，Fabric 主端加 NeoForge 后台。从日志看：
  - 主端 `read_complete` 和 `gpu_frames` 持续出现，并打印 `MMLF COMPOSITE READY remote=neoforge mode=gpu`。
  - `render.hand/native_composite_ready` 和 `render.icon/native_icon_ready` 出现，说明手部和图标也是轮询读到的。
  - 三个 `remote_frame` 窗口的帧龄 p50 约 11.6–11.9 ms；p95 分别为 26.1、17.2、16.9 ms；时钟误差 1.4–1.8 ms。第一个窗口 p99 为 173 ms（启动阶段）。
  - 自测相机控制两次 passed=true，碰撞 passed=true；远端 use 三次、attack 一次，均为 success=true。
  - 三个日志中 `frame` 类型消息和 `mmlf:visual` 消息都是 0 条。

  该会话由本次测试用 `taskkill /T /F` 结束，不是正常退出。
- 没有人工观察画面。日志只能说明帧读到了、合成路径执行了，不能证明画面正确。

## 尚未完成与下一步

- 需要用户进行画面验收，特别是手部和图标的显示，以及快速转头时的表现。
- 两个后台同时运行的情况未在本次改动后验证。
- 没有与改动前的性能对照，也没有统计轮询的系统调用开销和 CRC 开销（2560×1440 时每帧约 29 MB）。
- 帧元数据仍依赖 Coordinator ping 做跨 JVM 时钟校准。
- 会话目录和映射文件的清理仍未实现。
