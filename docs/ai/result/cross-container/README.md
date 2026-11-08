# 跨端容器（漏斗 ↔ 另一端容器）实现报告

日期：2026-10-29。对应讨论中的"切片 3"：一端的漏斗把物品送进另一端的容器，或从中取出，带事务，不复制、不丢失。

## 1. 实现了什么
- 远端对象若持有物品（vanilla `Container`、NeoForge 物品处理能力或 Fabric Transfer API），所属端在 `ObjectState` 里标记 `_container`。
- 漏斗查找容器时（`HopperContainerMixin`，挂在两个加载器的漏斗最终都会回退的 `HopperBlockEntity.getBlockContainer`），若本地没有容器、而该位置是远端容器，返回一个替身 `RemoteContainer`。它有两格：
  - 格 0：从上方和侧面放入。物品仍属于本端，向所属端发出 `offer`；所属端用自己的自动化规则插入，回复 `accepted` 数量，本端只移除被接收的部分。容器满或拒绝时格 0 保持满，漏斗像遇到满箱子一样停下。
  - 格 1：只能从下方取出。只有下方漏斗来取时（`getSlotsForFace(DOWN)`），才向所属端发 `pull`；所属端取出最多 8 个后回复 `pulled`，这些物品此后属于本端，放进格 1 供漏斗取走。
- 物品编码：原版物品按数据传；另一端的物品按引用传（复用 `RemoteItems` 的引用和 payload）；所属端导出自己的 Mod 物品时新建引用。本端不能交出的物品（本端自己的非原版物品）替身拒收。
- 所属端的物品读写（`ItemAccess`）：加载器适配优先（`NeoForgeItems` 用 `Capabilities.ItemHandler.BLOCK`，`FabricItems` 用 `ItemStorage.SIDED` 与事务），否则按原版漏斗规则处理 `Container` / `WorldlyContainer`。
- 每个物品同一时刻只在一端：`offer`/`accepted`/`pull`/`pulled` 都走可靠流（消息类型 `transfer`，恰好一次、按序）。替身在对象消失、且没有进行中的事务时，把剩余物品掉落在原地；会话结束时同样掉落（`spillAll`）。
- 测试方块 `crate_block`：9 格 `Container` 方块实体。场景中主端漏斗 (5,101,7) 带 8 个圆石，向下送入后台 crate (5,100,7)；后台 crate (-5,101,7) 带 4 个绿宝石，被下方主端漏斗 (-5,100,7) 取出。

## 2. 发现了什么问题

| 问题 | 复现 / 现象 | 原因 |
|---|---|---|
| **物品复制**：只送了 8 个圆石，远端 crate 里出现 185 个 | 首次真实运行 session-15859295381668855874：`MMLF CONTAINER SELFTEST push hopper=0 remote_crate=185 ... passed=false`；NeoForge 日志 90 次 `offer_answered`，多数为 `accepted=0` | `HopperBlockEntity.addItem` 会原地缩减传入的物品堆，并返回同一个对象。所属端用 `stack.getCount() - rest.getCount()` 计算接收数量，结果总是 0，但物品其实已经插入。主端以为没被接收，继续保留并再次发出，于是复制 |

## 3. 如何解决
插入前先记下数量，并传入副本：`accepted = offered - insertInto(..., stack.copy()).getCount()`。`ItemAccess.insert` 的注释写明传入的物品堆可能被消耗。自测中的守恒检查（推入 8 = 远端 8、拉出 4 = 本端 4、替身中剩余 0）就是为了抓住这类问题。

## 4. 得到了什么结果
- 自动化测试：全部通过（0 失败）。容器路径目前没有纯单元测试，正确性依赖下面的真实运行检查。
- 真实 Minecraft 自测（两个方向，修复后，会话同 `redstone-bridge` 报告）：
  ```
  MMLF CONTAINER SELFTEST push hopper=0 remote_crate=8 pull hopper=4 remote_crate=0 waiting=0 passed=true
  ```
  两个方向分别由 NeoForge 和 Fabric 作为 crate 的所属端执行插入和取出。运行中没有记录每次具体走的是加载器能力路径还是原版 `Container` 路径，不能据此断言两条路径都已覆盖。结构化日志没有 `answer_without_offer`、`transfer_unconfirmed`、`pull_unencodable`。
- 未执行：真实第三方 Mod 容器（如 Tom's Storage、Farmer's Delight）；会话结束时的掉落；所属端在事务中途崩溃。

## 5. 尚未完成、限制与下一步
- 只支持原版漏斗与远端容器之间的往来。远端 Mod 的管道或机器向主端原版箱子输出，需要在加载器能力层做替身，尚未实现（已记 TODO）。
- 所属端在插入后、回复前崩溃：主端 600 tick 后放弃等待并记 `transfer_unconfirmed`。若对端在崩溃前已存档，物品可能复制。主端崩溃时，替身中最多一组待送物品和 8 个已取出物品会丢失。
- 下方漏斗请求时，所属端会预先取出最多 8 个物品，远端比较器读到的满度会因此偏低。
- 本端自己的非原版物品不能送入远端容器。
- 吞吐量未测量：每个替身同一时刻只有一个 `offer` 和一个 `pull` 在进行。
