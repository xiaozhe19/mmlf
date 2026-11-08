# 瘦 Runtime 可行性实验报告（进行中：重跑被中断，R4b/R6 未运行，R5b 只完成一半）

日期：2026-10-29。Windows 11、RTX 3060 Laptop GPU、Temurin JDK 21；Minecraft 1.21.1，Fabric Loader 0.16.10，NeoForge 21.1.x。第三方 Mod：Farmer's Delight 1.3.4（NeoForge）、Tom's Simple Storage 2.4.2（Fabric）、Macaw's Doors / Trapdoors 1.1.5。

目的：判断"MMLF 统一协调层 + 两个加载器的瘦 Runtime"是否可以开始构建。三个实验分别回答：

1. 后台能否不要自己的原版世界（虚空世界 + 邻居镜像）？
2. 后台能否不再每帧全量渲染（空闲降帧、降分辨率）？
3. 能否由主端驱动后台 tick，使跨端时序固定？

**所有实验代码都是可选开关，默认关闭**（`MMLF_EXPERIMENTS` 环境变量或 `-Dmmlf.experiments`），不改变现有行为；关闭全部开关的对照运行 C0 全部自测通过。

## 1. 实现了什么

| 开关 | 实现 | 位置 |
|---|---|---|
| `thin_world` | 后台创建单层空气的平地世界（无地形、无结构、无装饰），演示场景中后台不再铺原版地板，只放自己拥有的方块；原版邻居完全靠主端的邻居镜像提供 | `VoidWorld.java`、`DemoScene.java` |
| `on_demand` | 后台在主端相机 250 ms 未变化、且自己的对象 250 ms 未变化时，帧率上限从 120 降到 10；托管 GUI 或挖掘时保持全速 | `BridgeCore.backgroundFps()`、`RenderSystemMixin` |
| `render_scale` | 后台窗口按 `mmlf.renderScale`（默认 0.5）缩小，世界层按 UV 采样合成，不改合成器 | `BridgeCore.matchPrimaryWindow` |
| `lockstep` | 主端每个服务端 tick 结束时先发布边界（信号、邻居），再发 `mmlf:tick` 许可；后台服务端用许可代替自己的 50 ms 计时，等待期间照常处理任务和区块；边界发布从客户端 tick 移到服务端 tick 末尾；250 ms 无许可时退回自己计时 | `TickSync.java`、`mixin/ServerTickMixin.java` |
| `probe` | 主端在现有协作自测之后运行：静止 30 s、摆头 30 s（±60°，周期 4 s）采集渲染指标；拉杆 40 次测跨端红石往返（主端 tick 数和 ms）；后台在主端篝火上放一个 Farmer's Delight 厨锅，检查是否受热 | `ExperimentProbe.java` |

指标：各端每 10 s 的 `metrics/runtime` 新增 `serverMspt`、`loadedChunks`、`experiments`，以及 lockstep/on_demand 的计数；GPU 占用由运行脚本每秒调用 `nvidia-smi` 采样，写入会话目录 `gpu.csv`。

## 2. 实验结果

所有运行均为真实 Minecraft，Fabric 主端 + NeoForge 后台（R6 除外），会话自动执行全部现有自测后再执行 probe。"自测"栏只列失败项。

| 运行 | 开关 | 会话 | 自测失败项 |
|---|---|---|---|
| B0 | 改动前代码（无实验代码） | session-17751200903728680817 | 无 |
| C0 | 当前代码，全部关闭 | session-1693592014902812274 | 无 |
| R1 | probe | session-17443511887833607802 | BOUNDARY unloaded（probe 自身导致，见 §3.1） |
| R1b | probe | session-7479547140553343379 | BOUNDARY unloaded；区块重载后 REDSTONE 比较器/mod_to_vanilla；latency 0/40（同上） |
| R2 | probe,thin_world | session-6345649096817422275 | SURVIVAL 掉落物；BOUNDARY unloaded；REDSTONE off/reloaded_off；latency 0/40 |
| R3 | probe,thin_world,on_demand | session-17536033951137745632 | SURVIVAL 掉落物；BOUNDARY unloaded |
| R5 | probe,thin_world,lockstep | session-12577470597832111107 | 后台玩家掉出虚空死亡（§3.3），GUI 两项、SURVIVAL、BOUNDARY；资源数据作废 |
| R4 | probe,thin_world,on_demand,render_scale | session-15343224111559413152 | 同 R5（后台玩家死亡），另 REDSTONE 两项；资源数据作废 |
| R5b | probe,thin_world,lockstep（修复 §3.3 后） | session-7677503348721548495 | 已完成的自测全部通过（含 GUI 三项、SURVIVAL 掉落物、REDSTONE on/off、CONTAINER）；BOUNDARY 与 probe 未执行：系统内存不足，运行被中断 |
| R4b / R6 | render_scale 重跑；NeoForge 主端 | 未运行 | 同上，未启动 |

### 2.1 实验一：后台不要原版世界

渲染与资源（静止阶段 2 个 10 s 窗口的平均；B 为后台）：

| 指标 | R1b 基线 | R2 thin_world |
|---|---|---|
| 后台服务端 MSPT | 2.22 ms | 1.17 ms |
| 后台已加载区块 | 2601 | 2601 |
| 后台 RSS | 2061 MB | 2305 MB |
| 后台堆 | 852 MB | 912 MB |
| 后台 CPU（单核%） | 77 | 72 |
| GPU 占用 | 40.6% | 38.2% |
| 后台启动到世界就绪 | 11.5 s | 13.9 s |

功能：

- 远端 GUI（测试 GUI、容器、Farmer's Delight 厨锅）、跨端容器（漏斗送入/取出）、跨端红石、邻居镜像在虚空世界中均与基线一致通过（R2、R3）。
- **第三方 Mod 读取主端原版邻居**：后台的 Farmer's Delight 厨锅放在主端的篝火上，`isHeated()=true`，后台读到的下方方块为 `minecraft:campfire[...,lit=true,...]`；放在自己灶台上的厨锅同样受热。所有带 probe 的运行（R1–R5）结果一致。
- **失败：生存模式破坏后台方块后掉落物丢失**（`SURVIVAL SELFTEST broken demo_drop_gained=0`）。两次虚空世界运行都失败，有地形的两次基线都通过。原因：掉落物是后台的实体，在后台世界里下面没有地面，掉进虚空。

### 2.2 实验二：后台不再每帧全量渲染

| 指标 | R2 thin_world | R3 + on_demand |
|---|---|---|
| 静止：后台帧率 | 133 fps | 21.5 fps（443 帧中 434 帧为空闲帧） |
| 静止：后台 CPU（单核%） | 72 | 26 |
| 静止：GPU 占用 | 38.2% | 30.6% |
| 静止：远端帧龄 P50 / P95 | 11 / 15 ms | 60 / 110 ms |
| 摆头：后台帧率 | 132 fps | 131 fps |
| 摆头：角度误差 P95 | 4.06° | 6.31° |
| 摆头：远端帧龄 P50 | 11.0 ms | 11.0 ms |

- 静止时帧龄变大不影响画面（相机不动，重投影为恒等）；代价在开始转动的第一帧：从 10 fps 恢复需要最多 100 ms，角度误差 P95 从 4.1° 增至 6.3°，快速转头时的裁切会更明显。
- **发现的耦合**：后台的边界发布（红石信号、对象状态）由客户端 tick 通过 `server.execute` 调度。后台降帧后，同一帧内会连续处理多个客户端 tick，发布变得成批；R3 中跨端红石往返出现 3 tick（约 130 ms）的情况。降帧的前提是先把边界发布移到服务端 tick（实验三已这样做）。
- `render_scale`：R4 的后台玩家死亡，GUI 与资源数据作废；渲染数据仍可参考：静止阶段（同时开 on_demand）后台 CPU 20%、GPU 27.0%，摆头阶段后台 131→136 fps、CPU 72%→54%、GPU 33.7%（R3 为 33.7%），角度误差 P95 4.65°。半分辨率下的画质（锯齿、边缘）没有截图对比，手部层（`VisualLayers` 要求与主端同尺寸）和远端 GUI 的清晰度未验证。R4b 未运行。

### 2.3 实验三：主端驱动后台 tick

跨端红石往返（主端拉杆 → 主端红石线 → 后台 Mod 灯 → 对象状态回到主端；单位为主端 tick）：

| 运行 | 往返 tick 分布 | 往返 ms |
|---|---|---|
| R1（独立 tick） | 1×1、2×39 | 50–69，P50 64 |
| R3（独立 tick + 降帧） | 1×31、2×6、3×3 | 29–135，P50 35 |
| R5（lockstep） | 2×32、3×8 | 54–151，P50 99 |

- 独立 tick 下，往返 tick 数取决于两边 tick 的相位，**每个会话不同**（R1 主要为 2，R3 主要为 1）。
- lockstep 下，后台 tick 严格跟随主端：许可间隔 P50 50.0 ms、P99 约 52 ms，等待许可 P50 49.6 ms，整个运行中退回自己计时 0 次、跳过 0 次，启动阶段追赶 25 次后稳定。
- lockstep 下，后台报告的"灯在哪个主端 tick 变化"为：40 次中 32 次 +1 tick、8 次 +2 tick；**回程永远是 +1 tick**（往返 = 单程 + 1，40/40）。即：时序比独立 tick 更可控，但单程仍有 20% 晚一个 tick，原因未查明（§3.4）。
- lockstep 的往返 ms 更长（P50 99 ms 对 35–64 ms），因为主端 tick N 的输出要等到后台 tick N+1 才生效、再回到主端 tick N+2，这是设计中"固定 1 tick 延迟"本身的代价。

## 3. 发现的问题、原因与处理

### 3.1 probe 让后台场景区块无法卸载（实验代码问题，已修，待验证）
- 现象：带 probe 的运行中 `BOUNDARY SELFTEST phase=unloaded` 全部失败；关闭实验的 C0 通过。
- 后台日志显示：主端传送离开 15 s 后，后台仍加载 3130 个区块（新区域 2601 + 旧区域残留），10 s 后才降到 2601。
- 原因（推断）：probe 在后台每个服务端 tick 读取场景中的灯（`ServerLevel.getBlockState`）。服务端线程上的区块读取会加票据，使区块无法卸载。第一次修复（先检查 `isLoaded`）后 R1b 仍失败；第二次改为 `getChunkNow` 直接读已加载区块，效果待 R5b 验证。
- R1b、R2 中区块重载后的红石卡住（比较器、mod_to_vanilla、latency 0/40）只出现在带 probe 的运行中，C0 无此问题。推断与上一条相同：一端的场景区块卸载、另一端没卸载，边界状态不对称。这说明**边界协议对"两端区块加载状态不一致"没有恢复能力**，在瘦 Runtime 下（后台视距不同）会成为真实问题，需要单独处理。

### 3.2 虚空世界中的实体（掉落物）
见 §2.1。瘦 Runtime 不能只去掉地形；后台产生的实体（至少是掉落物）需要交给主端，或者为后台提供碰撞镜像。

### 3.3 虚空世界中后台玩家掉出世界死亡
- 现象：R4、R5 后台日志 `Dev fell out of the world`，之后后台始终处于 `DeathScreen`；托管的容器 GUI 立即被系统界面顶掉（`hosted_closed reason=system_screen`），GUI 自测 `not_opened`；死亡玩家不加载区块，因此这两次的区块数（576）、MSPT（0.2 ms）不可比较。
- 原因：后台玩家在世界就绪后、收到主端第一个姿态前没有立足点，自由下落出界。R2、R3 未发生，是时序竞争。
- 处理：`thin_world` 下后台玩家设为无重力，低于 y=0 时拉回场景。R5b 中后台玩家未死亡，GUI 三项、SURVIVAL（含掉落物拾取）通过。掉落物这次能拾取，与 §3.2 的结论不一致，只有一次运行，不能说明问题已解决。

### 3.4 lockstep 单程偶尔多一个 tick（未解决）
已排除：灯方块的更新是立即的（`neighborChanged` 中直接 `setBlock`）；`RuntimeClient` 在网络线程按序交付，信号消息先于许可发送；后台在许可到达后会先执行队列中的任务再开始 tick。尚未排除：主端拉杆任务相对服务端 tick 的执行时机、主端 tick 末尾边界测量与红石线更新的先后。需要在两端记录每次信号发布/应用的 tick 号后再判断。

### 3.5 后台已加载区块数与世界内容无关
虚空世界与平地世界都加载 2601 个区块（51×51，对应视距）。区块数由后台玩家的视距决定，不由地形决定。瘦 Runtime 应降低后台视距，或只为自己拥有对象的区块加票据。

## 4. 验证与命令

- 编译：`./gradlew.bat :fabric-bridge:compileJava :neoforge-bridge:compileJava` 通过（仅既有 Mixin 映射警告）。未运行 `./gradlew.bat build` 的单元测试（本次改动不涉及 `common`/`coordinator`）。
- 真实 Minecraft：每次运行由 `run-demo.ps1 -SelfTest -LogLevel INFO`（`MMLF_EXPERIMENTS` 设为对应开关）启动，自动执行全部自测与 probe；结果来自会话目录的 `clients.log`、`logs/*.jsonl`、`gpu.csv`。
- 未执行：用户手动游玩验收；GPU 数据只有全卡占用率，无按进程统计；每种配置只运行一次，没有重复测量方差。

## 5. 结论（初步，待补重跑结果）

**可以开始构建统一协调层；"瘦 Runtime"可以作为方向，但不能一步到位地"去掉后台的原版世界"。**

已有证据支持的：
1. 协调层的现有协议（可靠消息、对象归属、边界信号、邻居镜像、容器事务）在虚空世界的后台中照常工作；真实第三方 Mod（Farmer's Delight 厨锅）能通过邻居镜像读到主端的原版篝火。
2. 后台空闲降帧收益明确：静止时后台 CPU 77%→26%、GPU 41%→31%，但开始转头时角度误差增大。
3. 主端驱动 tick 可以稳定运行：许可间隔抖动约 2 ms，无回退、无跳 tick，后台 MSPT 不受影响；回程延迟固定为 1 tick。

不支持"直接去掉原版世界"的：
1. 后台的实体（掉落物）和后台玩家本身都需要地面；虚空世界会让掉落物丢失、玩家死亡，并连带让 GUI 失效。
2. 去掉地形几乎不省内存，区块数由视距决定；真正的瘦身杠杆是降低后台视距、只加载自己对象所在的区块，这还没试。
3. 边界状态在"两端区块加载不一致"时会卡住（§3.1），瘦 Runtime 的视距不同于主端时必然遇到。
4. lockstep 单程仍有 20% 晚一个 tick，原因未查明；时序还不是完全确定的。

建议的下一步（按依赖顺序）：
1. 先修 §3.1：边界协议在一端卸载、另一端仍加载时的恢复（重发、过期清理）。这对现有架构同样有价值。
2. 把边界发布从客户端 tick 移到服务端 tick（lockstep 已证明可行），与降帧解耦。
3. 后台实体交给主端：至少掉落物在后台生成后转为主端的物品实体。
4. 后台视距只覆盖自己对象所在区块，再测内存与 CPU。
5. 查清 lockstep 单程多 1 tick 的原因后，再决定是否默认启用主端驱动 tick。
6. 降帧与降分辨率放在最后，并补画质截图和手部层、GUI 的适配。

未完成：R5b 的边界与 probe 部分、R4b（降分辨率重跑）、R6（NeoForge 主端）因系统内存不足被中断，未得到结果；所有配置各只跑一次。
