# 共享姿态与多 Runtime 合成进度报告（进行中）

## 实现

主 Runtime 每帧写入 `camera.pose`，后台在 `Camera.setup` 之前及拾取之前非阻塞读取最新完整快照；原先高频 WebSocket `pose` 路由已移除，交互和生命周期仍由 WebSocket 路由。源端 `nanoTime` 差值用于估计角速度，不直接与另一个 JVM 的时间戳相减。维度变化、较大位置跳变、异常时间间隔及转向反向会重置或调整估计；本地超过一秒未收到新序列时清除姿态。

主端 GPU 帧管理已按 Runtime 保存独立的最新帧和像素缓冲，并修复同帧号、不同 Runtime 的纹理缓存冲突；默认 CPU 对照合成仍只使用一个远端层，多后台尚未实际验证。

## 二进制协议 v1

512 字节，小端序；协议字段以字节偏移表示：0 magic int32、4 version int32、8 会话 token 的 SHA-256（32 字节）、40 sequence int64、48 发布端单调时间戳 int64、56 camera ID int64、64 epoch int64、72 维度 UTF-8 长度 int32、76 维度数据（128 字节）、204 相机 xyz float64×3、228 yaw/pitch/FOV float32×3、240 宽高 int32×2、248 玩家 xyz float64×3、272 view matrix float32×16、336 projection matrix float32×16、400–507 保留、508 CRC32。长度和校验不匹配、版本不同、会话不同的快照不接受。会话 token 的散列是隔离标识，不应当作通信权限控制。

数据文件采用映射，独立的 `.lock` 文件第 0 字节用于参与者非阻塞排他锁；获取失败即跳过，不等待，读取端保留上一个快照。第 1 字节由发布者持有，用于拒绝第二个发布者；发布者启动时在锁内清空旧数据，崩溃时 OS 释放锁。校验和用于检测写入中断留下的不完整数据。锁是协作机制，所有访问者必须遵守。Windows 本地文件映射跨进程一致性参考 [Microsoft MapViewOfFile 文档](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-mapviewoffile)；[Java FileLock 文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html) 提醒文件锁与映射的系统差异，所以锁文件与映射文件分离。本实现仍需系统地验证 Java/JDK 与 Windows 同步边界，不能将压力测试上未见撕裂视为跨平台证明；不支持以网络共享文件替代本地文件。

## 发现与解决

- 单帧状态会被不同后台互相覆盖，且纹理缓存只按 frame ID 判断：GPU 合成改为逐 Runtime 保存和绘制，缓存使用 Runtime 和 frame ID。尚未用两个后台实际验收。
- 两个 JVM 启动顺序不固定，读取端可能看到刚创建但未扩容的文件：读取端重试，测试子进程同步修复。`PoseFileTest` 在修复前出现 `pose file incomplete` 失败，修复后通过。
- 相机预测原来按 WebSocket 到达间隔计算角速度；现在使用发布端时间间隔，减少到达抖动影响，但预测提前量仍固定 25 ms，尚未实现动态估计。

## 执行结果

- `./gradlew.bat -PcoreOnly :common:test --tests dev.mmlf.common.PoseFileTest`：通过；覆盖编码、不同会话拒绝、锁竞争、破损拒绝、发布者重启、单发布者和一个写入 JVM 加两个读取 JVM 的 15,000 次快照压力测试。先前一次启动竞态失败已修复并重跑。
- `./gradlew.bat build`：在多 Runtime 合成改动后通过；Fabric 编译有既有 Mixin 映射警告。最终状态重新执行通过：37 个任务中 15 个执行、22 个 up-to-date。
- `./scripts/run-demo.ps1 -SelfTest -Foreground -LogLevel INFO`：真实 Fabric 主端与 NeoForge 后台启动并进入世界，日志出现 `MMLF COMPOSITE READY`，脚本的相机控制和碰撞检查打印 passed=true；交互请求和路由有日志。该进程由本次测试在完成脚本操作后手动终止，不是正常自动退出。本次运行早于多 Runtime 帧管理和发布者锁的最后修改，不能验证这些后续修改。
- 实际截图 `runtime/session-4866854458967348246/fabric/screenshots/mmlf-core-demo.png` 可见两个方块与远端陷阱门；这是单次静态截图，不能证明快速转头、深度遮挡及所有交互正确。

补充验证：`./gradlew.bat -PcoreOnly :common:test --tests dev.mmlf.common.PoseFileTest --max-workers=1` 通过；共享快照现在拒绝非有限 FOV、玩家坐标和矩阵元素，避免异常数值进入预测及渲染。本次游戏会话启动于此补充改动之前。

## 未完成和限制

需要验证最终代码的真实启动、断线重连及两个后台；补齐帧会话身份、可靠跨进程时间校准与帧龄、动态预测、指标分位数、覆盖和拒绝率；继续按诊断排查边缘消失、视锥与深度搜索并进行 Overscan 对照。共享帧文件既有 seqlock 仍缺少跨进程内存排序证明；不能宣称无数据撕裂。尚无修改前后性能对比、完整画面和交互验收，项目整体未完成。

## 发布者初始化竞争修复

完整构建中一次跨 JVM 测试出现 `pose initialization lock busy`：读取端先启动时可能短暂占有锁，使发布者初始化直接失败。发布者启动时现在最多等待 250 ms 重试，按中断退出；只有创建时有界等待，每帧读写仍非阻塞。如果超过上限仍失败，初始化仍会报错，尚未实现后台重试。新增短暂持锁后释放的启动测试；`./gradlew.bat build --max-workers=1` 重跑通过，37 项任务中 19 项执行。
