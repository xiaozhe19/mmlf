# v2 工作流 B3：单 JVM 帧通道（进行中：内存通道已实现并通过自动化测试；GPU 共享只写了探针、未运行；真实游戏未验证）

日期：2026-11-05。需求：`docs/manual/Single JVM Two Runtime v2.md` B3；实施计划 M2。

**状态**：用户 GPU 正在训练模型，本轮没有运行任何 GPU 程序（真实 Minecraft、共享上下文探针都没有运行）。默认通道仍为文件通道，行为与 v1 相同。

## 一、实现了什么

### 1. 现状审查

旧链路：`FrameExporter → PBO → FrameFile → RemoteCompositor`，每帧的步骤如下。

- 远端：
  1. PBO 映射后拷贝进 mmap 文件槽；
  2. 计算颜色、深度和元数据的 CRC32；
  3. 加文件锁（系统调用）。
- 近端：
  1. 加文件锁；
  2. 从 mmap 拷贝到直接缓冲；
  3. 再算一次 CRC32；
  4. 用 `glTexSubImage2D` 从客户端内存同步上传。

1080p 一帧颜色加深度约 16 MB，两次 CRC 和两次拷贝都在两端的渲染线程上执行。

### 2. 内存通道（`FrameFile` 内存后端，即计划中的 `FrameChannel`）

没有新建接口，而是给 `FrameFile` 增加了第二种后端。理由是槽布局、序列锁、代数排序以及 `Latest`/`Pixels` 类型全部复用，调用方只需要换掉"打开"这一步。

- `FrameFile.memoryWriter/memoryReader(key,maxPixels)`：三个槽是直接 `ByteBuffer`，存放在 `InJvm.registry()` 的 `frames:<session>:<name>` 下。
  - 共享对象只含 JDK 类型：`AtomicReferenceArray<ByteBuffer>`、`AtomicIntegerArray`（槽锁）、`AtomicLong`（写者代数）。
  - 两个 Runtime 各自的 `FrameFile` 副本可以互相读取。
- 槽锁用 CAS，不做系统调用。不算 CRC：CAS 获取和释放本身建立了 happens-before，读写由槽锁排开。
- 槽按实际帧大小增长（最多到容量上限），不会一开始就为每个通道分配 3×29.5 MB。
- 会话结束时，`SessionHost.close()` 调用 `FrameFile.releaseMemory(identity)`，把会话的通道从注册表移除。
- `bridge-common/Frames`：所有帧通道（世界、手持、图标、GUI、目录图集）统一从这里打开。
  - `-Dmmlf.frameChannel` 或 `MMLF_FRAME_CHANNEL` 取 `file`（**默认**）或 `direct`；进程会话一律使用文件。
  - 两端读取的是同一个 JVM 级设置，因此读写端不会选到不同后端。打开时记录 `frame_channel_opened` 日志。
- 尚未做：近端用 unpack PBO 异步上传。现在仍是同步 `glTexSubImage2D`，没有 GPU 无法测量其收益，所以没有改。

### 3. GPU 共享探针（只编写、编译，未运行）

`tools/ShareProbe.java` 和 `tools/ShareProbeSide.java`：

- 两个独立 `URLClassLoader`，各有一份 LWJGL/GLFW，和 MMLF 的两端一样只通过 JDK 类型通信。
- 近端先建上下文；远端建上下文后、创建任何对象之前，调用 `WGL.wglShareLists(near, far)`。
- 远端获取 GL 能力时，复制 `InJvmLwjgl` 的槽位让出逻辑。
- 共 120 帧：
  - 远端把随帧号变化的颜色和深度写入双缓冲的共享纹理，然后 `glFenceSync` + `glFlush`；
  - 近端用 `glWaitSync` 在 GPU 侧等待（CPU 不阻塞），采样两张共享纹理画到自己的 FBO，读回一个像素逐帧校验。
- `current` 模式：远端加入共享组时，近端上下文是 current 的，这是 MMLF 实际面对的情况。
- 编译命令：`javac -cp <cp.txt 中的 LWJGL 3.3.3 jar> -d out ShareProbe.java ShareProbeSide.java`，**编译成功**。
- 运行命令（待 GPU 空闲）：`java -cp out ShareProbe tools/cp.txt out current`

计划的后续步骤：

- 探针通过后，接入 `WindowMixin`：远端创建上下文后立即加入共享组，近端的 HGLRC 经注册表以 Long 传入。
- 远端 `captureFrame` 把颜色和深度 blit 到共享纹理对；近端 `RemoteCompositor` 直接采样。
- 失败或超时都降级到 `direct` 或 `file`。
- 如果 `wglShareLists` 不可行，备选方案是 `split-runtime-experiments/tools/SharedGpu.java` 的 Vulkan 外部内存。

## 二、发现的问题

1. **两次 CRC 与系统调用都在渲染线程上**（代码审查）。在单 JVM 中，文件锁和 CRC 的作用（跨进程互斥、防止崩溃导致的半写）可以由 CAS 槽锁和"同一进程"这一事实替代。内存后端去掉了这两项。实际节省多少毫秒**未测量**。
2. **内存后端不能套用文件的 `close()` 语义**：一端关闭时，另一端可能仍持有槽。因此内存通道的 `close()` 不做任何事，统一在会话结束时释放。

## 三、实际执行与结果

| 层级 | 内容 | 结果 |
|---|---|---|
| 编译 | 两个 bridge `compileJava`；`gradlew build` | 成功 |
| 自动化测试 | `FrameMemoryTest`（4）；原有 `FrameFileTest`（7） | 全部通过 |
| 探针编译 | `javac ShareProbe*.java` | 成功 |
| 探针运行 | — | **未执行**（GPU 被占用） |
| 真实游戏 `direct` 通道 | — | **未执行** |

`FrameMemoryTest` 覆盖：

- 最新帧和元数据；槽增长后帧顺序保持正确；
- 重启的写者（更高代数）胜出；
- 写线程持续发布 1 s、读线程持续校验每 64 字节，没有出现撕裂帧（替代 CRC 的正确性依据）；
- 两个独立 `URLClassLoader` 各加载一份 `FrameFile`，互相读写帧。

## 四、未完成与下一步

1. GPU 空闲后：
   - 两个方向分别用 `MMLF_FRAME_CHANNEL=file` 和 `direct` 跑自测，对比 `metrics/frame_sync` 的端到端延迟和 `capture_latency`；
   - 每种配置至少 3 轮（`scripts/perf/perf_run.py --configs injvm-file,injvm-optimized`）。
   - 差异超过轮间波动、且自测与截图无回归时，才把 `injvm` 会话的默认值改为 `direct`。
2. 运行 `ShareProbe`，再按结果决定是否接入 GPU 共享。
3. 近端 unpack PBO 异步上传。

## 五、文件

- 新增：
  - `bridge-common/.../Frames.java`
  - `common/src/test/.../FrameMemoryTest.java`
  - `tools/ShareProbe.java`、`tools/ShareProbeSide.java`、`tools/cp.txt`
- 修改：
  - `common/.../FrameFile.java`（内存后端）
  - `BridgeCore`、`GuiClient`、`GuiHost`、`RemoteCatalog`、`VisualLayers`（通道改由 `Frames` 打开）
  - `launcher/.../SessionHost.java`（会话结束时释放）

## 补充：2026-11-05 `ShareProbe` 首次运行（失败，已定位并修改，未重跑）

- 两种模式（`current`、`idle`）都在 GL 共享之前失败：`ExceptionInInitializerError` ← `IllegalStateException: setFunctionMissingAddresses has been called already`。
- 调用栈：`ShareProbeSide.far` → `WGL.<clinit>` → `GL.<clinit>` → `GL.create` → `ThreadLocalUtil.setFunctionMissingAddresses`。
- 原因：只要第一次碰到 `WGL` 类，就会运行 `GL` 的静态初始化，并写入 JVM 级的缺失函数槽位。探针原本只在 `GL.createCapabilities` 前后让出槽位，太晚了。近端的失败是远端失败后连带的。
- 修改：远端在第一次调用 `WGL` 之前让出槽位，`wglShareLists`、`makeCurrent`、`createCapabilities` 都在让出期间完成；之后无条件恢复原值（包括空值，这样 `idle` 模式下近端之后仍能自己填入）；去掉远端对 `nearCapabilities` 的等待，避免 `idle` 模式死锁。已重新编译，**未重跑**（GPU 再次被训练占用）。
- 对正式接入的意义：将来远端在 MMLF 中调用 `WGL`（共享上下文）时，必须放在 `InJvmLwjgl.yieldMissingTable` 的窗口内，或者在 `GL` 类已经初始化之后。

## 补充：2026-11-09 ShareProbe 重跑

- `java -cp docs/ai/result/v2-frame-channel/tools/out ShareProbe docs/ai/result/v2-frame-channel/tools/cp.txt docs/ai/result/v2-frame-channel/tools/out current`：两端越过了此前的 `GL.<clinit>` 槽位错误，但 `wglShareLists` 返回失败，未进入帧校验；探针错误地仍以退出码 0 退出，因此必须检查输出，不能以退出码判定成功。尚未获得 Windows 错误码，失败原因未确定。
- 同命令将 `current` 改为 `idle`：NVIDIA GeForce RTX 3060 Laptop GPU；120 帧颜色及深度全部正确，`wrong=0`，两端 `glError=0`；consume p50 0.3369 ms、p95 0.5907 ms，produce p50 0.0127 ms。这是探针数据，包含读回校验，不能当作游戏性能。
- 运行前 `javac` 输出了访问 Gradle 缓存 jar 时的 `AccessDeniedException`，尽管返回码为 0 并生成了可运行类；本次编译环境异常不能算干净的编译通过。当前上下文模式失败，正式 GPU 共享仍未接入，也未验证真实游戏画面。

### 探针退出码修正与复验

- `ShareProbe.main` 现在要求两端共享成功、120 帧全部正确且双方 `glError=0`，否则抛出异常并返回非零退出码；线程池在 `finally` 中关闭。
- 使用非 native 的 LWJGL jar 编译 `javac -cp <cp.txt 中非 natives-windows 的 jar> -d docs/ai/result/v2-frame-channel/tools/out ShareProbe.java ShareProbeSide.java`：成功。随后运行上述 `current` 命令：仍在 `wglShareLists` 失败，退出码 1；运行 `idle`：120/120 正确、双方 `glError=0`，退出码 0（consume p50 0.3381 ms、p95 0.6715 ms）。尚未查明 current 失败的 Windows 错误码。
