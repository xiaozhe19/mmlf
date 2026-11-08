# 跨端邻居状态读取 实现报告

日期：2026-10-29。对应讨论中的"切片 2"：后台 Mod 读取邻居时，看到主端真实的原版方块。

## 1. 实现了什么
- 主端每 tick 收集"其他端对象的 6 个相邻位置"上的方块，用 `BlockStateParser.serialize` 写成文本（例如 `minecraft:campfire[facing=north,lit=true,...]`），只发变化（`SignalTable.NeighborOutbox`），走可靠流（消息类型 `neighbors`）。主端自己的 Mod 方块在别处无法构造，发布为空气；离开边界的位置以 null 撤回。
- 接收端（`NeighborMirror`）不放置任何方块，只在服务端区块读取时返回镜像状态：`LevelChunkMixin` 用 `@ModifyReturnValue` 改 `LevelChunk.getBlockState` 的返回值，只有被标记含镜像的区块才查表，其他区块只多一次字段判断。镜像表是并发表，因为光照线程也会读区块。
- 镜像变化时，按普通方块变化的方式通知邻居，并对本端相邻方块执行 `updateShape`。
- 本端在镜像位置放了自己的方块时，镜像让位。区块重新加载时重新标记。主端离开或断线时清除全部镜像。
- 镜像位置"只可见、不运行"（`MirroredNeighborMixin`）：邻居更新和形状更新到达镜像位置时什么也不做。镜像没有方块实体、不 tick、不掉落、不存档。
- 测试方块 `probe_block`：下方是点亮的篝火时点亮。它读取的是邻居的方块类型和属性，不是红石信号。场景中主端在 (2,100,7) 放篝火，后台在 (2,101,7) 放 probe。

## 2. 发现了什么问题

| 问题 | 复现 / 现象 | 原因 |
|---|---|---|
| 后台服务端无限邻居更新、卡住约 6 秒 | 首次真实运行 session-15859295381668855874；NeoForge `latest.log`：`Too many chained neighbor updates. Skipping the rest. First skipped position: 5, 100, -7`，`Can't keep up! ... 2593ms` | 主端红石线 (5,100,-6) 被镜像到后台。后台的邻居更新执行了这根镜像红石线的原生 `neighborChanged`：它在后台世界重算功率并写入，却仍读到镜像状态，于是不断更新邻居。镜像方块在后台被"运行"了，这正是要避免的重复执行 |

## 3. 如何解决
新增 `MirroredNeighborMixin`：`BlockStateBase.handleNeighborChanged` 和 `updateShape` 在镜像位置直接返回。修复后两次运行的 `latest.log` 都没有 `chained neighbor`。

## 4. 得到了什么结果
- 自动化测试：`SignalTableTest.neighbourStatesArePublishedOnChangeAndWithdrawnWhenTheyLeave` 通过；全部测试 0 失败。
- 真实 Minecraft 自测（两个方向，修复后，会话同 `redstone-bridge` 报告）：
  ```
  MMLF NEIGHBOR SELFTEST phase=on probe=true passed=true
  MMLF NEIGHBOR SELFTEST phase=off probe=false passed=true
  MMLF NEIGHBOR SELFTEST phase=reloaded probe=true passed=true
  MMLF NEIGHBOR SELFTEST phase=reloaded_off probe=false passed=true
  ```
  结构化日志没有 `unreadable_state`。
- 未执行：用户手动验收；真实第三方 Mod（例如 Farmer's Delight 厨锅放在主端篝火上）未测试。

## 5. 尚未完成、限制与下一步
- 只有主端 → 后台：后台没有自己的原版方块可发布（`ownedVanilla` 除外，暂不镜像）。
- 只覆盖 6 个直接相邻位置；读取更远位置的 Mod（例如检查下方两格、扫描多方块结构）看不到。
- 只能镜像原版方块；另一端的 Mod 方块显示为空气。
- 镜像没有方块实体，读取邻居方块实体的 Mod 拿不到数据。
- 光照不会因镜像重新计算；渲染不受影响（后台不绘制原版方块）。
