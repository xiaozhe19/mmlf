# 区块加载跟随近端（拆分架构步骤 0 / 步骤 6 前半）——进行中，受阻

日期：2026-10-29。依据：`docs/ai/split-runtime-manual.md` §5.1、§5.4、§9 步骤 0 与 6。环境同 `docs/ai/result/split-runtime-experiments/`（Fabric 近端 + NeoForge 远端）。

状态：代码已编译；真实 Minecraft 运行 6 次（F0、F1、F1b、F1c、F1d 及之前的 G1/G2 对照），**边界自测仍未通过**，原因查到一半；最后一次诊断运行（F1d）在边界阶段前被 Claude Code 因系统内存不足中止，未重跑。

## 1. 实现了什么

| 改动 | 默认 | 位置 |
|---|---|---|
| 边界发布（信号、邻居镜像、对象、容器）一律在**服务端 tick 末尾**执行，不再由客户端 tick 调度 | **默认生效** | `TickSync.endOfTick`、`ServerTickMixin`（注释已更新）、`BridgeCore.tick` |
| `follow` 开关：远端把"自己对象所在区块"作为关注集合（`mmlf:interest`）发给近端；近端每个服务端 tick 末尾把这些区块在近端的状态（0 未加载 / 1 已加载 / 2 方块 tick / 3 实体 tick）的变化发回（`mmlf:chunks`）；远端按状态持有 `mmlf_follow` 区块票据（距离 = 状态 − 1）；状态为 0 的区块立即"休眠"——其中对象撤出边界、不再发布，不等区块真正卸载；恢复后重新索引 | 开关 | `ChunkFollow.java`（新增）、`Boundary.java`（`dormant`、`awaken`、扫描与发布时检查休眠）、`RedstoneBridge.java`（调用与重置）、`BridgeCore.java`（消息分发、指标） |
| `follow` 下远端玩家不产生区块票据（与"旁观者不生成区块"相同的原版入口），已加载区块仍发送给远端客户端 | 开关 | `mixin/ChunkMapMixin.java`（`ChunkMap.skipPlayer`） |
| 边界自测 `phase=unloaded` 改为检查不变量：等**近端自己**的场景区块（4 个）票据等级降到未加载，然后远端对象必须在 40 tick 内全部撤出；最多等 1500 tick。另加诊断输出 `MMLF BOUNDARY DIAG` | 自测 | `CollabSelfTest.java` |

## 2. 结果

运行方式同 `split-runtime-experiments`（`run-demo.ps1 -SelfTest -Primary fabric`，INFO 日志）。

| 运行 | 开关 | 会话 | 结果 |
|---|---|---|---|
| F0 | 无（含"tick 末尾发布"改动） | `session-4892474386067436347` | 20 项通过；`BOUNDARY unloaded objects=14` 失败（旧 15 秒检查） |
| F1 | `follow` | `session-4410214741152075190` | 近端没收到关注集合（bug，见 §3.2）；REDSTONE on/reloaded、BOUNDARY unloaded 失败 |
| F1b | `follow`（修复 §3.2） | `session-17568158835199806183` | 20 项通过；BOUNDARY unloaded 失败（旧 15 秒检查） |
| F1c | `follow` + 新边界自测 | `session-3239305346053998796` | `reason=primary_never_unloaded`；之后 REDSTONE/NEIGHBOR reloaded、reloaded_off 失败 |
| F1d | 同 F1c + 诊断 | `session-13905259711378426929` | REDSTONE on/off 通过；**在边界阶段前被中止（系统内存不足）** |

`follow` 的资源效果（F1b，整局平均，对照 C0/F0）：

| | C0 | F1b |
|---|---|---|
| 远端加载区块 | 2452 | 784–841 |
| 远端 RSS MB | 1716 | 1533 |
| 远端服务端 MSPT | 3.4 | 1.2 |

远端剩余的约 800 个区块来自原版的票据等级梯度：一张实体 tick 票据会以较低状态带出周围约 11–13 圈区块（4 个对象区块 → 约 29×29）。这是原版机制的下限，每个"对象簇"约 600–800 个区块，虚空世界中这些区块几乎是空的。

## 3. 发现的问题

### 3.1 旧边界自测靠时机通过
旧的 `phase=unloaded` 在传送走 15 秒后检查远端对象是否清零。实测远端（也可能是近端）区块卸载要先存盘，常常超过 15 秒（F0：远端在检查后约 7 秒才回落）。因此 C0/G1 通过、G2/F0 失败都是时机问题，旧测试**没有检查近端自己是否已卸载**。

### 3.2 近端在服务端创建前收到关注集合被丢弃（已修复）
远端世界就绪早于近端创建世界，`ChunkFollow.receive` 在 `server==null` 时直接返回。修复：近端收到关注集合直接写入并发集合（F1b 验证：远端持有 4 张跟随票据）。

### 3.3 近端场景区块不卸载（未解决，进行中）
F1c 中近端传送到 x=1000 后，近端加载区块从 2601 变为 3177 并保持 90 秒，回到原点后变为 5202，即**近端停止卸载任何区块**；而 F0/F1/F1b 中近端约 10 秒内回落到 2601。F1c 与 F1b 的代码差别只有新边界自测（每个客户端 tick 用 `server.execute` 查询 4 个区块的 `hasChunk`，并在该阶段停住自测时间线）。`hasChunk` 是只读查询，原因尚未确定。可能的方向：
- 新自测本身（停在该阶段时的任务投递）影响了 `MinecraftServer.haveTime()`，而原版卸载队列只在 `haveTime()` 为真时处理（`ChunkMap.processUnloads`）；
- 或者近端存在把场景区块保持在 FULL 等级的票据（数字上 3177−2601=576=24²，与 4 个区块各一张 33 级票据的范围相符）。

F1d 加了打印近端场景区块票据等级/状态的诊断，但运行在到达该阶段前被中止。F1c 中随后的红石/邻居失败是否与此相关，也未确定。

## 4. 尚未完成与下一步

- 重跑 F1d 拿到诊断（需要内存充足：建议关闭 VS Code 的 Java 语言服务器等占内存的程序，或用 `CLAUDE_CODE_DISABLE_BG_SHELL_PRESSURE_REAP=1` 启动 Claude Code）。
- 同时跑一次不带 `follow` 的新边界自测（F0b），区分问题来自 `follow` 还是自测。
- 解决 §3.3 后：`follow` 在不同视距、`thin_world`、`lockstep` 下重复运行自测，验收步骤 0。
- 尚未做：掉落物交给近端（步骤 6 后半）、远端 START 出生点票据的处理。
- "边界发布移到服务端 tick 末尾"已默认生效，F0 中除旧边界检查外 20 项通过，但只跑了 1 次。
