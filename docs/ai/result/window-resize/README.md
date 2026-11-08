# 主窗口尺寸变化与合成失效修复报告（进行中：未实机验证）

## 1. 问题与日志证据

会话 `session-12204234034067556936`（2026-10-28 15:40）中，用户最大化/调整 Fabric 主窗口后报告：远端物品消失、渲染怪异、手持层出现在画面中间、中键/左键/右键（含近端门）全部失效、远端物品快捷栏图标不对。

日志事实：

- `fabric.jsonl` 在 07:41:59.758 之后不再有 `protocol/send_pose`，此后全部 `input/request` 的 `cameraId` 固定为 4149；`render.composite/frame` 也同时停止。
- `manager.jsonl` 显示之后的每次 `ray_query` 都收到两端 `ray_hit`（revision 0），但没有任何 `interaction/routed`，Coordinator 也没有记录丢弃原因。
- 本机物理分辨率 2560×1440（125% 缩放）。

原因：`BridgeCore.publishCamera` 只在帧缓冲 ≤1920×1080 时发送姿态（受 `FrameFile` 固定容量与 `Pose` 校验限制）。窗口最大化后帧缓冲超过上限，姿态停发，于是：

1. 远端仍按旧相机、旧尺寸（1280×720）渲染，主端合成要求尺寸一致，远端世界层消失。
2. 手持层裁剪坐标是 NeoForge 1280×720 帧缓冲中的像素坐标，直接贴到更大的主窗口，所以位置偏到中间。
3. 输入路由使用过期的 `primaryPose` 做射线，看向的已不是玩家准星，Coordinator 得到未命中或未索引目标后静默丢弃，三个鼠标键都失效（近端操作同样经 Coordinator 路由）。

另外发现：

- 快捷栏原生图标在 `renderLevel` 返回后绘制，当时是世界光照、雾和模型视图状态，而不是 GUI 物品光照，方块物品的明暗会不对；远端还没导出图标时，槽位显示为空。
- 启动器主循环检测到主端断开后 `main` 返回，但 Coordinator 的 WebSocket 线程不是守护线程，JVM 不退出，shutdown hook 中的清理从未执行，导致启动器和 Coordinator 长时间残留（本次实际观察到 15:40 的启动器在 16:52 仍在运行）。

## 2. 修改

- `FrameFile`：默认容量提升到 2560×1440，并支持按文件指定容量（图标文件只需 32×32）；新增 `fits()`。`Pose` 尺寸校验改为单边 ≤4096。
- `BridgeCore.publishCamera`：姿态始终发送，不再依赖远端层能否传输；超过帧容量时记录 `render.transfer/window_exceeds_frame_capacity`（WARN 采样）。
- 非主端新增 `matchPrimaryWindow`：用 GLFW 把 NeoForge 窗口调整到主端帧缓冲尺寸（考虑窗口与帧缓冲的缩放比，500ms 限频），记录 INFO `render.capture/match_primary_size`。采集和合成在尺寸不一致时分别记录 `render.capture/size_mismatch`、`render.composite/size_mismatch`。
- `VisualLayers`：手持层只在源帧尺寸与主端一致时导出和合成，否则记录 `render.hand/size_mismatch`；图标绘制前设置单位模型视图、`FogRenderer.setupNoFog()`、`Lighting.setupFor3DItems()`；没有原生图标时回退显示带标签的代理物品。
- `Coordinator`：未命中和未索引目标分别记录 DEBUG `interaction/no_target`、`interaction/unindexed_target`。
- `Launcher`：会话结束后显式 `System.exit(0)`，触发清理钩子结束客户端进程树和 Coordinator。

取舍：帧容量 2560×1440 时每个帧文件约 88MB（三槽 × 颜色+深度），世界层和手持层两个文件约 177MB。超过 2560×1440（例如 4K）时远端层仍不显示，但交互不再受影响，并有 WARN 日志；按比例降采样后再合成留待后续实现。

## 3. 结果

实际执行：

```
./gradlew :common:test :coordinator:test :launcher:build :fabric-bridge:build :neoforge-bridge:build :fabric-test-mod:build :neoforge-test-mod:build
```

结果为 BUILD SUCCESSFUL；11 个测试全部通过（新增 `FrameFileTest.capacityCoversMaximisedHighResolutionWindowAndSmallFilesRejectLargeFrames`）。`GameRendererMixin` 的 mapping 警告在此次修改前就存在。

- 编译：通过。
- 自动化测试：通过（只覆盖帧容量与姿态校验，不覆盖 GLFW 窗口调整、图标光照或启动器退出）。
- 真实 Minecraft 启动：本次未执行。曾启动过一次，但按用户要求在客户端就绪前中止，没有产生任何验证结论。
- 真实画面与交互验证：未执行。

## 4. 未完成与下一步

- 需实机确认：最大化后 NeoForge 窗口跟随调整、远端层和手持层位置正确、三个鼠标键恢复、图标明暗正确、关闭主窗口后启动器自动退出。
- Windows 是否允许后台窗口调整到与主窗口相同的尺寸（受屏幕尺寸限制）尚未确认；若被系统限制，会出现 `size_mismatch` 日志，届时需改为离屏 RenderTarget 渲染。
- 4K 等超出容量的分辨率需要降采样合成。
