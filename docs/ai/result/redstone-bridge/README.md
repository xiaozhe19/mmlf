# 跨端 Mod 信号与事件驱动边界 实现报告

日期：2026-10-29。对应讨论中定下的"切片 1"：原版红石全部留在主端原生运行，另一端的 Mod 方块视为信号端点。

## 1. 实现了什么

### 事件驱动的边界索引（`Boundary`）
- 替换原来每 2 tick 扫描玩家周围 17×11×17 的 `publishNearby`。改为服务端 `LevelChunk.setBlockState` 与 `LevelChunk.setLoaded` 的 Mixin（`LevelChunkMixin`）：方块变化和区块加载/卸载时只做记录，下一个服务端 tick 再发布。不在区块升级途中查询世界，避免等待正在加载的区块。
- 区块加载时只逐格遍历调色板中可能含非原版方块的 section；本端放置的原版方块（`ownedVanilla`）按位置单独处理。
- 对象 ID 加入放置代数：`Messages.objectId(owner, dim, x, y, z, generation)`，`ObjectState.generation`。同一位置拆了重放是新对象，旧的射线命中和交互引用失效（`WorldIndex` 校验）。
- 主端拥有全部原版方块，`ownVanilla` 只在后台生效。
- 远端对象位置表（`Boundary.remote`）供信号、邻居状态和容器使用。

### 信号端点（`RedstoneBridge`）
- 输入：每端在自己的世界里原生测量"另一端对象相邻位置"给出的信号（`level.getSignal` / `getDirectSignal`，6 个方向）。
- 输出：每端测量自己的信号源对象和可被比较器读取的对象发出的信号。只取方块自身的输出（`state.getSignal` 等），不取经方块传导的电，否则主端红石线指向远端实心方块时会读回自己的电而无法熄灭。比较器模拟输出（`getAnalogOutputSignal`）一并发布。
- 只发变化（`SignalTable.Outbox`），每 tick 一批，走任务 A 的可靠流（消息类型 `signals`，Coordinator 要求必须带可靠头）。
- 接收端：`BlockStateSignalMixin` 在 `BlockStateBase.getSignal/getDirectSignal` 返回值处取"本地值与发布值"中较大者。`ComparatorMixin` 在比较器读取背后方块时，用发布的模拟输出代替。接收后对变化位置及其邻居调用 `updateNeighborsAt`，由原生逻辑反应。
- 对端离开、断线时清除其信号并通知邻居；对端重新加入时全量重发。

### 测试方块与场景
- 两个测试 Mod 新增 `CollabBlocks`（两端代码相同，只依赖 Minecraft API）：`signal_block`（点亮时各方向 15，比较器读 9）、`lamp_block`（有邻居信号即点亮）。
- `DemoScene` 支持 `runtime: primary/background` 与方块名中的 `{primary}`/`{background}`，同一场景在两个方向都成立。场景 z=-6 一排：Fabric 信号 → NeoForge 灯、NeoForge 信号 → Fabric 灯、主端拉杆 → 红石线 → 远端灯、远端信号 → 主端红石线 → 原版红石灯；z=7：远端信号方块 → 主端比较器。
- `CollabSelfTest`：GUI 自测结束后运行，开、关各检查一遍；之后传送离开 40 格、离开 1000 格（等区块卸载）、再回来，检查对象索引与信号恢复。

## 2. 发现了什么问题

| 问题 | 复现 / 现象 | 原因 |
|---|---|---|
| 首次真实运行中，"开"阶段所有跨端结果仍为关，"关"阶段反而为开 | session-15859295381668855874，Fabric 主端 | NeoForge 后台日志 `Too many chained neighbor updates` 和 `Can't keep up! 2593ms behind`：主端红石线的邻居状态被镜像到后台后，后台的邻居更新调用了这根"镜像红石线"的原生 `neighborChanged`，它在后台世界重算并写入，又读回镜像值，无限更新，服务端卡住约 6 秒，所有消息都晚了一个阶段（详见 `docs/ai/result/neighbor-state/`） |
| 区块卸载检查失败：离开 1000 格 15 秒后仍有 14 个远端对象 | 同上 | 场景在世界出生点，1.21.1 的出生点区块（`spawnChunkRadius`）常驻加载，场景从未卸载，测试本身无效 |
| 讨论中提出的回传锁死 | 设计阶段 | 远端实心方块若导出"传导来的电"，主端指向它的红石线会读回自己的电。已按"只导出方块自身输出"规避 |

## 3. 如何解决
- 镜像邻居改为"只可见、不运行"：`MirroredNeighborMixin` 让镜像位置的 `handleNeighborChanged` 与 `updateShape` 不执行。
- 自测开始时两端都把 `spawnChunkRadius` 设为 0，让卸载、重载检查真实发生。
- 取舍：远端 Mod 方块不传导电（例如拉杆贴在远端实心 Mod 方块上，不会点亮方块另一侧的灯）；红石线不会在视觉上弯向远端信号源（供电本身不受影响）。

## 4. 得到了什么结果

### 编译与自动化测试（已通过）
`./gradlew.bat build`：两端编译通过；common 53 项、coordinator 4 项、launcher 6 项，0 失败。新增：`SignalTableTest`（5 项：只发变化、离开时静默、多端取最大、模拟输出、范围校验）、`WorldIndexTest.aBlockPlacedAgainIsANewObjectAndOldHitsAreStale`。

### 真实 Minecraft 自测（已执行，两个方向，修复后）
命令：`./scripts/run-demo.ps1 -SelfTest -Primary fabric|neoforge -LogLevel DEBUG`。Fabric 主端 session-1745092563855220521、NeoForge 主端 session-1583707565630392519，均输出：
```
MMLF REDSTONE SELFTEST phase=on mod_to_mod=[neoforge@-5=true, fabric@-2=true] vanilla_to_mod=[background@2=true] mod_to_vanilla=true comparator=9 passed=true
MMLF REDSTONE SELFTEST phase=off ... comparator=0 passed=true
MMLF BOUNDARY SELFTEST phase=away objects=14 expected=14 passed=true      （NeoForge 主端为 16）
MMLF BOUNDARY SELFTEST phase=unloaded objects=0 passed=true
MMLF BOUNDARY SELFTEST phase=reloaded objects=14 expected=14 passed=true
MMLF REDSTONE SELFTEST phase=reloaded ... passed=true / phase=reloaded_off ... passed=true
```
原有自测（相机、碰撞、三项 GUI、生存放置与挖掘）两个方向均 `passed=true`。两次运行的 `latest.log` 都没有 `chained neighbor` 和 `Can't keep up`，结构化日志没有 `reliable_*` 错误。

### 实测延迟（样本少，不构成性能结论）
从发送端发布到接收端服务端线程应用：Fabric→NeoForge 12 个样本，P50 2.0 ms、最大 8.5 ms；NeoForge→Fabric 4 个样本，P50 6.5 ms、最大 9.0 ms。另有发送端最多 1 tick 的测量间隔。

### 未执行的验证
- 用户手动画面验收未执行。
- 真实第三方 Mod 的红石行为（Create、Mekanism 等）未测试。
- 一主两后台、维度切换未测试。
- 未做改动前后的 TPS 对照。

## 5. 尚未完成、限制与下一步
- 跨端信号在 0 到 1 tick 之间生效，取决于两端 tick 相位；对 1 tick 脉冲等时序敏感电路可能不同。改进方向见 `docs/ai/TODO.md`（主端驱动后台 tick）。
- 远端 Mod 方块不传导电；红石线不视觉连接远端方块。
- 不通过标准信号接口读取的 Mod：后台一侧由邻居状态镜像部分覆盖；主端一侧，远端对象位置仍是空气。
- 性能：`BlockStateBase.getSignal` 每次调用多一次 volatile 读；每 tick 测量本端信号源对象与远端对象相邻位置。大量对象时的开销未测量。
