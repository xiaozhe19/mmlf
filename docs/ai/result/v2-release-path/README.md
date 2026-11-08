# v2 工作流 E：发行版单 JVM 启动路径（进行中：代码完成，自动化测试通过；真实启动器与模拟环境启动均未执行）

日期：2026-11-05。需求：`docs/manual/Single JVM Two Runtime v2.md` 第六节；实施计划 M5。

**状态**：用户决定本轮只做到开发路径模拟。另外 GPU 被占用，计划中的"用开发产物拼一个最小 .minecraft、实际启动一次"**也没有执行**。下文所有结论都只到自动化测试为止。本次**没有**创建 GitHub Release，也**没有**推送标签。

## 一、实现了什么

### 发行版远端的准备与启动，两种后端共用（`launcher/ReleaseRemote`）

- `prepare(root, primary, mcVersion, gameDir, progress)`：
  - 从玩家启动器安装的版本中，找出另一个 Loader 在同一 Minecraft 版本下最新且文件完整的版本；
  - 确定实例目录：版本隔离时用版本自己的目录，否则用 `.minecraft`；
  - 检查实例 mods 中有该 Loader 的 MMLF Mod；
  - 暂存到 `<gameDir>/mmlf/<remote>`。
  - 这些逻辑原本只存在于 `Manager` 进程中，现在由 `Manager` 改为调用这里，行为不变。
- `inJvm(prepared, sessionDir, account, launcher)`：
  - 用该版本自己的启动命令（`VersionProfile.command`）经 `JavaLaunch.parse` 拆回各部分；
  - 去掉 `-Xmx/-Xms/-Xss`（堆属于近端 JVM）和所有 `-Dmmlf.*`（身份来自 In-JVM 注册表）；
  - `org.lwjgl.system.SharedLibraryExtractPath` 设为远端自己的 natives 目录：同一个原生库文件不能被两个类加载器加载。
- `process(...)`：进程后端使用的命令，与原来相同。

### `ReleaseSession` 的 In-JVM 分支（默认）

- `RuntimeManager.configuredBackend()` 为 `injvm`（默认）时，近端游戏在自己的 JVM 内：
  1. `ReleaseRemote.prepare`，进度显示在原有的 `SessionStatus` 面板；
  2. 清理旧会话目录；
  3. `SessionHost.create(..., IN_JVM)`，然后 `new RuntimeManager(host, new InJvmBackend(host))`；
  4. `SessionBootstrap.adopt(manager)`：注册关闭钩子，近端 `destroy` 前有序停止远端，远端退出后检查 LWJGL 槽位；
  5. NeoForge 远端关闭早期窗口；
  6. `Manager.reportExit`，处理远端退出或停滞时的错误显示；
  7. 以 `inJvm` 规格启动远端。
- 不再启动 `mmlf-manager.jar` 进程。
- `-Dmmlf.backend=process`（或 `MMLF_BACKEND=process`）时，仍走原来的 Manager 进程路径。

### 发行环境要求（写入文档，尚未在发行环境验证）

| 项目 | 要求 |
|---|---|
| JDK | 21。v1 在 Temurin 21.0.3 上验证过 `JvmAccess` 用到的内部 API |
| Minecraft | 1.21.1 |
| Fabric | Loader 0.16.10（开发环境版本），Fabric API 0.115.4+1.21.1 |
| NeoForge | 21.1.265 |
| 启动器 | 官方启动器、HMCL、PCL（`minecraft.launcher.brand` 检查不变） |
| 安装方式 | 两个 Loader 版本用同一启动器安装，各自的 mods 中放入对应的 MMLF jar（普通 Mod 安装方式） |

## 二、发现的问题与未确认点

1. **生产版 NeoForge 作为远端时如何找到游戏**：开发环境依赖 `fml.modFolders` 的 `minecraft%%` 做法（`DevRunSpecs`），生产环境由 FML 从 libraries 中找到打过补丁的客户端 jar。`ReleaseRemote.inJvm` 没有加任何开发环境专用的属性。它在 `FarModuleLayer` 下是否成立，**未经运行，无法确认**。
2. **生产版 Fabric 的 intermediary 名**：`SodiumBlockRendererMixin`、`LevelRendererMixin` 新增的注入在 `remapJar` 时由 Loom 重映射。`gradlew release` 构建成功，但发行 jar 中的注入是否命中，未经运行。
3. **旧 `Manager` 进程代码保留**：需求要求在正式单 JVM 路径验证之前保留它作为对照，本轮不删除。

## 三、实际执行与结果

| 层级 | 内容 | 结果 |
|---|---|---|
| 编译与打包 | `gradlew build`；`gradlew release` → `build/release/mmlf-{fabric,neoforge}-1.0.0-demo.jar` | 成功 |
| 自动化测试 | `ReleaseRemoteTest`（3）；原有 `VersionProfileTest`（5）未受影响 | 通过 |
| 模拟环境实际启动 | — | **未执行** |
| 真实启动器（官方 / HMCL / PCL） | — | **未执行**（按用户决定） |

`ReleaseRemoteTest` 用仿照真实结构的版本 JSON 构造临时 `.minecraft`：原版 1.21.1，加上继承它的 NeoForge 21.1.265 与 Fabric Loader 0.16.10；包含 `-p` 模块路径、`--add-opens/--add-exports/--add-modules`、`-Xss1M`、带 Windows 规则的 `-XX:HeapDumpPath`。

- Fabric 近端 + NeoForge 远端：
  - 版本隔离实例、只暂存 NeoForge 的 Mod、进度回调；
  - In-JVM 启动的主类、模块路径、类路径（Loader 的 ASM 9.7 覆盖原版的 9.6，客户端 jar 在内）、JVM 选项（保留 `--add-*`，去掉 `-Xss`）；
  - `SharedLibraryExtractPath` 指向远端自己的 natives；`libraryDirectory`、启动器名；不含 `mmlf.*`；
  - 游戏参数（`--gameDir`、`--launchTarget`、账户）、日志路径；
  - 与进程命令行之间的往返解析结果一致。
- NeoForge 近端 + Fabric 远端：共享实例、Knot 主类、无模块路径、`FabricMcEmu` 属性（含空格）原样保留；进程回退命令。
- 远端版本缺失时，在暂存任何文件之前报错。

## 四、下一步

1. GPU 空闲后，先做模拟环境的实际启动：用 Gradle 缓存中的 jar 拼一个最小 `.minecraft`，设置 `minecraft.launcher.brand=pcl`，启动 `release` jar。两个方向都要跑。
2. 由用户在真实启动器中验证：两个方向分别作为启动入口，检查只有一个窗口、两端都进入世界、退出码。
3. 两者都通过后，删除 `Manager` 进程、`managerJar` 内嵌以及 `MMLF_HOST_PID` 看门狗。

## 五、文件

- 新增：
  - `launcher/.../ReleaseRemote.java`
  - `launcher/src/test/.../ReleaseRemoteTest.java`
- 修改：
  - `launcher/.../Manager.java`（改用 `ReleaseRemote`；`removeOldSessions` 改为 public；写错误文件的逻辑抽出为方法）
  - `bridge-common/.../ReleaseSession.java`（In-JVM 分支）
  - `bridge-common/.../SessionBootstrap.java`（`adopt`）
