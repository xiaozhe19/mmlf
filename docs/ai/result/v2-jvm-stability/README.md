# v2 工作流 A：单 JVM 稳定性与隔离（进行中：编译与自动化测试通过，真实 Minecraft 未验证）

日期：2026-11-05。需求：`docs/manual/Single JVM Two Runtime v2.md` 第二节；实施计划 M1。

**状态说明**：本模块代码已完成，`gradlew build` 与新增单元测试通过。用户的 GPU 正在训练模型，本轮**没有启动真实 Minecraft**。下列行为在真实游戏中均未验证：作用域属性对 Fabric/NeoForge 加载器的实际影响、有序关闭、LWJGL 槽位检查、阶段上报。因此本报告不是完成报告。

## 一、实现了什么

### A1 Runtime 私有系统属性（`ScopedProperties`）

旧行为：`InJvmBackend` 在远端启动期间把远端的 `java.class.path`、log4j 配置、LWJGL 目录等写入 JVM 全局属性，并清除宿主 Loader 专属的键（`fabric.`、`fml.`、`log4j`、`mixin.` 等），等远端 bridge 初始化后才恢复。这个窗口最长 180 s，期间近端线程读到的是远端的值，或者干脆读不到。

新行为（默认，`-Dmmlf.injvm.properties=scoped`）：

- `launcher/.../runtime/ScopedProperties`：`Properties` 子类，通过 `System.setProperties` 安装一次。
  - 宿主的属性作为基础层；每个远端 Runtime 有一个覆盖层，放它启动参数里的值，宿主专属键在覆盖层里标为"不存在"。
- 判定线程归属：线程位于远端专属 `ThreadGroup mmlf-<id>` 内（子线程会继承创建者的线程组），或者线程的上下文类加载器派生自远端的类加载器。
- 远端线程的读写只走覆盖层；其他线程只能看到、也只能修改宿主的值。宿主永远看不到远端的值。
- `keySet`/`entrySet`/`stringPropertyNames` 等枚举返回远端视角的合并视图，因为 log4j 会枚举全部属性。
- `mmlf.injvm.*` 键和非 String 值（例如 In-JVM 注册表）始终写入基础层，两端共享。
- 回退：`-Dmmlf.injvm.properties=window` 恢复旧的全局窗口行为（`applyProperties`/`restoreProperties` 保留）。

已知限制（不能声称完全隔离）：

- 既不在远端线程组、上下文加载器也不属于远端的线程（JDK `ForkJoinPool.commonPool`、虚拟线程等）一律视为宿主线程。这类线程读取远端覆盖的键时，每个键记录一次 `injvm_property_foreign_read`。
- JDK 在启动时缓存的值（`java.io.tmpdir`、`user.dir`）无法按 Runtime 区分。
- 安装前已经持有原 `Properties` 对象引用的库，此后看不到新写入的属性。安装发生在宿主托管会话、创建 `InJvmBackend` 之后，首次启动远端时。

### A2 LWJGL / GL 生命周期

- `InJvmLwjgl`：远端让出槽位时，把宿主表地址记入注册表 `lwjgl:hostMissingTable`，把远端自己建的表记入 `lwjgl:guestMissingTable`。
- 新增 `verifyHostTable()`：宿主在每个远端退出后（`RuntimeManager.onExit`）检查槽位。
  - 若不是宿主表，就恢复宿主表并记录 `lwjgl_slot_verified`。日志中注明当前值是否等于远端表。
- 有序关闭：`MinecraftMixin` 新增 `destroy` HEAD 注入，调用 `BridgeCore.destroying()` → `SessionBootstrap.stopRuntimes()`。
  - 近端释放窗口和 GL 资源之前，先 `RuntimeManager.close()`：请求每个远端 `mc.stop()`，各自最多等 10 s。
  - 耗时与仍存活的 Runtime 记录在 `hosted_runtimes_stopped`。超时后近端照常继续退出，不保证远端已干净退出。
- 线程归属断言：没有新增。Minecraft 自带的 `RenderSystem.assertOnRenderThread` 已经覆盖各自 Runtime 的渲染线程，本轮不重复实现。

### A3 统一生命周期

- `RuntimeHandle.State` 扩展为 `STARTING → LOADER_INIT → GAME_INIT → IN_WORLD → STOPPING → EXITED/FAILED`；进程后端仍只报 `RUNNING`。
  - `alive()` 改为"非 EXITED/FAILED"。
- 远端 bridge 通过注册表 `phase:<loader>` 上报阶段：
  - bridge 初始化：`bridge`
  - 第一次客户端 tick：`game`
  - 发出 `world_ready`：`world`
  - `stopBackground` 或 `Minecraft.stop`：`stopping`
  - 进入 `stopping` 之后不会再回退。
- 原来轮询 180 s 的属性恢复任务，改为守护线程 `mmlf-<id>-watch`：
  - 跟踪阶段变化（`injvm_phase` 日志带上前一阶段的耗时）；
  - 窗口模式下按旧规则恢复属性；
  - 按阶段判断超时，可配置：
    - `loaderTimeoutMs`：180 s
    - `gameTimeoutMs`：300 s
    - `worldTimeoutMs`：600 s
    - `stopTimeoutMs`：60 s
  - 超时后记录 `injvm_phase_timeout`，写入 `<会话>/<id>.log`，完成 `RuntimeHandle.stalled()`，并请求远端停止（`stopping` 阶段超时除外）。
  - `RuntimeManager.onStall` 把停滞通知 `Manager.reportExit`，后者写 `remote-error.txt`，近端界面显示错误。
- 资源只清理一次：`Handle.finish` 使用 CAS 守卫。
  - 远端 bridge 在注册表 `cleanup:<loader>` 留下一个 `Runnable`（关闭 `RuntimeClient`、`PoseFile`）。远端主线程结束后，后端运行它一次。
- 停止请求的检查移出了"已连接"分支：远端还在加载、尚未加入会话时，也能响应宿主的停止请求。
- Loader 早期的 `System.exit`（Fabric）：
  - Fabric 远端的根加载器改为 `InJvmBackend.TrappingLoader`。
  - 它加载 `net/fabricmc/loader/` 下的类时，用已有的 `ClassPatch` 把 `java/lang/System.exit` 方法引用改指向本加载器内再定义一份的 `InJvmExitTrap`（只引用 JDK 类型）。
  - `InJvmExitTrap` 抛出 Error，远端主线程以 FAILED 结束，近端不受影响。
  - 同时为这些类提供 `CodeSource`，Fabric 定位自身 jar 时需要它。
- NeoForge 未做同样的拦截：ModLauncher/FML 由 SJH 的 `ModuleClassLoader` 加载，不经过我们的加载器。其早期 `System.exit` 仍会结束整个 JVM。

### A4 运行数据隔离

- 已有：
  - 远端 gameDir 在会话目录下（`DevRunSpecs.prepareGameDirectory`）；
  - log4j 配置改写到 gameDir（`DevRunSpecs.gameLog4j`）；
  - LWJGL 解压目录按 Loader 区分（`org.lwjgl.system.SharedLibraryExtractPath`）。
  - 原先这些值在全局窗口期间对宿主可见，现在只存在于远端的覆盖层。
- 未做：`java.io.tmpdir` 和 CWD 无法按 Runtime 区分（JDK 启动时缓存）。依赖相对路径的 Mod 仍共用宿主的工作目录。本轮没有用测试 Mod 实测写入位置。

## 二、发现的问题

| # | 问题 | 原因 | 处理 |
|---|---|---|---|
| 1 | 远端退出后，它的 `RuntimeClient` 仍每秒重连，投递线程仍向已结束的远端回调消息 | 远端的清理放在 `Runtime.addShutdownHook` 中，单 JVM 内这要等整个 JVM 退出才会执行 | 改为注册表清理回调，由后端在远端主线程结束时运行一次（代码审查发现，未在真实游戏复现） |
| 2 | 宿主属性在远端启动期间最长 180 s 不可见或被改写 | 远端属性通过全局 `System.setProperty` 设置 | `ScopedProperties` |
| 3 | 远端卡在加载阶段时，宿主的停止请求无效 | 停止检查只在 `link!=null && !primary` 分支内执行 | 移到 tick 开头 |
| 4 | 近端关闭时，远端可能仍在自己的 GL 驱动调用中 | 只有 JVM 关闭钩子才会关闭 `RuntimeManager` | `Minecraft.destroy` HEAD 处有序停止 |

## 三、实际执行与结果

| 层级 | 命令 | 结果 |
|---|---|---|
| 编译 | `gradlew :fabric-bridge:compileJava :neoforge-bridge:compileJava` | 成功（仅有已存在的 Mixin 映射警告） |
| 自动化测试 | `gradlew -PcoreOnly :common:test :coordinator:test :launcher:test` | 82 个测试全部通过（原 72 + 新 10） |
| 完整构建 | `gradlew build` | BUILD SUCCESSFUL |
| 真实 Minecraft 启动 | — | **未执行**（GPU 被占用） |
| 真实画面与交互 | — | **未执行** |

新增测试：

- `InJvmLifecycleTest`（3）：用替身 Runtime（`fake/FakeRuntime`，在 JDK-only 根下的独立加载器中运行，只通过注册表通信）验证：
  - 阶段依次推进到 `IN_WORLD`；
  - 同一 Loader 拒绝重复启动；
  - 停止后退出码为 0、状态为 `EXITED`，清理恰好执行一次，再次 `close` 不会重复清理；
  - 注册表键全部移除；
  - 作用域属性：远端主线程及其子线程看到自己的值、看不到宿主的专属键，宿主看不到远端的值和远端的写入；
  - `bridge` 阶段超时会触发 `stalled`、写日志、请求停止；
  - 远端抛出异常时状态为 `FAILED`，并带有异常原因。
- `ScopedPropertiesTest`（5）：
  - 只安装一次，注册表保留；
  - 两端值互不可见；远端的写入和清除都不影响宿主；
  - 枚举返回远端视图；
  - `mmlf.injvm.*` 共享；
  - 按上下文类加载器判定归属。
- `ExitTrapTest`（2）：编译一个位于 `net.fabricmc.loader.impl.test` 包、调用 `System.exit(3)` 的类，经 `TrappingLoader` 加载后：
  - 调用得到带退出码的 Error，JVM 不退出；
  - 其他 `System` 调用不受影响；
  - `CodeSource` 正确；
  - 陷阱类是该加载器自己的副本；
  - 其他包的类不做修改。

## 四、未完成、限制与下一步

1. **真实验证（最高优先级，待 GPU 空闲）**：两个方向都运行 `MMLF_BACKEND=injvm MMLF_SELF_TEST=true MMLF_TEST_SECONDS=300 gradlew :<loader>-bridge:runClient`，确认：
   - 作用域属性下两个 Loader 都能正常启动（尤其是 log4j 枚举属性、Knot 读取 `java.class.path`、NeoForge 读取 `legacyClassPath`）；
   - `injvm_phase` 依次出现，`hosted_runtimes_stopped` 的耗时，`lwjgl_slot_verified` 的结果；
   - 退出码为 0；
   - `injvm_property_foreign_read` 记录了哪些键。
   - 若作用域属性导致启动失败，用 `-Dmmlf.injvm.properties=window` 对照。
2. 单 JVM **没有**进程级故障隔离：
   - 原生崩溃（驱动、JNI）、OOM、NeoForge 早期 `System.exit`，都会结束整个 JVM；
   - 远端线程不响应停止时无法强制结束。
3. `System.out/err` 被两端各包装一次的问题（日志前缀重复）本轮未处理。`System.out` 是静态字段，无法按线程区分读取方，需要另外设计。
4. 远端类加载器能否被回收仍未验证，不支持在同一 JVM 内重启远端。

## 五、文件清单

- 新增：
  - `launcher/.../runtime/ScopedProperties.java`、`InJvmExitTrap.java`
  - 测试：`InJvmLifecycleTest.java`、`ScopedPropertiesTest.java`、`ExitTrapTest.java`、`fake/FakeRuntime.java`
- 修改：
  - `launcher/.../runtime/`：`InJvmBackend.java`、`RuntimeHandle.java`、`RuntimeManager.java`
  - `launcher/.../Manager.java`
  - `bridge-common/.../`：`BridgeCore.java`、`SessionBootstrap.java`、`InJvmLwjgl.java`、`mixin/MinecraftMixin.java`

## 补充：2026-11-05 实机自测（默认配置，GPU 短暂空闲时执行）

命令：`MMLF_BACKEND=injvm MMLF_SELF_TEST=true MMLF_TEST_SECONDS=300 gradlew :<loader>-bridge:runClient`，两个方向各 1 轮，默认 `scoped` 属性模式、`file` 帧通道、ownership `off`。

| 项目 | Fabric 近端 / NeoForge 远端 | NeoForge 近端 / Fabric 远端 |
|---|---|---|
| Gradle 结果 | BUILD SUCCESSFUL（5m35s），退出码 0 | BUILD SUCCESSFUL（4m58s），退出码 0 |
| 阶段（耗时） | loader→bridge 23.0 s → game 5.0 s → world 4.7 s → stopping | loader→bridge 24.2 s → game 2.0 s → world 9.6 s → stopping |
| 阶段超时 / 停滞 | 无 | 无 |
| `injvm_property_foreign_read` | 0 次 | 0 次（`scoped_properties_installed entries=71`） |
| 自测 | 除已知的 `BOUNDARY phase=unloaded`（`primary_never_unloaded`）外全部 `passed=true`：相机、碰撞、GUI（screen/container/thirdparty）、生存放置/破坏、红石与邻居（on/off/reloaded）、容器、边界 away/reloaded | 同左 |
| 有序关闭 | `hosted_runtimes_stopped alive=[] elapsedMs=683` | `alive=[] elapsedMs=466` |
| LWJGL 槽位 | `lwjgl_slot_verified host=true restored=false` | 同左；远端启动时 `lwjgl_slot_yielded` |

结论：v2 的属性隔离、阶段上报、有序关闭和槽位校验在两个方向的默认配置下运行正常，没有出现回归。只跑了 1 轮，未覆盖：`window` 回退模式、阶段超时的实际触发、远端崩溃路径。窗口数量和画面本轮没有截图核对。

日志中的 `mmlf_*_test` 方块 missing model 警告来自测试 Mod 中已有的方块（资源未提供），与本轮改动无关。`@Pseudo` 的 `SodiumBlockRendererMixin` 在未装 Sodium 时打出一条 `ClassNotFoundException` 警告，属预期行为。
