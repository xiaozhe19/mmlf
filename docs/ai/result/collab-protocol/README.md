# 协作层任务 A：可靠协议 + 玩家状态统一 实现报告

日期：2026-10-29。来源：`docs/manual/Coordinate_completion.md` 的评估与拆分（§5 消息信封、§10 玩家联邦、§12 事件分类）。本报告只涉及任务 A；任务 B（世界镜像与边界）和任务 C（跨端红石）尚未开始。

## 1. 实现了什么

### 消息分级
运行时之间的消息分成两类：

| 类别 | 语义 | 消息 |
|---|---|---|
| 可靠（`sendReliable`） | 恰好一次、按序；断线期间保留，重连后重发 | 物品栏 grant / update / return / owner_change，`gui` 状态报告，`interaction_result`，`mmlf:picked`、`attack_stop`、`session_end`、`world_ready`、`world_closed`、目录与图标请求，第三方 Mod 经 `BridgeEvents` 发出的事件 |
| 快照 / 请求（`send`） | 丢了就由下一份替换，或由请求方超时发现 | `player`、`object`、物品栏 mirror、`mmlf:world` 公告、`latency_feedback`、`ray_query`/`ray_hit`、`gui_input`、ping |

### `ReliableLink`（`common/.../ReliableLink.java`，纯 Java，可单测）
- 每个进程有一个 incarnation（UUID，随 `Hello` 上报）。一条流 = 发送方 incarnation → 接收方 incarnation，序号从 1 开始。
- 发送方：每个对端一个有界发件箱（默认 1024 条、保留 60 秒，`-Dmmlf.reliableCapacity`、`-Dmmlf.reliableRetentionSeconds`）。消息在收到累计确认 `ack` 前一直保留；对端（重新）注册时整批重发。目标为 `"*"` 时展开为每个已知对端各一条。
- 接收方：按序号去重、重排，只把连续序号交给上层；每条可靠消息回一个累计 `ack`。
- 对端换了 incarnation（进程重启）：发给旧进程的未确认消息直接丢弃并记日志，绝不投递给新进程；新进程收到写给旧 incarnation 的消息也会丢弃。
- 超出界限不静默：发件箱满、过期、对端重启都记 `reliable_*` 日志；每条消息带 `floor`（发送方仍能重发的最小序号），接收方据此发现缺口，记 `reliable_gap` 后继续，不会永远等待。

### 协议与 Coordinator
- `Wire` 新增可选字段 `delivery`（stream / to / seq / floor），协议版本升到 2。
- `Hello` 带 `incarnation`；`Welcome` 带所有在线 Runtime 的 `incarnations`；`runtime_joined` 带 `incarnation`。
- Coordinator 校验：可靠消息的 stream 必须是发送方自己的 incarnation，且只能有一个具体接收方（不能是 `*` 或自己）；新增 `ack`（必须有目标）与 `player`（只允许主端发出）两种路由。
- `RuntimeClient`：在网络线程上处理 welcome / joined / left / ack，在交给 `BridgeCore` 之前完成去重和重排。

### 玩家状态统一（`Messages.PlayerState`）
- 原先四条各自独立的同步路径：`mmlf:held`（物品、格子、着地）、`mmlf:language`（语言、游戏模式）、物品栏 mirror 里的 `selected`、`primaryGrounded`。现在合并为主端发出的一条带版本快照 `player`：选中格、手持物引用、着地、游戏模式、语言。变化时发送，否则每秒一次；菜单界面也会发（无玩家时 selected=-1）。
- 次级端只应用版本更新的快照（`followPlayer`），旧版本丢弃并记 `stale_state`；主端离开或断线后版本归零。
- 选中格现在只有一个来源：`PlayerState` 同时设置次级端客户端和服务端玩家的选中格。`InventoryBridge.applyOnServer` 不再写 `selected`。

### 物品栏接入版本号
- 次级端记录已应用的主端快照版本（mirror 与 grant 共用主端计数器），收到更旧的版本时拒绝并记 `stale_snapshot`，避免被超车的旧镜像覆盖新状态。主端丢失时归零。
- `owner_change`（租约外的捡起、消耗）改为可靠发送。此前若在断线时发出会被静默丢弃，而本地 baseline 已经更新，这个变化就永远丢了（与"烤鸡丢失"同类）。现在会在重连后恰好送达一次，主端按原有增减量规则应用。

## 2. 发现了什么问题

| 问题 | 复现 / 现象 | 原因 |
|---|---|---|
| 可靠性缺失（改动前） | 断线期间 `RuntimeClient.send` 直接丢弃；Coordinator 对 `event`/`object` 只是转发，没有序号和确认 | 协议只有一种 fire-and-forget 语义 |
| 同一事实多个来源（改动前） | 选中格同时由 `mmlf:held` 和物品栏 mirror 写入 | 每个 bug 单独打补丁，各走各的事件 |
| **NeoForge 后台在生存挖掘自测中崩溃**（第一次真实运行，Fabric 主端，session-16898291060706277625） | `IllegalArgumentException: Infinity is not a valid double value`，栈顶为 `MmlfLog.write` ← `BridgeCore.continueMining` | 方块被挖掉的那一 tick，`continueMining` 的采样日志读取该位置（此时已是空气，硬度 0）的 `getDestroyProgress`，结果为 Infinity；Gson 拒绝非有限数，异常抛出游戏 tick。这是旧问题：日志每 250 ms 采样一次，只有采样恰好落在破坏那一 tick 才触发，此前几次运行没有碰上 |

## 3. 如何解决
- 可靠协议与玩家状态：见第 1 节。
- 崩溃：`MmlfLog` 把非有限的 `Double`/`Float` 记为字符串（`"Infinity"`、`"NaN"`），日志永远不会让调用方（可能是游戏 tick）失败。新增 `MmlfLogTest.nonFiniteNumbersAreLoggedAsText`。

取舍：
- 确认丢失时不按定时器重发，只在对端重新注册时重发。底层是 loopback TCP，连接存在时不会丢包或乱序，丢失只发生在断线，而断线一定伴随重新注册。
- `gui_input` 保持快照语义：它有自己的 `GuiInputSequencer`，大量鼠标移动不需要逐条确认。
- Coordinator 主动发出的消息（`ray_request`、`interaction`、`welcome`、`object` 快照）不走可靠流。交互靠 1.5 秒超时报告失败，对象状态在重连时整体重发。

## 4. 得到了什么结果

### 编译与自动化测试（已通过）
- `./gradlew.bat :common:test :coordinator:test -PcoreOnly`：通过。
- `./gradlew.bat build`：Fabric、NeoForge 两端编译通过，全部测试通过。common 47 项（新增 `ReliableLinkTest` 6 项、`MmlfLogTest` 1 项），coordinator 4 项（新增 2 项），launcher 6 项，0 失败。
- 新增测试覆盖：
  - `ReliableLinkTest`：重复和乱序只交付一次且按序；对端离线期间保留，确认后释放，确认丢失后重发被去重；对端重启后不向新进程投递旧消息、新流从 1 开始；发件箱满和过期造成的缺口被报告而不是卡住；广播每个对端各一条流；非可靠消息直接透传。
  - `CoordinatorIntegrationTest.reliableDeliveriesAreCheckedAndThePlayerBelongsToThePrimary`：welcome / joined 携带 incarnation；冒用他人 stream、可靠广播被拒；`ack` 正确路由；`player` 只允许主端。
  - `CoordinatorIntegrationTest.reliableMessagesSurviveAReconnectExactlyOnce`：两个真实 `RuntimeClient` 连接真实 Coordinator，服务端强制断开所有连接，断线期间发出 2 条（含 1 条 `*`），重连后再发 1 条，接收方恰好按序收到 2、3、4，之后 1.5 秒内没有重复。

### 真实 Minecraft 自测（已执行，两个方向）
命令：`./scripts/run-demo.ps1 -SelfTest -Primary fabric|neoforge -LogLevel DEBUG`。

- 第一次 Fabric 主端（session-16898291060706277625）：相机、碰撞、三项 GUI、生存放置通过；生存挖掘时 NeoForge 后台因上述日志问题崩溃。**该次不算通过。**
- 修复后 Fabric 主端（session-13443961991809094824）和 NeoForge 主端（session-16661100863258354766）均输出：
  ```
  MMLF CAMERA CONTROL SELFTEST ... passed=true（两项）
  MMLF COLLISION SELFTEST shapes=1 dx=0.2 / dx=-0.2 passed=true
  MMLF GUI SELFTEST name=screen ... passed=true
  MMLF GUI SELFTEST name=container leased_move cobblestone_delta=16 passed=true
  MMLF GUI SELFTEST name=container ... cobblestone_after_close=16 ... passed=true
  MMLF GUI SELFTEST name=thirdparty ... passed=true
  MMLF SURVIVAL SELFTEST placed count=7 passed=true
  MMLF SURVIVAL SELFTEST broken demo_drop_gained=1 passed=true
  ```
- 日志证据（`logs/*.jsonl`）：两次运行都没有 `reliable_*`、`invalid_message`、`request_rejected`、`stale_snapshot`、`stale_state`、`owner_change_conflict`、`snapshot_rejected`。Coordinator 转发 `ack` 47 / 40 次；次级端收到 `player` 快照 122 / 136 条；Fabric 主端一次中 3 次租约授予与归还、2 次 owner_change 发送与应用、4 次游戏模式跟随。
- 自测结束后游戏进程不会自动退出，由我按 PID 结束，属于正常结束方式之外的终止。

### 未执行的验证
- **真实游戏中的断线重连**（例如中途结束并重启 Coordinator 连接）没有执行。断线后恰好一次的结论只来自自动化测试里的真实 `RuntimeClient` 与 Coordinator，不是来自真实 Minecraft。
- 用户手动画面与交互验收未执行（语言切换、快捷栏快速切换、远端手持物显示）。

## 5. 尚未完成、限制与下一步
- Coordinator 的出站缓冲仍由 java-websocket 管理，没有显式上界或背压。界限目前只在运行时：发件箱 1024 条 / 60 秒，重排缓冲 1024 条。
- 可靠流只在进程内存中，不持久化。进程崩溃时它发出但未确认的消息会丢失，接收方会记录缺口。
- `ObjectState` 的 generation 与边界事件驱动属于任务 B，本次未改。
- 物品类操作仍是租约 + 增量规则，没有手册 §8 的跨端事务（不在本轮范围）。
- 协议版本已升到 2：新旧版本 JAR 不能混用。发行版两端必须同时更新。
- 下一步：任务 B（世界镜像与边界）。
