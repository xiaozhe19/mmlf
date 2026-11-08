# 单 JVM 双原生 Runtime 实现报告（开发路径已真实运行；发行版路径未完成）

日期：2026-11-04。需求见 `docs/manual/Single JVM Two Runtime.md`。

## 结论速览

| 验收项 | 状态 | 依据 |
|---|---|---|
| 两个原生 Loader 在同一 JVM 完成初始化 | **真实运行通过**（两个方向） | 日志：`MMLF bridge initialized: fabric/neoforge` 同进程出现；进程列表只有一个游戏 `java` |
| 两端分别加载原生测试 Mod | **真实运行通过** | Fabric：`Loading 62 mods … mmlf_fabric_test 0.1.0`；NeoForge：`mmlf_neoforge_test` 及 farmersdelight、mcwtrpdoors 均完成 FML 构造 |
| 通过 MMLF 双向消息 | **自动化测试 + 真实运行通过** | `InJvmTransportTest` 4/4；真实会话中 `world_ready`、`session_end`、可靠消息、ping RTT 0.5–1.0 ms |
| 任意一端为启动入口 | **真实运行通过**（开发路径） | `:neoforge-bridge:runClient` 和 `:fabric-bridge:runClient` 各自拉起另一端 |
| 无重复启动、循环拉起、明显 ClassLoader 泄漏 | 循环/重复：**设计 + 真实运行**；泄漏：**仅单元测试** | 见"限制" |
| 两个完整 Minecraft 在同一 JVM 运行 | **真实运行通过** | 两端都进入世界：2601 区块、服务器 tick、各自 GL 上下文 |
| 近端唯一可见窗口 | **真实运行通过** | 窗口枚举只有近端一个标题；截图见下 |
| 远端提供原生 Mod 渲染 | **真实运行通过** | 远端世界渲染 106–131 fps，近端 GPU 深度合成：`COMPOSITE READY … mode=gpu` |
| 跨端交互和合成能力保留 | **自动化自测通过**（真实游戏内自测脚本） | 两方向自测见下；用户另行实机试用（用户口头确认"试了下"，具体项目见"用户实机"） |
| 发行版（官方启动器/PCL）单 JVM | **未完成** | 发行版仍走 `Manager` 进程后端 |

## 一、实现了什么

### 架构

```
同一 JVM
├─ 近端：原生启动的 Loader（Gradle runClient / 启动器）
│   └─ bridge ─ RuntimeManager ─ CoordinatorCore ─┬─ InJvmPort（String 帧，System.getProperties 交接）
│                └─ InJvmBackend                   └─ Coordinator（WebSocket，进程后端用，保留）
└─ 远端隔离根：父加载器 JdkOnlyLoader（只有 JDK）
    ├─ Fabric：URLClassLoader → 原生 KnotClient
    └─ NeoForge：FarModuleLayer（bootstraplauncher/SJH/JarJarFS/ASM）→ 原生 BootstrapLauncher → ModLauncher → FML
        └─ bridge ─ RuntimeClient(InJvmTransport)
```

跨 Loader 只传 JDK 类型：`String` 协议帧、`Map<String,String>` 上下文、`Boolean/Integer` 状态。`Wire`、`Messages`、Gson 和 Minecraft 对象都不跨端。两端各有一份 `dev.mmlf.common.*`、Mixin、注册表和 LWJGL。

### 阶段 1：Runtime 管理层（`launcher/.../runtime/`）

- `RuntimeSpec` / `JavaLaunch`：一次 Runtime 启动的描述（主类、classpath、module path、属性、JVM 选项、参数），两种后端共用。`JavaLaunch.parse/command` 可以互相转换。
- `RuntimeBackend`、`RuntimeHandle`（STARTING/RUNNING/EXITED/FAILED，带 `exit()` future 和 `stop()`）。
- `ProcessBackend`：迁入原 `SessionHost.start/launchInstalled/destroyTree`。
- `RuntimeManager`：持有会话，统一负责启动、退出回调、监督（主端退出、离线、注册超时、全部退出）和清理。
  - `Launcher`、`Manager`、`SessionBootstrap` 原来各写一份的监督逻辑改为调用它。
  - `Manager.reportExit` 统一写 `remote-error.txt`。
- `SessionHost` 只保留建会话、管理 Coordinator 端点（`IN_JVM`/`WEBSOCKET`）和准备 Mod 的职责。
- `DevRunSpecs`：从 Loom `launch.cfg`、ModDev 运行文件，以及新增 Gradle 任务 `writeClientRunSpec`（`gradle/client-run-spec.gradle`）读出远端的原生开发启动描述。
- `bridge-common/RuntimeContext`：Runtime 身份统一从这里解析，先查 In-JVM 注册表，再查 `-D` 参数和环境变量。
  - 单 JVM 内远端不读 `mmlf.runtime`、`mmlf.session` 这些 JVM 全局属性。
  - 被拉起的 Runtime `mayHost()=false`，这是防止循环拉起的关键。
- 后端选择：`-Dmmlf.backend` 或 `MMLF_BACKEND`，默认 `injvm`（按用户决定，单 JVM 是架构替代）；`process` 为过渡期回退。

### 阶段 2：JVM 内通信

- `CoordinatorCore`：从原 `Coordinator` 拆出的路由与状态，规则一字未改。`Coordinator` 改为 WebSocket 前端，对外 API 不变。
- `InJvmPort`：连接契约是 `BiFunction<String,Consumer<String>,Consumer<String>>`，`null` 帧表示关闭。每个连接的两个方向各用一个投递线程，不在发送方栈上回调，避免在 `stateLock` 内重入。
- `common`：抽出 `Transport` 接口，实现为 `WebSocketTransport` 和 `InJvmTransport`；`Session` 接受 `injvm://local`。`ReliableLink` 及上层协议不变。

### 阶段 3：In-JVM Backend（`InJvmBackend`）

1. **防重入**：JVM 级注册表 `running:<loader>` 守卫，并拒绝启动与宿主同类型的 Loader。远端上下文 `primary=false`，绝不托管会话。
2. **隔离根**：`JdkOnlyLoader`，见问题 3。
3. **启动属性窗口**：远端启动期间设置它自己的 `java.class.path`、log4j 配置、LWJGL 原生库目录、`fabric.*`/`fml.*` 等，并临时清除宿主 Loader 专属的属性。远端 bridge 初始化后恢复宿主的值。
4. **线程**：远端在 `mmlf-<id>-main` 线程上运行，TCCL 设为远端加载器，通过反射调用原生 `main`。
5. **NeoForge 在 Fabric JVM 中**（`FarModuleLayer` + `JvmAccess` + `ClassPatch`）：
   - 为 `-p` 中的模块建立一个位于 boot 层之上的 ModuleLayer；
   - 通过受信 Lookup 补齐 `--add-opens/--add-exports`（实测 0 项跳过）；
   - 注入 `union`/`jij`/`path` 文件系统；
   - 只改两处常量池方法引用：BootstrapLauncher 的 `ModuleLayer.boot()`，以及 SJH `ModuleClassLoader` 的 `ClassLoader.getPlatformClassLoader()`。字节码指令本身未改动。
6. **退出**：`InJvmExitMixin` 把 `Minecraft.destroy/crash` 中的 `System.exit` 改为记录退出码后返回。宿主 `stop()` 通过注册表请求远端 bridge 调用 `mc.stop()`。

### 阶段 4：单窗口渲染

远端沿用现有 `WindowMixin`，用独立的 LWJGL/GLFW 副本在自己的线程上创建隐藏窗口和 GL 上下文，保留完整的原生渲染。画面链路复用 `FrameExporter`（PBO + fence）→ `FrameFile` → `RemoteCompositor`，同 JVM 内无需修改。没有禁用远端渲染。

## 二、发现的问题与解决

| # | 复现条件与现象 | 原因 | 解决 |
|---|---|---|---|
| 1 | 探针：两份 LWJGL 共用同一个原生目录，或共用一份 GLFW | JNI 库只能被一个 ClassLoader 加载；GLFW 不支持在另一线程上建窗口（共享模式下 `window failed`） | 每端独立的 LWJGL 副本和原生目录。`tools/GlfwTwinProbe.java` 证明 GLFW 3.4 双副本可以并行建窗口和 GL 上下文（重复 3 次） |
| 2 | NeoForge 近端拉起 Fabric：`Mixin transformation of Main failed`，堆栈丢失 | 远端 log4j 继承了 NeoForge 设置的 `log4j2.contextSelector=MLClassLoaderContextSelector` | 启动窗口期清除宿主 Loader 专属属性；失败的完整异常链写入 `<会话>/<id>.log` |
| 3 | `LinkageError: loader constraint violation … ClassNode … previously loaded by 'app'` | NeoForge 用 `-p` 把 ASM 放进 boot 层（`app` 加载器）；JDK 内置加载器共享"包→模块"表，平台加载器会把这些包转交给 `app` | `JdkOnlyLoader`：隐藏宿主 boot 层中所有非 JDK 模块的包 |
| 4 | Fabric 远端在 `Window.<init>` 崩溃：`setFunctionMissingAddresses has been called already` | LWJGL 在 JNI 函数表的保留槽里放了一张 JVM 级的"缺失函数"表，第二份副本发现槽已被占就拒绝 | `InJvmLwjgl` + `WindowMixin`：远端首次建 GL 上下文时临时让出该槽，之后恢复宿主的值（各线程的能力表在各自 JNIEnv 副本中，不受影响） |
| 5 | 远端正常关闭后 JVM 退出码为 1 | 抛出的退出 Error 被 Fabric 当作崩溃，Fabric 自己调用了 `System.exit(1)` | 退出改为"记录退出码并返回"：游戏自身的流程在调用 exit 后都会立即 return |
| 6 | Fabric 近端拉起 NeoForge：`ClassNotFoundException: AttachmentTarget` | FML 开发环境从系统类加载器（即 Fabric 的 classpath）找到了 Loom 处理过的 Minecraft jar | 改用 FML 原生的 `fml.modFolders` 中 `minecraft` 组；该组必须是目录，所以把 ModDev 的 `neoforge-<ver>.jar` 解包缓存为目录 |
| 7 | `ResolutionException: minecraft 和 neoforge 同时导出 …` | 问题 6 的第一版传的是 jar，FML 检查不通过，回退到了 Fabric 的 jar | 同问题 6，改为目录 |
| 8 | `NoClassDefFoundError: org/objectweb/asm/tree/ClassNode`（MixinExtras） | MixinExtras 没有声明读取 ASM，依赖 SJH 回退到平台加载器、再经 JDK 共享表找到 boot 层里的 ASM | SJH `ModuleClassLoader` 的平台加载器调用改为返回"JDK + 我们的 boot 层"的视图，与原生环境中平台加载器能看到的范围一致 |
| 9 | NeoForge 远端停在 `LoadingErrorScreen`，近端在 `WaitingScreen` 等到超时 | FML 开发环境的 `UserdevLocator` 扫描系统类加载器，扫到 Fabric 的 jar，产生 52 条"这是 Fabric Mod"的警告；隐藏窗口无法点击继续 | 后台 Runtime 只有警告、没有错误时，把警告全部写入日志，然后进入标题界面；有错误时保持原样 |
| 10 | `.gitignore` 中的 `runtime/` 会忽略新源码包 `launcher/.../runtime/` | 规则未锚定到根目录 | 改为 `/runtime/`、`/coordinator/runtime/` |

## 三、实际执行与结果

### 编译与自动化测试

- `gradlew -PcoreOnly :common:test :coordinator:test :launcher:test`：通过。
- `gradlew build`：BUILD SUCCESSFUL，23 个测试类共 72 个测试全部通过。其中：
  - 新增 `InJvmTransportTest`（4）：断线重连后恰好一次投递；同一会话第二个 Coordinator 被拒；`runtime_left` 广播；两个独立 `URLClassLoader` 各加载一份协议类后互发消息，结束后两个 ClassLoader 都被 GC 回收。
  - 新增 `RuntimeLayerTest`（5）：常量池重定向、Java 命令行往返解析、Loom/ModDev 文件解析、log4j 路径改写。
- `gradlew release`：生成 `build/release/mmlf-{fabric,neoforge}-1.0.0-demo.jar`。

### 真实 Minecraft 启动

日志在 `runtime/*.log` 和对应的 `runtime/session-*`，均不入库。

| 运行 | 命令（环境变量） | 结果 |
|---|---|---|
| NeoForge 近端 + Fabric 远端 | `MMLF_BACKEND=injvm MMLF_TEST_SECONDS=300 gradlew :neoforge-bridge:runClient` | 两端进世界；远端 130.9 fps；近端收到 642 帧，帧龄 p50 13.0 / p95 16.0 ms；捕获 p50 7.7 ms；只有 1 个窗口；退出码 0（问题 5 修复后） |
| Fabric 近端 + NeoForge 远端 | 同上，`:fabric-bridge:runClient` | 两端进世界；远端 105.9 fps；近端收到 635 帧，帧龄 p50 16.3 / p95 20.6 ms；捕获 p50 9.3 ms；只有 1 个窗口；退出码 0 |
| 自测：NeoForge 近端，In-JVM | 加 `MMLF_SELF_TEST=true` | 除 `BOUNDARY phase=unloaded` 外全部 `passed=true`（GUI ×3、红石 ×4、邻居 ×4、容器、碰撞、相机、生存模式、交互均为 SUCCESS） |
| 自测：Fabric 近端，In-JVM | 同上 | 同上；交互覆盖 farmersdelight 烹饪锅、mcwtrpdoors 活板门 |
| 自测：Fabric 近端，**双进程基线** | `MMLF_BACKEND=process …` | 同上，`BOUNDARY phase=unloaded` 同样失败，说明是既有问题（`chunk-follow` 报告中已记录），不是回归；同时证明重构后 `ProcessBackend` 可用 |

截图只截游戏窗口，不含其他屏幕内容：

- `neoforge-near-fabric-far.png`
- `fabric-near-neoforge-far.png`：左侧是 Fabric 端 mcw-doors 的门，右侧是 NeoForge 端 Farmer's Delight 的烹饪锅（远端渲染后合成进来）。

### 资源开销（实测，每种配置 1 轮自测，取进入世界后 metrics 的中位数）

| 配置 | 进程数 | RSS 合计 | 堆已用合计 | CPU（单核%） |
|---|---|---|---|---|
| Fabric 近端，单 JVM | 1 | 4142 MB | 约 1660 MB（共享堆） | 165 |
| Fabric 近端，双进程 | 2 | 2215 + 2022 = 4237 MB | 797 + 763 = 1560 MB | 52 + 79 = 131 |
| NeoForge 近端，单 JVM | 1 | 4755 MB | 约 1675 MB | 172 |

- 单 JVM 比双进程少约 95 MB（约 2%）。两套 Minecraft 的类、世界和纹理依旧是两份，省下的主要只是一个 JVM 的基础开销。
- CPU 读数单 JVM 更高，原因未分析。
- 每种配置只有一轮数据、未控制后台负载，以上数字只能当作量级参考。
- 消息 RTT 单 JVM 为 0.5–0.7 ms，双进程为 0.74–0.75 ms。

### 用户实机

用户表示已经实机试过（"对的 我试了下"）。具体看了哪个方向、哪些交互，尚待用户补充；在此之前，本报告不把任何具体交互记为"用户实机确认"。

## 四、限制与未完成

1. **发行版路径未接入单 JVM**：`ReleaseSession` 仍然启动 `Manager` 进程（进程后端）。下一步是在近端 JVM 内用 `VersionProfile` 生成 `JavaLaunch`，交给 `InJvmBackend`。
2. **模块补齐依赖 JDK 内部实现**：`sun.misc.Unsafe` 取 `IMPL_LOOKUP`、`Module.implAddOpens`、`FileSystemProvider.installedProviders` 字段注入、常量池重定向，在 Temurin 21.0.3 上验证过。未来 JDK 可能收紧；那时需要回退为"要求启动参数"（`JvmAccess` 已经给出明确的错误提示）。
3. **NeoForge 开发环境的 `UserdevLocator` 仍会扫描宿主 classpath**：只产生警告，已在后台跳过，但扫描本身仍有开销和噪声。发行版的 FML 不走这条路径。
4. **无法拦截的退出**：Loader 自身在 Mixin 生效前出错（Fabric `FormattedException`、FML 早期失败）仍会调用 `System.exit`，连同近端一起退出。
5. **JVM 级共享状态**：
   - `System.out/err` 被两端各包装一次（日志前缀重复）；
   - 使用相对路径的 Mod 共用同一个 CWD；
   - GLFW、OpenAL 各有两份；
   - LWJGL 的全局"缺失函数"表在远端退出后可能指向远端 DLL，只在调用缺失 GL 函数时才会出问题；
   - 堆共享，需要更大的 `-Xmx`（Gradle 运行使用 JVM 默认值，约为物理内存的 1/4）。
6. **ClassLoader 泄漏**：只在单元测试中验证（协议层）。真实 Minecraft 远端退出后线程、原生库和 JNI 引用很多，**未验证**远端加载器能否回收。目前不支持同一 JVM 内反复重启远端。
7. **`Launcher`（`scripts/run-demo.ps1` 入口）的 in-JVM 路径只编译、未真实运行**：它在 in-JVM 模式下只启动主端的 Gradle 客户端，由主端自行托管会话；本次真实运行都是直接用 `gradlew :<loader>-bridge:runClient` 完成的。
8. **测试用的自动关闭**：`MMLF_TEST_SECONDS` 只在设置该环境变量时生效，用于无人值守测试。
9. **双进程后端是过渡期回退**：用户决定单 JVM 取代双进程。`Manager` 进程协议、`managerJar` 内嵌、`MMLF_HOST_PID` 看门狗等在发行版接入单 JVM 后才能删除。这次没有删除，因为发行版仍依赖它们。

## 五、下一阶段（统一世界、远端裁剪）所需接口

- `RuntimeHandle` 增加暂停/恢复，以及 tick 许可（已有 `TickSync`、`ServerTickMixin` 实验）。可以沿用 InJvm 注册表的 JDK 类型键，或者新增可靠消息类型。
- 世界所有权：`CoordinatorCore.world`（`WorldIndex`）作为统一世界的所有权表，远端只提交它所拥有的对象。
- 帧通道：单 JVM 内可以把 `FrameFile` 换成直接传 `ByteBuffer`（JDK 类型），去掉文件锁的系统调用；接口位置在 `BridgeCore.captureFrame` 和 `RemoteCompositor` 上传之间。
- 远端裁剪：`RuntimeContext.background()` 已是统一判断入口，裁剪逻辑可以挂在这里。

## 六、文件清单（本模块）

- **新增**：
  - `launcher/.../runtime/`：`RuntimeSpec`、`JavaLaunch`、`RuntimeBackend`、`RuntimeHandle`、`RuntimeManager`、`ProcessBackend`、`InJvmBackend`、`DevRunSpecs`、`FarModuleLayer`、`JvmAccess`、`ClassPatch`、`JdkOnlyLoader`
  - `coordinator/`：`CoordinatorCore`、`InJvmPort`
  - `common/`：`InJvm`、`Transport`、`WebSocketTransport`、`InJvmTransport`
  - `bridge-common/`：`RuntimeContext`、`InJvmExit`、`InJvmLwjgl`、`mixin/InJvmExitMixin`
  - `gradle/client-run-spec.gradle`
  - 测试：`InJvmTransportTest`、`RuntimeLayerTest`
  - 本目录：`tools/GlfwTwinProbe.java`、`tools/capture-window.ps1`、两张截图
- **修改**：
  - `Coordinator`、`RuntimeClient`、`Session`、`SessionHost`、`Launcher`、`Manager`
  - `SessionBootstrap`、`BridgeCore`（初始化、隐藏判定、停止请求）、`DemoScene`
  - `mixin/WindowMixin`、`mixin/RenderSystemMixin`、`mmlf.bridge.mixins.json`
  - `NeoForgeBridge`（只有警告时跳过加载警告屏）
  - 两个 bridge 的 `build.gradle`、`.gitignore`
- **删除**：`SessionHost.launch/launchInstalled/start/destroyTree/RUN_TASKS`（迁入 `ProcessBackend`/`DevRunSpecs`）；`Manager.supervise` 和 `Launcher` 中的监督循环（由 `RuntimeManager.supervise` 取代）。
