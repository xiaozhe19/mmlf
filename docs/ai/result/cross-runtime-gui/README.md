# 跨 Runtime Mod 交互与原生 GUI 桥接（Phase 2.1–2.5）实现报告

状态：Fabric 主端 + NeoForge 后台的核心闭环已在真实游戏中完成自动自测与用户人工验证；主次互换、一主两次等项未在本轮真实运行，见第 5 节。

依据：`docs/manual/Cross-Runtime Mod Interaction & GUI Bridge.md`。

## 1. 实现了什么

### 协议与纯逻辑（`common`）
- `Messages` 新增：`InteractionResult`、`GuiState`、`GuiInput`、`GuiFrame`、`SlotItem`、`InventorySnapshot`；`RayRequest`/`Interaction` 增加 `hand`、`selectedSlot`，旧构造器保留。
- `GuiLifecycle`：`CLOSED → OPENING → ACTIVE → CLOSING → CLOSED`，任意打开态可进入 `FAILED`；拒绝旧 instance 和旧序号的报告；打开 3 s、停帧 10 s、关闭 2 s 超时。
- `GuiInputSequencer`：发送端给事件编号；鼠标移动合并为最新一条，但在下一个按键/按钮边沿事件之前必定先发出；两端都记录按下中的按钮和按键，失焦或关闭时补发释放；接收端去重，并拒绝旧 instance 的事件。
- `GuiCoordinates`：两端窗口尺寸、GUI 缩放不同时的坐标归一化。
- `InventoryLease`：物品栏单一权威 + 租约 + 版本号；失败时回滚到最后确认的快照；新增 `OWNER_CHANGE`（租约外由次级端自身产生的变化）。
- `RecentIds`：有界去重。

### Coordinator
- 新增消息类型 `interaction_result`、`gui`、`gui_input`、`inventory`，并校验收发方向：输入只能由主端发出，报告只能发往主端。

### 2.1 原生方块交互
- `use` 和 `attack` 都在方块所属端的**客户端**原生执行：`MultiPlayerGameMode.useItemOn` / `startDestroyBlock` / `continueDestroyBlock`，由集成服务器按原生流程校验距离、线程和权限。服务端临时塞物品、瞬间破坏方块的旧路径已删除；"主端补播声音和粒子"的 `SoundMixin` 也已删除。
- 挖掘：主端按住攻击键期间约每 150 ms 发一次请求，所属端最多保持 600 ms；松开时发送 `mmlf:attack_stop`，立即停止并清除裂纹。
- 次级端玩家的游戏模式、语言、快捷栏选中格、着地状态都跟随主端（`mmlf:language`、`mmlf:held`、`FollowedGroundMixin`）。
- 交互结果回传主端；失败原因显示在动作栏。actionId 有去重。

### 2.2 普通 GUI
- 次级端 `GuiHost`：从 `Minecraft.setScreen` 检测"由路由交互打开"的界面（2 s 窗口内），系统界面不代理；界面在独立透明 `TextureTarget` 中绘制，捕获期间 alpha 按覆盖率累积（`BlendCaptureMixin`）并跳过模糊；用 PBO 异步读回（`GuiExporter`），发布到 `<runtime>-gui.frames`。
- 输入通过原生 `MouseHandler` / `KeyboardHandler` 回放（`@Invoker`）；隐藏窗口没有焦点，所以重定向 `isWindowActive`（拖拽）和 `InputConstants.isKeyDown`（Shift 点击等修饰键）。
- 主端 `RemoteGuiScreen` 是普通原版 Screen：显示远端帧，转发全部鼠标、键盘、字符输入（包括 ESC）；超时、断线、死亡、维度切换时都会清理。
- 次级端永不因界面暂停集成服务器。

### 2.3 容器 GUI 与物品栏一致性
- 主端物品栏持续镜像到次级端的服务端玩家；远端界面打开期间，物品栏租约交给该次级端，变化按版本回传，关闭时归还。主端独有的物品在次级端显示为不可移动的占位物品（`ContainerMenuMixin`、`SlotMixin`）。
- 租约外次级端自身的变化（放置消耗、捡起掉落物、Mod 塞入物品）按格回传；主端按"物品本身 + 数量增减"应用；无法对应时记 `owner_change_conflict` 并拒绝。

### 2.4 物品显示代理
- 远端物品代理携带名称、堆叠上限、耐久、Tooltip，以及次级端序列化的物品数据（payload）。次级端重启后，引用可以用 payload 还原。
- 创造模式：次级端的 Mod 创造标签页按原名称、原图标显示在主端（预留 16 个标签位，空位自动隐藏）；Mod 加进原版标签页的物品追加到主端对应的原版页；搜索可以找到。图标由次级端一次性渲染成图集，打开标签页时即全部就位。

### 2.5 双向
- 所有路径只区分 primary/owner，不写死加载器。

### 验收用真实 Mod
- NeoForge：Farmer's Delight 1.21.1-1.3.4（Modrinth `XTVZDOol`）
- Fabric：Tom's Simple Storage 1.21-2.4.2（`nhRuPk5W`）
- 两者均由 `scripts/fetch-demo-mods.ps1` 固定版本并校验 SHA-512 后下载。测试 Mod 新增通用的 `gui_block`（普通 Screen）和 `box_block`（9 格 ChestMenu），不是适配器。

## 2. 发现了什么问题

| 问题 | 复现 / 现象 | 原因 |
|---|---|---|
| NeoForge 端启动失败 | 加载 Farmer's Delight 时报 `requires neoforge 21.1.219` | 项目使用 21.1.172 |
| 主端崩溃 | 滚轮切到远端物品时报 `UUID string too large` | 调试日志把带 `#格子号` 后缀的字符串当作引用解析 |
| 一直提示 `inventory_not_synced`，切换时手先变空 | 来回切快捷栏 | 次级端选中格没有跟随，旧的 `updateHeld` 把物品写进了次级端客户端的错误格子 |
| 煎锅弹出的烤鸡丢失 | 租约外次级端捡起物品 | 租约外的变化不回传，下次镜像覆盖 |
| 手持 1 个远端方块可放置无数次 | 放到原版地面 | 服务端路径放置的是临时副本 |
| 生存模式不扣数量、捡起不加 | 放置、捡起 | 两端序列化数据含数量、引用随数量变化，被判冲突后丢弃 |
| 一下打掉、不掉落 | 左键 | 服务端 `destroyBlock` 瞬间破坏 |
| 裂纹停在第一格 | 按住左键 | ① 次级端自身 `continueAttack(false)` 每 tick 停止挖掘；② 保持时间短于请求间隔；③ 次级端玩家 `onGround=false`，挖掘慢 5 倍，服务端也会拒绝完成 |
| 远端物品图标陆续出现 | 打开创造模式物品栏 | 按需单个请求 |
| 生存模式交互仍按创造模式处理 | `/gamemode survival` | 次级端玩家的游戏模式独立 |
| 切换语言后远端文字不变 | 改语言 | 次级端语言独立 |

## 3. 如何解决

见上表对应的第 1 节实现：升级 NeoForge 到 21.1.265；选中格、语言、游戏模式、着地状态跟随主端；全部交互走所属端客户端原生路径；租约外变化按增减量回传；payload 不含数量；图标改为图集。

取舍：
- 没有修改进行中的相机/共享姿态重构文件；GUI 读回使用独立的 `GuiExporter`，没有重构 `FrameExporter`。
- `GuiSelfTest` 中的 `BridgeCore.selfTestAttacking` 只供自测在窗口无焦点时模拟"按住攻击键"。

## 4. 得到了什么结果

### 编译与自动化测试（已通过）
- `./gradlew.bat build`：Fabric、NeoForge 两端编译通过，全部单元测试和集成测试通过。
- 新增测试：`GuiLifecycleTest`（3）、`GuiInputSequencerTest`（4）、`InventoryLeaseTest`（3）、`CoordinatorIntegrationTest.hostedScreenTrafficOnlyFlowsBetweenPrimaryAndOwner`（1）。

### 真实 Minecraft 自测（已执行，Fabric 主端 + NeoForge 后台）
命令：`./scripts/run-demo.ps1 -Primary fabric -SelfTest`。最后一次会话 `runtime/session-*`（2026-10-29 00:25 左右）输出：

```
MMLF GUI SELFTEST name=screen requested=true opened=true closed=true reason=none passed=true
MMLF GUI SELFTEST name=container leased_move cobblestone_delta=16 passed=true
MMLF GUI SELFTEST name=container requested=true opened=true closed=true cobblestone_after_close=16 reason=none passed=true
MMLF GUI SELFTEST name=thirdparty requested=true opened=true closed=true reason=none passed=true
MMLF SURVIVAL SELFTEST placed count=7 passed=true
MMLF SURVIVAL SELFTEST broken demo_drop_gained=1 passed=true
```

含义：
- 普通 Screen：打开、点击、输入、滚动、ESC 关闭全部正常。
- 测试容器：Shift 点击 16 个圆石进入主端物品栏，租约期间和关闭后数量都正确。
- Farmer's Delight 厨锅：打开、输入、关闭正常。
- 生存模式：放置远端物品扣 1；挖掘远端方块后掉落物进入主端背包。

### 真实画面与人工交互（用户已验证）
- 用户确认：农夫乐事厨锅界面显示与操作、切菜流程、生存模式放置与挖掘、松手后裂纹消失均正常。
- 创造模式远端标签页、语言跟随、快捷栏切换：用户试用过，未报告新问题。

### 性能（真实运行日志，`metrics/gui`、`metrics/gui_capture`）
- 界面打开到首帧：多数 68–140 ms，出现过 306 ms、419 ms。
- 输入到画面反馈：P50 37–92 ms（样本少）。
- 次级端 GUI 读回：P50 30–32 ms，P95 36–56 ms。

样本量很小，不构成性能结论。

## 5. 尚未完成、限制与下一步

未在本轮真实运行验证的：
- NeoForge 主端 + Fabric 后台（Tom's Simple Storage 终端）的完整流程；本轮只有 Fabric 主端被系统性测试过。
- 一主两次（同一加载器的第二个 Runtime）。
- 不同 GUI 缩放、窗口缩放时的点击坐标；GUI 快速开关；Runtime 中途退出时租约回滚的真实验证。

已知限制：
- 远端界面中的主端独有物品显示为屏障占位，不显示原图标。
- 租约外两端同时修改同一格时，次级端的变化会被拒绝并记录错误（极少出现，不会复制物品）。
- 远端物品的 Tooltip 是次级端生成的纯文本，样式和动态内容会丢失。
- 主端预留 16 个创造标签位，超过时多出的标签页不显示（有日志）。

渲染层待办（已记入 `docs/ai/TODO.md`）：
- 远端手部重新装备动画异常。
- 半透明粒子（蒸汽）被当作不透明表面合成，形成拖影。
- 全屏时后台帧率减半、远端画面延迟翻倍。
- 远端物体锯齿。

下一步：主次互换和一主两次的真实验证；渲染层后台降分辨率与半透明层分离；Phase 3 统一物品栏的持久化。
