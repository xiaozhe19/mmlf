# 对等主端角色与客户端拉起实现报告

日期：2026-10-28。Windows 11、RTX 3060 Laptop GPU、Temurin JDK 21，Fabric Loader 0.16.10 / NeoForge 21.1.172。

## 实现了什么

主端（拥有窗口、接收输入、负责合成的 Runtime）不再固定为 Fabric。角色属于会话，而不属于加载器：

- `Session` 新增 `primary` 字段，由创建会话的一方写入；`BridgeCore` 用 `runtimeId.equals(session.primary())` 决定本端角色。删除了 Fabric 运行配置里的 `-Dmmlf.primary=true` 和 NeoForge 的 `false`。
- 新增 `launcher` 模块的 `SessionHost`：创建会话目录、令牌、Coordinator、场景文件，按加载器分拣 `mmlf/mods/`，并用 Gradle 开发任务启动指定 Runtime。独立启动器和客户端内托管共用这段代码。
- 新增 `bridge-common` 的 `SessionBootstrap`：客户端启动时如果没有会话（无 `MMLF_SESSION`）且是开发运行（`-Dmmlf.root` 指向项目），本端就在进程内启动 Coordinator，成为主端，并在后台拉起其余 Runtime。两个 Bridge 因此都打包了 coordinator 和 launcher 代码（Fabric 用 `include`，NeoForge 用源码目录）。
- 两个 Bridge 的 `runClient` 前增加 `stageHostMods` 任务：独立启动时把 `mmlf/mods/` 中属于本加载器的 JAR 复制到本端 `run/mods`。
- 启动脚本新增 `-Primary fabric|neoforge`，启动器新增 `--primary=<id>`，默认仍为 `fabric`。
- 与角色相关的硬编码改为按角色判断：后台窗口隐藏改为"非主端"（原为 `runtime.equals("neoforge")`），自测改为在主端运行（原为 `runtime.equals("fabric")`）。
- 后台 Runtime 注册成功后，如果与会话断开超过 15 秒，会自行 `mc.stop()`，避免主端进程异常消失后留下看不见的后台窗口。

使用方式：

```powershell
./gradlew.bat :neoforge-bridge:runClient   # NeoForge 为主窗口，自动在后台拉起 Fabric
./gradlew.bat :fabric-bridge:runClient     # Fabric 为主窗口，自动在后台拉起 NeoForge
./scripts/run-demo.ps1 -Primary neoforge   # 由独立启动器创建会话，NeoForge 为主
```

设置 `MMLF_SELF_TEST=true`（或 `-Dmmlf.selfTest=true`）会在客户端托管的会话中运行自测；`-Dmmlf.autohost=false` 关闭客户端托管。

## 发现的问题与解决

1. Gradle 构建脚本里，`loom.runs.client {}` 闭包内的 `rootProject` 解析到错误对象（`No such property: projectDir for class: java.lang.Boolean`）。改为在 `plugins {}` 之后定义 `def mmlfRoot = rootDir.absolutePath` 再引用。
2. 自测在后台 Runtime 世界就绪前就开始执行。后台 Mod 一初始化就注册到 Coordinator，比它的世界加载早约 1 分钟，导致第一次 NeoForge 主端运行的交互自测全部 `request=false`。改为主端收到第一张远端帧（`BridgeCore.remoteLayerReady()`）后才开始计时。
3. 早期报告记录过两个独立 Gradle 进程争用 Loom 缓存锁，所以启动器当时改成了单次并行调度。客户端托管必须在已运行的 `runClient` 之外再启动一个 Gradle 进程。本次两个方向各实测一次，都正常拉起了另一端，但还没有做多次重复验证，锁争用风险没有排除。

## 得到了什么结果

- `./gradlew.bat build`：全部模块编译成功，现有自动化测试通过。
- 真实 Minecraft，NeoForge 托管（session-13330307011031721131，修复自测门槛之前的旧版本）：日志显示 `hosting session as primary neoforge; started [fabric]`，Coordinator 记录 `runtime_registered … "primary":true,"loader":"neoforge"`，Fabric 以 `"primary":false` 连接，后台捕获和发送帧正常。由于上述问题 2，这一轮交互自测失败，不能作为交互通过的证据。停止主端后，Fabric 后台进程随之退出，没有残留 Minecraft JVM。
- 真实 Minecraft，Fabric 托管（session-1955423682484589279）：`hosting session as primary fabric; started [neoforge]`；相机控制、碰撞（`shapes=1 … passed=true`）、跨端交互、反向事件、第三方活板门、中键选取、远端放置、破坏粒子自测均输出成功；截图见 [GPU 合成报告](../gpu-composite/README.md)。
- 真实 Minecraft，NeoForge 托管（新代码，session-4973500186604494280）：碰撞、远端测试方块和第三方门的跨端交互均通过，详见 [GPU 合成报告](../gpu-composite/README.md)。停止主端后约 20 秒内，所有 MMLF Java 进程都已退出。
- 未实机执行：`run-demo.ps1 -Primary neoforge` 和 `Launcher --primary=` 这条独立启动器路径。它们与客户端托管共用 `SessionHost`，但本次只编译、没有运行。

## 限制与下一步

- 客户端托管只支持开发环境：它通过项目的 Gradle 任务拉起另一端。用户自己的启动器实例（官方启动器、Prism 等）之间互相拉起，需要配置另一端的启动命令，尚未实现。
- 会话期间不能切换主端；要换主端，需重新启动会话。
- `stageHostMods` 只复制、不清理，`run/mods` 中旧的第三方 JAR 需要手动删除。
- 第三个 Runtime（Forge）需要在 `SessionHost.RUN_TASKS` 中登记启动任务。

## 关闭联动（2026-10-28 晚，用户反馈"游戏界面关掉后，后台 Runtime 也应该一起关掉"）

之前只有两道保险：主端的 JVM 关闭钩子结束子进程树；后台与会话断开 15 秒后自行退出。主端如果没有走正常关闭流程（被强制结束、崩溃、终端里 Ctrl+C），后台会以隐藏窗口的形式再存活十几秒。

新增：

- **看门狗**：`SessionHost` 通过环境变量 `MMLF_HOST_PID` 把托管进程的 PID 传给后台。后台每 20 tick 检查一次，进程不在了就立即 `mc.stop()`（日志 `background_stopping reason=host_exited`）。
- **主动通知**：主端 `Minecraft.stop()` 开始时广播 `mmlf:session_end`，后台收到后立即退出（`reason=session_end`）。
- **兜底超时**：断开后自行退出的时间从 15 秒缩短到 5 秒。

实测（NeoForge 主端，Fabric 后台）：

- 正常关闭主窗口（向窗口发送关闭消息，等同点击 ×）：约 3 秒内，Fabric 客户端和 Gradle 进程全部退出。这一轮是在加入看门狗之前测的，生效的是原有的关闭钩子。
- 强制结束主端进程（`Stop-Process -Force`，不执行任何关闭流程）：约 3 秒内全部退出，Fabric 日志记录 `background_stopping {"reason":"host_exited"}`。

未单独测试：`session_end` 广播这一条路径（正常关闭时，它和关闭钩子同时生效，无法区分是哪一条起的作用），以及 Fabric 当主端时的关闭联动。
