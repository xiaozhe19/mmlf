# v2 工作流 B1/B2/B4/B5：统一渲染（进行中：编译通过；真实画面未验证）

日期：2026-11-05。需求：`docs/manual/Single JVM Two Runtime v2.md` 第三节；实施计划 M2。帧通道（B3）见 `../v2-frame-channel/`。

**状态**：本轮所有渲染改动只完成了编译和代码审查。GPU 正在训练模型，**没有真实画面验证**。新行为默认关闭（B2），或者只增加日志（B4），不会改变默认画面。

## 一、实现了什么

### B4 统一帧序号与一致性指标

已有基础：远端每帧的元数据带有 `cameraSequence`（远端渲染该帧时使用的近端姿态序号），但近端从不使用它。

新增内容：

- `common/PoseHistory`：近端按姿态序号记录本地发布时间，环形缓冲 1024 项，有单元测试。
- 近端每次合成远端帧时（`BridgeCore.gpuComposite`）：
  - **端到端延迟** = 现在 − 该帧对应姿态的发布时间。两个时间点都来自近端自己的时钟，不需要跨端时钟校准。它覆盖远端渲染、读回、传输和近端读取的全过程，比原有的"帧龄"（从读回开始计时）更完整。
  - **落后姿态数**：合成时，近端已经发布的、比该帧更新的姿态有多少个。
  - **重复帧**：近端帧率高于远端时，同一远端帧被合成多次的次数。
  - **过期拒绝数**。
- 新日志 `metrics/frame_sync`：每 10 s 一次，按远端分别记录。
  - 字段：`endToEndP50/P95/P99Ms`、`posesBehindP50/P95`、`composited`、`repeatedFrames`、`staleRejected`、`unknownPose`、`frameChannel`。
- `metrics/runtime` 新增：
  - 帧时间分位数 `frameTimeP50/P95/P99Ms`（只看 fps 会掩盖卡顿）；
  - `gcCollections`、`gcMillis`。单 JVM 中 GC 是两端共享的，这两个值是 JVM 级的。
- 未做：把分散的帧率和过期阈值逻辑收拢成 `RenderSchedule`。这是纯重构，没有真实运行就无法确认行为不变，本轮不做。

### B2 Render Ownership（`render/RenderOwnership`，默认关闭）

`-Dmmlf.renderOwnership=safe`（或 `MMLF_RENDER_OWNERSHIP=safe`）时，远端跳过以下两部分：

- **天空**（`LevelRenderer.renderSky`）：远端天空在远平面（深度 = 1），在合成的深度测试中必然输给近端，跳过只节省 GPU 时间。
- **雨雪**（`renderSnowAndRain`）：远端的雨会被烘进远端方块的颜色，与近端的雨叠成两层。跳过它同时修正了这个画面问题。

安全判定（不按 Mod ID）：

- 首次询问时枚举 `Mixins.getConfigs()`。若有任何非 MMLF 的 Mixin 配置以 `LevelRenderer` 或 `DimensionSpecialEffects` 为目标（Iris、天空类 Mod 等），对应部分自动保持绘制。
- 结论记录在 `render/ownership` 日志中。
- 无法读取 Mixin 目标时，按"全部绘制"处理。

区块网格原本就已按归属拆分（`SectionCompilerMixin`）。

**新增：Sodium 网格路径的归属过滤（`mixin/SodiumBlockRendererMixin`）**

- 问题（代码与字节码审查发现，**未在真实游戏复现**）：Sodium 用自己的 `ChunkBuilderMeshingTask` 替换了原版的区块编译循环，`SectionCompilerMixin` 的过滤不再生效。远端会把近端拥有的方块也画进网格，与近端的同一方块深度相同，产生重复绘制和 z-fighting。
- 处理：`@Pseudo` Mixin 注入 `BlockRenderer.renderModel(model, state, pos, origin)` 的 HEAD，复用同一规则 `BridgeCore.renderOwned(state,pos)`。
  - 已用 `javap` 核对 Sodium 0.8.13 Fabric（intermediary）与 NeoForge（Mojang）两个版本的签名，两者一致。
  - 没有安装 Sodium 时，目标类不存在，Mixin 不生效。
- 这是把通用的归属规则扩展到另一个网格器，不按 Mod ID 过滤。流体走 `FluidRenderer`，与原版路径一样不做过滤。

未做（需要真实画面验证后才能安全推进）：

- 实体、方块实体、粒子的归属；
- 半透明层（厨锅蒸汽拖影）单独导出：研究方向是让远端内部使用原版 Fabulous 透明链；
- 用 `GL_TIME_ELAPSED` 测量远端 GPU 时间。

### B5 回归

- 所有新行为都有开关：
  - `frameChannel=file`、`renderOwnership=off` 为默认，等同 v1；
  - Sodium 过滤只在装了 Sodium 时生效。
- 两个方向的自测、截图对比都**未执行**。

## 二、实际执行与结果

| 层级 | 结果 |
|---|---|
| 编译（两个 bridge、`gradlew build`、`gradlew release`） | 成功 |
| 自动化测试 | `PoseHistoryTest`（1）通过；渲染相关代码没有可在无 GPU 环境运行的测试 |
| 真实启动、画面、交互 | **未执行**（GPU 被占用） |

## 三、下一步（GPU 空闲后）

1. 两个方向的默认配置自测：确认 `metrics/frame_sync` 正常输出，与 v1 自测结果一致。
2. 开启 `MMLF_RENDER_OWNERSHIP=safe`，在雨天场景截图对比，并检查 `render/ownership` 日志。
3. 运行 `sodium` 兼容性组合，确认 `SodiumBlockRendererMixin` 生效、远端不重复绘制近端方块。
4. 根据第 1 步的数据决定是否实现 `RenderSchedule` 和半透明层。

## 四、文件

- 新增：
  - `common/.../PoseHistory.java`（及其测试）
  - `bridge-common/.../render/RenderOwnership.java`
  - `bridge-common/.../mixin/SodiumBlockRendererMixin.java`
- 修改：
  - `BridgeCore.java`（指标）
  - `mixin/LevelRendererMixin.java`（天空和天气）
  - `mmlf.bridge.mixins.json`
