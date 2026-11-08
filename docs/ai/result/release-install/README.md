# 快速安装（发行包）实现报告（进行中：未经真实启动器验证）

## 1. 实现了什么

目标：玩家用自己的启动器（官方启动器、HMCL、PCL）分别装好 Fabric 和 NeoForge 两个 1.21.1 版本，各自的 `mods` 里放入对应的 MMLF JAR。之后只需启动其中一个，另一个会在后台自动拉起并融合。

启动链：**启动器 → 近端（MMLF Mod）→ 会话管理器 `mmlf-manager.jar` → 远端**。

- **发行产物**：`./gradlew.bat release` 生成 `build/release/mmlf-fabric-0.1.0.jar` 和 `mmlf-neoforge-0.1.0.jar`。两个 JAR 里都内嵌了 `META-INF/mmlf/mmlf-manager.jar`，即 `:launcher:managerJar`，是一个包含 launcher、coordinator、common、gson、Java-WebSocket 的独立 fat jar。所以逻辑上是 3 个 JAR，玩家每个实例只需放 1 个。测试 Mod 不进发行包。
- **近端**（`bridge-common/.../ReleaseSession.java`、`BridgeCore.joinRelease`）：
  - 只有发行 JAR 内嵌了 manager，并且没有 `mmlf.root` 和会话参数时，才进入发行模式。开发运行行为不变。
  - 会话推迟到第一个客户端 tick 才启动，因为账号信息要等 `Minecraft.getUser()` 可用时才能拿到。
  - 检查 `minecraft.launcher.brand`：只接受以 `minecraft-launcher`、`hmcl`、`pcl` 开头的值（不区分大小写），其他启动器直接报错。
  - 从 classpath（含 NeoForge 的 `legacyClassPath`）里 `libraries` 目录的上级推出 `.minecraft`；推不出时，从游戏目录往上找同时含 `versions` 和 `libraries` 的目录。
  - 把 manager 提取到 `<游戏目录>/mmlf/mmlf-manager.jar`，用同一个 JRE 的 `javaw.exe` 启动它。通过 stdin 交一行 JSON：主端、MC 版本、根目录、启动器名和版本、账号（用户名、UUID、令牌、类型、xuid、clientId）、近端 PID、近端最大堆。从 stdout 读取 `MMLF-SESSION <文件>` 或 `MMLF-ERROR <原因>`。
  - 出错时在 tick 中抛出 `IllegalStateException("MMLF: …")`，游戏崩溃并生成崩溃报告，也就是"直接报错"。
- **会话管理器**（`launcher/.../Manager.java`、`VersionProfile.java`）：
  - 扫描 `.minecraft/versions`，沿 `inheritsFrom` 合并版本 JSON，识别加载器和 MC 版本。NeoForge 看 `--fml.neoForgeVersion` / `--fml.mcVersion` 或 `net.neoforged:neoforge` 库；Fabric 看 `fabric-loader` 和 `intermediary` 库。
  - 选择对端加载器中版本号最高的一个，检查库文件和客户端 JAR 是否齐全。
  - 远端实例目录：`versions/<id>/mods` 存在时按版本隔离处理，否则用 `.minecraft`。其中必须有含 `dev/mmlf/bridge/BridgeCore.class` 的对端 JAR。
  - 远端在会话目录 `<近端游戏目录>/mmlf/sessions/session-*/<runtime>` 中运行：对端 Mod 用硬链接（失败时复制）放进去，`config/` 整体复制过去，玩家自己实例里的选项和存档不会被改动。只保留最近 2 个旧会话。
  - 按版本 JSON 规则（操作系统、架构；可选 feature 一律关闭）拼出 classpath 和 JVM/游戏参数，替换启动器占位符。额外加上 `-Xmx`（与近端相同）、`-Dmmlf.session`、`-Dmmlf.runtime`、`-Dmmlf.logLevel`。环境变量沿用开发时的 `MMLF_HIDE_SECONDARY`、`MMLF_HOST_PID`。
  - 监督：近端进程退出、主端断开、10 分钟内未注册，任一发生就清理远端并退出。远端提前退出会写 ERROR 日志。
- **正常游戏流程**（`bridge-common/.../WorldFollow.java`）：发行会话不写 `scenario.json`，所以 `DemoScene` 不会启用：不显示等待页，不自动建平地世界，不清理和铺设场地，也不修改 FOV 和帧率。近端走原版标题画面，由玩家自己选单人存档。
  - 主端进入单人世界后，每 5 秒广播一次 `mmlf:world`，内容为存档文件夹名、种子、是否平地世界、世界名；回到标题画面时发送 `mmlf:world_closed`。
  - 远端收到后，打开同名的影子世界；不存在就按相同的种子和类型（平地或普通）新建。打开后设为和平难度，关闭刷怪和天气循环。主端切换或退出世界时，远端保存并退出，回到标题画面。
  - 远端游戏目录改为固定的 `<近端游戏目录>/mmlf/<远端>/`，影子世界可以跨会话保留。会话目录只放会话文件和日志。`mods` 每次启动前清空后重新链接，`options.txt` 和 `config/` 每次都重新复制。崩溃原因只从本次会话开始之后产生的崩溃报告里提取。
  - 多人服务器（没有内置服务器）时不广播，远端停在标题画面。
- **测试模式与日志**：
  - `MmlfLog` 默认级别由 DEBUG 改为 INFO。开发运行（两个 `runClient` 和 `run-demo.ps1`）显式传 `mmlf.logLevel=DEBUG`，可用 `MMLF_LOG_LEVEL` 覆盖。
  - 发行路径创建会话时固定 `selfTest=false`，不写场景文件。
- **重构**：`SessionHost.create(runtimeDir, scene, primary, selfTest)` 改为通用入口，开发版 `create(root, …)` 委托给它。新增 `launchInstalled` 和 `suppressEarlyWindow`。

## 2. 发现的问题

1. **回复乱码**：manager 用 `System.out` 回复时走系统代码页（中文 Windows 为 GBK），近端按 UTF-8 读取，中文错误信息会乱码。复现：向 manager 发送一个找不到版本的请求，终端看到乱码。
2. **握手行可能被日志抢先**：`MmlfLog` 每条日志都会打印到 stdout，`SessionHost.create` 在回复之前就会写日志，近端读到的第一行就不是握手行。
3. **（PCL 实测）远端 Fabric 崩溃，近端无限等待**：环境为 PCL，NeoForge 21.1.265 作近端，Fabric Loader 0.20.1 + Fabric API 0.116.17 作远端。PCL 识别和远端版本挑选都正常，远端被拉起后在 `Initializing game` 阶段崩溃：`HostedKeyStateMixin` 的 `@Inject(method="isKeyDown")` 写了 `remap=false`，而 `InputConstants.isKeyDown` 在 intermediary 中叫 `method_15987`，正式环境找不到目标。开发环境用的是 Mojang 名，所以一直没有暴露。另一个问题是远端崩溃后近端没有任何提示，一直停在等待界面。
3b. **（PCL 第二次实测）远端卡在首次启动的无障碍和语言引导页**：Mixin 修复后远端正常加载（日志有 `MMLF bridge initialized: fabric`，没有崩溃），但一直没有进入世界。原因有两个：会话目录里没有 `options.txt`，所以远端被当成全新客户端，弹出 `AccessibilityOnboardingScreen`；而 `DemoScene` 是用 `getSimpleName().equals("AccessibilityOnboardingScreen")` 识别这一页的，正式环境的 Fabric 里类名是 intermediary 名，没有匹配上。窗口隐藏着，玩家也没法手动关掉这一页。
4. 远端日志里的 `Failed to fetch user properties`（401）在近端自己的日志中同样出现，是该账号本身的问题，与令牌传递无关，也不致命。
5. Windows 上无法通过 `ProcessHandle.Info.arguments()` 读取近端自己的启动参数，所以改为从 `Minecraft.getUser()` 取账号，从 classpath 推 `.minecraft`。

## 3. 如何解决

1. manager 对 `FileDescriptor.out` 包一层 UTF-8 的 `PrintStream`，专门用来回复。
2. 回复前把 `System.out` 指向 stderr（近端把 stderr 重定向到 `mmlf/manager-error.log`），回复后再把 stdout 和 stderr 都改写到会话目录的 `manager.log`。近端也会跳过所有不以 `MMLF-` 开头的行。
3. 去掉 `HostedKeyStateMixin` 的 `remap=false`，refmap 现在含 `isKeyDown -> Lnet/minecraft/class_3675;method_15987(JI)Z`。其余 `remap=false` 的目标（`RenderSystem`、`GlStateManager`）经核对映射表都不混淆，编译时剩下的两个 AP 警告（`RenderSystem.clear`、`GLFW`）也属于这一类。远端失败的提示：manager 发现远端退出后，从最新崩溃报告中提取 `Description` 和最后一个 `Caused by`（取不到时用退出码），写入会话目录的 `remote-error.txt`；近端在远端世界就绪之前每秒检查一次，读到后抛出 `MMLF: 远端 … 启动失败（…）。详见 <路径>`，游戏崩溃并生成报告。
3b. `DemoScene` 改用 `instanceof AccessibilityOnboardingScreen` 判断（会被 remap）。manager 把远端实例的 `options.txt` 复制到会话目录，并强制写入 `onboardAccessibility:false`；没有这个文件时只写这一行。这样远端会沿用玩家在该实例里的设置（语言、按键等），因为是复制，原文件不受影响。
4. 无需处理。
5. 见上文。取舍：会话推迟到第一个 tick，标题画面会短暂出现，然后才进入等待界面。

## 4. 结果

| 验证项 | 结果 |
|---|---|
| 编译 | `./gradlew.bat build release` 成功 |
| 自动化测试 | 全部通过。新增 `VersionProfileTest` 5 项（含崩溃原因提取）：继承合并和库替换、natives 规则、参数替换、版本挑选和文件缺失报错、远端 Mod 必须含 Bridge、旧会话清理。其他已有测试保持通过（common 40、coordinator 2、ModClassifier 1） |
| manager 独立进程 | 在 scratchpad 中用伪造的 `.minecraft` 实测：stub Fabric 版本，主类是一个写参数后休眠的 `Fake`。确认收到 `MMLF-SESSION`；远端以 `javaw.exe -Xmx2048m -Dmmlf.session=… -Dmmlf.runtime=fabric -Dmmlf.logLevel=INFO` 启动，`MMLF_HIDE_SECONDARY=true`，`--username/--gameDir/--accessToken` 替换正确，Mod 已放入会话目录；杀掉模拟的近端进程后，manager 以 0 退出，远端被结束。错误路径返回 UTF-8 中文 `MMLF-ERROR` |
| 发行包内容 | 两个 JAR 都含 `META-INF/mmlf/mmlf-manager.jar` 和 `ReleaseSession`；Fabric 含 refmap 和 JiJ；不含测试 Mod |
| 真实 Minecraft 启动 | PCL，NeoForge 作近端：近端正常加载，manager 成功拉起远端 Fabric，远端因上述 Mixin 问题崩溃。第一次修复后重测，远端不再崩溃，但卡在引导页（见 3b）。3b 修复后的 JAR **尚未重测** |
| 真实画面与交互 | **未执行** |
| 开发路径回归 | 只编译通过，本轮**没有**重新跑 `run-demo.ps1` 或 `runClient` 实机启动 |

产物：`build/release/mmlf-fabric-0.1.0.jar`、`build/release/mmlf-neoforge-0.1.0.jar`。

## 5. 未完成、限制和下一步

- **必须实测**：在官方启动器、HMCL、PCL 中分别安装 Fabric 和 NeoForge 1.21.1 并放入 JAR，从两端分别启动各验证一次。重点确认以下几点：
  - Fabric 发行 JAR 在正式环境（intermediary）下的 Mixin 和 remap 是否正常；
  - 三个启动器实际的 `minecraft.launcher.brand` 值（尤其 PCL 写的是什么，目前只是按前缀 `pcl` 猜的）；
  - 官方启动器下 NeoForge 的客户端 JAR 路径和 `ignoreList` 是否匹配；
  - 离线账号能否启动远端。
- 远端启动失败（比如对端 Mod 版本不兼容）时，只会记到 `mmlf/sessions/session-*/<runtime>.log`，近端会一直停在"等待其他 Runtime"界面，没有提示。下一步考虑由 manager 通过 Coordinator 通知主端。
- 影子世界只在种子和平地/普通这两项上与主端一致；自定义预设、数据包、修改地形生成的 Mod 都会让远端的原版地形与主端不同。远端的原版方块不会被渲染，影响的只是远端自己的碰撞和放置。
- 远端打开已有存档时，如果需要确认（例如版本升级或实验性功能提示），由于窗口是隐藏的，会卡在确认界面。
- 只支持标准 `.minecraft` 目录结构；HMCL"公共目录"之类的自定义库目录没有处理。
- 有多个对端版本时自动选版本号最高的那个，暂时不能手动指定。
- 令牌会出现在远端进程的命令行里（和启动器自己的做法一样），manager 日志不记录命令行。

## 6. 追加：远端启动进度与错误提示（进行中：未经真实启动器验证）

### 实现了什么

远端窗口是隐藏的，近端之前对它的状态没有任何显示：成功时看不出远端已经拉起，失败时直接让游戏崩溃。现在近端（主端）左上角有一个状态面板，失败时改为弹出错误界面，游戏继续单独运行。

- `bridge-common/.../SessionStatus.java`：维护阶段 `MANAGER → LAUNCHING → CONNECTED → READY`，以及 `LOST`、`FAILED`，只在主端或尚在启动会话的发行版近端出现，后台窗口永远不显示。
  - `MANAGER`：显示 manager 当前步骤（查找版本 → 检查模组 → 准备运行目录 → 启动进程），进度 0–15%。
  - `LAUNCHING`：远端 JVM 加载中，按"上次实测耗时"插值到 85%，耗时记录在 `<游戏目录>/mmlf/remote-startup-ms.txt`（首次默认 60 秒，限制在 10–300 秒）；超过 `max(180 秒, 3 倍预期)` 变黄并给出远端日志路径。
  - `CONNECTED`：在菜单中显示"已连接，进入世界后远端会同步打开同一存档"；在世界中显示"正在加载世界"。
  - `READY`：远端报告 `mmlf:world_ready` 后显示 5 秒后隐藏。
  - `LOST` / `FAILED`：在世界中只显示 10 秒，在菜单中常驻。
- `SessionErrorScreen.java`：失败时在标题界面弹出，显示完整原因，提供"打开日志文件夹"和"继续（不使用远端）"。玩家正在世界里时不打断，只显示面板，回到标题界面再弹出。
- 失败来源全部改为走 `SessionStatus.fail`，不再抛 `IllegalStateException`：不支持的启动器、找不到 `.minecraft`、manager 返回 `MMLF-ERROR`、manager 异常退出、会话初始化失败、`remote-error.txt`（远端进程退出，现在在远端就绪后也会检查）。
- `Manager` 在回复前输出 `MMLF-PROGRESS <比例> <文本>` 行，`ReleaseSession` 读取并更新面板；握手仍以 `MMLF-SESSION` / `MMLF-ERROR` 结束。
- 渲染挂点：新增 `GuiMixin`（`Gui.render` TAIL，无屏幕时）和已有 `ScreenMixin.renderWithTooltip` RETURN（有屏幕时）。
- `WorldFollow` 主端发送 `mmlf:world_closed` 时清空 `readyRuntimes`，否则换存档后面板会误报"已就绪"。

### 发现了什么问题

用户反馈"远端好像没被拉起"。检查 `C:\Users\ZZY\Desktop\mc\.minecraft\versions\1.21.1-NeoForge_21.1.265\mmlf\sessions\session-5545282577498376864`：`manager.log` 有 `runtime_registered {"runtimeId":"fabric"}`，`fabric.log` 有 `world create {"save":"新的世界"}`，09:36:07 进入世界。也就是说远端其实已经启动并跟随进入了世界，问题在于近端没有任何反馈。

### 结果

| 验证项 | 结果 |
|---|---|
| 编译 | `./gradlew.bat :launcher:test :fabric-bridge:remapJar :neoforge-bridge:jar` 和 `./gradlew.bat release` 成功；只有原有的两个 AP 警告，`GuiMixin` 没有映射警告 |
| 自动化测试 | `:launcher:test` 通过；新增的 UI 逻辑没有自动化测试 |
| 发行包内容 | `build/release/mmlf-neoforge-0.1.0.jar` 含 `SessionStatus`、`GuiMixin`、`META-INF/mmlf/mmlf-manager.jar` |
| 真实 Minecraft 启动 / 画面 | **未执行**：面板外观、错误界面、Fabric 正式环境下 `Gui.render` 的 Mixin 都还没有在真实启动器中验证 |

### 下一步

- 把新 JAR 放入 PCL 两个实例的 `mods` 后实测：正常启动（面板走完各阶段）、删掉远端 MMLF JAR（应弹错误界面）、在世界中杀掉远端进程（应提示断开或失败）。
- 远端 JVM 加载阶段的进度目前是按时间估算的，不是真实进度。

## 7. 追加：远端卡在加载世界（进行中：修复未经真实启动器验证）

### 发现了什么问题

用户反馈主端进入世界后，面板一直显示"正在加载世界"，等了 100 秒以上。日志 `…\mmlf\sessions\session-14980421177981163183\fabric.log`：

- 09:45:57 远端收到存档开始打开（`world open {"save":"新的世界"}`）；09:46:00 收到主端语言 `language_follow {"from":"en_us","to":"zh_cn"}`，于是 `reloadResourcePacks()`。
- 之后再也没有出现 `Created: …atlas`，也就是这次资源重载一直没有完成。服务端打印了 `Time elapsed: 6458 ms` 和 `Changing view distance`，但一直没有出现 `logged in with entity id` / `joined the game`。开发运行的日志在 `Time elapsed` 之后会马上出现这两行。
- 原因：`Minecraft.doWorldLoad` 的等待循环条件是 `!isReady() || overlay != null`，而资源重载的 `LoadingOverlay` 在世界加载期间一直没有结束，所以客户端始终没有连上自己的内置服务器，也就从来没有发出 `mmlf:world_ready`。主端的 `neoforge.jsonl` 里确实没有收到 `runtime_world_ready`。09:35 那次会话也是同样的现象。
- 之所以和世界加载撞在一起，是因为主端只在进入世界后才发送 `mmlf:language`，它和 `mmlf:world` 几乎同时到达远端。开发环境两端语言相同、不会触发重载，所以一直没有暴露。

### 如何解决

- 主端在菜单里也发送 `mmlf:language`（抽成 `BridgeCore.sendLanguage`），这样远端在标题界面就先完成语言切换。
- 远端只在标题界面或已经进入世界（且不在 `ReceivingLevelScreen`）时才重载资源；其他时候把请求记为 `pendingLanguage`，之后在 tick 中重试。`WorldFollow.follow` 在 `BridgeCore.languageBusy()` 为真时不打开世界。
- 双端并行加载：`WorldFollow.announce` 改为只要内置服务器存在就广播，不再等主端玩家进入世界。平地判断改用存档注册表中的 `LevelStem.OVERWORLD`，因为此时 overworld 可能还没创建。
- 主端等待：主端进入世界后，如果远端还在启动或者还没就绪，就打开 `RemoteWaitScreen`（`isPauseScreen()=true`，主端世界暂停），显示进度，远端就绪后自动关闭；提供"直接进入（不等远端）"按钮。每个世界只拦一次。
- 远端加载世界超过 90 秒时，面板变黄，提示"远端可能卡住了"，并给出远端日志路径。

### 结果

| 验证项 | 结果 |
|---|---|
| 编译 | `./gradlew.bat :launcher:test release` 成功，产物生成于 09:52 |
| 自动化测试 | `:launcher:test` 通过；本节改动没有自动化测试 |
| 真实 Minecraft 启动 / 画面 | **未执行**：以上原因是根据日志和 1.21.1 源码（`Minecraft.doWorldLoad`、`LoadingOverlay`）推断的，修复效果需要在 PCL 中实测 |
