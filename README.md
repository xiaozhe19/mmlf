# Multi-ModLoader Fusion

**Fabric MC x NeoForge MC**

MMLF 希望兼容NeoForge与Fabric，但想要绕开两个ModLoader复杂的相互兼容部分。

每个 ModLoader 运行在独立的 Minecraft 进程里，加载自己的原生 Mod；Coordinator 在进程之间同步相机、玩家、方块状态和交互；主窗口把其他进程渲染出的真实画面（颜色 + 深度）按正确的前后遮挡合成进来。

> Native Execution, Shared Logical World, Unified Interaction, Composited Rendering.

当前版本是 **Demo 1**：Minecraft 1.21.1，Fabric + NeoForge 双端，开发环境运行。

## 能做什么

- 主窗口里同时显示 Fabric 和 NeoForge 的原生方块，以及第三方 Mod 内容（Macaw's Doors / Macaw's Trapdoors），遮挡关系正确，原版世界不会重复显示。
- 点击、破坏、中键选取另一端的方块，由**方块所属的那一端原生执行**：有状态变化、挥手动作、原生声音和破坏粒子。
- 两端测试 Mod 互相发送事件（反向交互）。
- 玩家会被另一端的方块挡住（碰撞）。
- 另一端物品的手持模型和快捷栏图标，都是由它所属的那一端原生渲染的。
- **哪一端都能当主窗口**：启动哪个客户端，哪个就是主窗口，另一端在后台自动拉起、隐藏运行，关闭主窗口后几秒内自动退出。

没有复制另一端的资源，没有重新注册另一端的方块，也没有重写另一端的渲染逻辑。画面全部来自各自真实运行的 Minecraft。

## 环境要求

- Windows 10/11（脚本为 PowerShell；Java 代码本身不依赖平台，其他系统未测试）
- 64 位 JDK 21
- 支持 OpenGL 3.2 的显卡。两个客户端同时渲染，显卡越强越好；在笔记本 RTX 3060 上测试通过
- 联网：首次构建会下载官方 Minecraft 开发依赖

## 快速开始

```powershell
# 1. 下载演示用第三方 Mod（固定版本，来自 Modrinth，校验 SHA-512）
./scripts/fetch-demo-mods.ps1

# 2. 构建并运行自动化测试
./gradlew.bat build

# 3a. 用脚本启动，-Primary 选择主窗口（默认 fabric）
./scripts/run-demo.ps1 -Primary neoforge

# 3b. 或者直接启动任意一端：它成为主窗口，并在后台拉起另一端
./gradlew.bat :neoforge-bridge:runClient
./gradlew.bat :fabric-bridge:runClient
```

启动后，主窗口会先显示"等待其他 Runtime"，后台世界加载好后（约 1–3 分钟）自动进入同一个创造模式平地世界。不想等可以点"直接进入"。

### 演示场景

| 物体 | 所属 |
|---|---|
| 蓝色测试方块（x=-1） | Fabric 测试 Mod |
| 橙色测试方块（x=1） | NeoForge 测试 Mod |
| 橡木四格门（x=-3） | Macaw's Doors（Fabric） |
| 橡木四格活板门（x=3） | Macaw's Trapdoors（NeoForge） |

场景在 `config/demo-scene.json` 中配置。可以试试：空手右键测试方块（另一种颜色的方块会跟着联动）、开关门和活板门、走进远端方块、中键选取远端物品后放到地上、左键破坏远端方块。

### 自动自测

```powershell
./scripts/run-demo.ps1 -SelfTest
# 或者客户端托管模式
$env:MMLF_SELF_TEST='true'; ./gradlew.bat :neoforge-bridge:runClient
```

主窗口会自动转向、交互、截图，并输出 `MMLF ... SELFTEST ... passed=true` 等结果。测完记得 `Remove-Item Env:MMLF_SELF_TEST`。

## 工作原理

```
          主端 Runtime（如 Fabric）                        后台 Runtime（如 NeoForge）
   ┌──────────────────────────────┐                ┌──────────────────────────────┐
   │ 原生 Mod + MMLF Bridge       │   WebSocket    │ 原生 Mod + MMLF Bridge       │
   │  · 玩家输入、相机姿态  ──────┼──► Coordinator ─┼──► 跟随主端相机与玩家       │
   │  · 射线仲裁、交互路由  ◄─────┼── （对象归属） ◄┼──  方块状态、碰撞形状、事件 │
   │                              │                │                              │
   │  GPU 合成 ◄──── 共享帧文件（颜色+深度+实际渲染姿态）◄──── PBO 异步读回       │
   └──────────────────────────────┘                └──────────────────────────────┘
```

- **通信**：Loopback WebSocket，会话令牌握手，心跳和断线检测。每个方块对象有唯一 ID 和所属 Runtime，只有所属端能修改它的权威状态。
- **帧传输**：后台在世界画完、画手之前，把颜色和深度读进 PBO，不阻塞渲染；GPU 栅栏通过后，用 seqlock 写进三槽共享帧文件。每帧附带后台**实际**渲染时的相机和投影矩阵。
- **合成**：主端在世界画完后画一个全屏三角形，片元着色器写出远端表面在当前相机下的深度，由硬件深度测试和主端自己的深度缓冲比较遮挡。远端帧的姿态和当前相机不一致时，沿视线对数步进加二分，做重投影；遇到被揭开的遮挡区域就丢弃该像素，不产生拖影。
- **去重**：每一端只渲染自己拥有的方块（在区块构建时过滤）；后台不画云和方块选择框，原版世界只由主端画。
- **延迟补偿**：后台以 1.25 倍视野渲染，按主端的转动速度预测相机朝向，帧率上限 120。
- **交互**：主端点击时，向所有 Runtime 发射线查询，Coordinator 选出最近的命中，交给那个方块所属的一端，在它的服务端原生执行。
- **会话**：`SessionHost` 创建会话目录和 Coordinator，按元数据把 `mmlf/mods/` 里的 JAR 分到对应加载器，再启动各 Runtime。后台窗口从创建起就隐藏；主端退出时广播会话结束，后台还有看门狗检测主端进程。

渲染合成和重投影的思路参考了 [rehan-remade/universal-modder](https://github.com/rehan-remade/universal-modder) 中的 Minecraft × GTA V passthrough 示例（MIT），代码按 Minecraft 1.21.1 的 OpenGL 重新实现。

## 项目结构

```
common/              协议消息、会话、共享帧文件、日志
coordinator/         WebSocket Coordinator（注册、路由、射线仲裁、世界索引）
launcher/            SessionHost（会话、Mod 分拣、启动 Runtime）与独立启动器
bridge-common/       两个加载器共用的 Bridge 代码与 Mixin（编译进各自的 Bridge）
fabric-bridge/       Fabric 入口与构建配置
neoforge-bridge/     NeoForge 入口、NeoForge 专用 Mixin 与构建配置
fabric-test-mod/     Fabric 原生测试 Mod（蓝色方块）
neoforge-test-mod/   NeoForge 原生测试 Mod（橙色方块）
mmlf/mods/           统一 Mod 目录（第三方 JAR 不入库，由脚本下载）
config/              演示场景
scripts/             run-demo.ps1、fetch-demo-mods.ps1
runtime/             每次会话的游戏目录、帧文件与日志（不入库）
```

## 配置项

在 `run-demo.ps1` 中：`-Primary fabric|neoforge`、`-SelfTest`、`-Foreground`、`-LogLevel DEBUG|INFO|WARN|ERROR`。

JVM 系统属性（客户端托管模式下加在 `runClient` 的运行配置里）：

| 属性 | 默认 | 作用 |
|---|---|---|
| `mmlf.autohost` | `true` | 没有会话时，由本客户端托管会话并拉起另一端 |
| `mmlf.compositor` | （GPU） | 设为 `cpu` 时使用旧的 CPU 合成，作为对照 |
| `mmlf.overscan` | `1.25` | 后台视野放宽倍数 |
| `mmlf.poseLeadMs` | `25` | 后台相机预测的提前量（毫秒） |
| `mmlf.backgroundFps` | `120` | 后台帧率上限 |
| `mmlf.selfTest` | `false` | 托管会话时运行自测（也可用环境变量 `MMLF_SELF_TEST=true`） |

## 日志与排错

- 最新会话目录写在 `runtime/latest-session.txt` 里；会话目录下的 `logs/*.jsonl` 是各端的结构化日志，`clients.log` 或 `<runtime>.log` 是客户端完整输出。
- 主端每秒记录 `render.composite/gpu_frames`（直接合成帧数、重投影帧数、远端帧龄），后台记录 `render.capture/frame`（读回耗时），各端定时记录 `metrics/runtime`（CPU、内存、FPS、往返时间）。
- 构建报 `mappings.jar` "另一个程序正在使用此文件"：通常是 IDE 的 Gradle 守护进程占用了 Loom 缓存。关闭 IDE 或执行 `./gradlew.bat --stop` 后重试。

## 已知限制

- **快速转头时，屏幕边缘的远端物体会被裁切**，之后能看到重新出现的过程（未完成）。两个客户端同时渲染时显卡吃紧，后台帧率上不去，远端画面落后约 30–40 ms。
- 远端透明材质（水、玻璃）按不透明处理，主端的半透明表面会挡住后面的远端物体。
- 远端交互目前在服务端执行，长按使用物品、远端 Mod 的 GUI 等还不支持。
- 只能在开发环境里启动（依赖各 Bridge 的 Gradle 运行任务）。
- 不支持：跨端物品栏和物品组件、生存模式物品消耗、实体和完整物理同步、Forge 第三端。
- Macaw's Doors 的门在物品栏里的图标和放置后的门贴图不同，这是 Mod 本身的设计，不是传输错误。

## 路线

1. 缓解快速转头裁切：降低后台分辨率，或在快速转头时按需同步。
2. 单个 Bootstrap Mod JAR 加 Runtime Manager：放进普通 `mods` 文件夹即可使用，自动准备并管理后台 Runtime。
3. 原生按键转发（由所属端的客户端执行点击）和手部、HUD 分层。
4. 增加 Forge 作为第三个 Runtime（协议层已经按多个对等 Runtime 设计）。

## 致谢与说明

- 合成与重投影思路参考 [universal-modder](https://github.com/rehan-remade/universal-modder) 的 Minecraft × GTA V passthrough 示例（MIT License）。
- 演示用第三方 Mod：Sketch Macaw 的 [Macaw's Doors](https://modrinth.com/mod/macaws-doors) 与 [Macaw's Trapdoors](https://modrinth.com/mod/macaws-trapdoors)，由 `scripts/fetch-demo-mods.ps1` 从 Modrinth 下载，本仓库不重新分发。
- Minecraft 属于 Mojang Studios / Microsoft。本项目不包含、也不分发任何 Minecraft 游戏文件。
- 本项目使用 Claude Code 开发。
