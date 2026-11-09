# 统一渲染层（进行中：主体已经用户实机验证，Flywheel 等残留内容未解决）

## 目标与范围

用户确认的目标是统一渲染层：
- 窗口所在的近端渲染整个场景。
- 远端只提交自己拥有的内容，不再自己渲染完整画面再交给近端贴合。
- 远端光影不在范围内，只要求近端光影能正确渲染远端内容。
- 不针对 Sodium、Iris 等具体 Mod 写专门代码，它们只作为验收样例。
- 渲染类 Mod 在远端照常安装，只是不被使用。

计划分四步，本次全部实现：
1. 静态区块。
2. 方块实体、实体等动态内容。
3. 半透明层。
4. 远端停止渲染世界，完整帧合成只保留为残留内容的兜底。

全部功能位于 `MMLF_EXPERIMENTS=geometry,geometry_replay` 下，`scripts/compat/run_visual.py` 默认开启。`unified_preview` 和 `MMLF_RENDER_HOST=auto` 冻结，不再扩展。

## 实现了什么

### 近端：用一个客户端代理实体绘制全部远端内容（`RemoteProxy`）

- **原理**：此前在 `renderLevel` 的 `blockentities` profiler 段注入绘制，已改为在近端客户端世界中放一个只存在于客户端的 `Marker` 实体（`RemoteProxy`）。它固定在相机所在方块，由它的渲染器绘制远端的静态网格和动态批次。
  - 渲染器通过 `EntityRenderDispatcherMixin.getRenderer` 提供。
  - Marker 没有网络表示，不会发往服务端。
- **为什么通用**：任何绘制实体的渲染器或光影 Mod 都会在自己的每个 pass 中绘制它，并带上该 pass 自己的变换和视锥：
  - 原版主 pass
  - Sodium 的实体处理
  - Iris 光影包的阴影 pass

  这样不需要任何 Sodium/Iris 专门代码，就有希望让远端内容投射光影阴影（原方案的已知缺陷）。
- **剔除**：渲染器在 `shouldRender` 中记下当次 pass 的视锥，绘制时按区块剔除静态网格。
- **顶点变换**：所有顶点都经 `PoseStack` 变换（法线用法线矩阵）。坐标先在 double 精度下相对代理实体位置求差，再转 float，远离原点时也不失精度。
- **确认条件**：近端向远端发送 `mmlf:geometry_consumed` 前，要求以下三项同时满足：
  - 远端图集代次与网格一致；
  - 最近 1 秒内收到过动态帧；
  - 代理实体正在被绘制，或当前没有任何远端内容。

### 第 1 步 静态区块（沿用上一版，改为经代理实体绘制）

- **编译**：`OwnedSectionCompiler` 在远端用原版 `RenderRegionCache` 与 `SectionCompiler` 编译含远端自有方块的区块，与远端装了什么渲染器无关。
- **重编触发**：挂在 `ClientLevel` 的世界数据入口。
- **近端绘制**：所用 RenderType 照搬原版区块层的状态，只把纹理换成远端图集。
- **远端图集**：在近端注册为 `mmlf:remote/<runtime>/blocks`，由 `RemoteTexture` 管理，场景重置时释放。

### 第 2 步 动态内容（`DynamicCapture`）

- **录制方式**：远端每帧在 `Minecraft.runTick` 调用 `GameRenderer.render` 之前，自己调用原版 `EntityRenderDispatcher.render` 和 `BlockEntityRenderDispatcher.render`，绘制渲染距离内它拥有的实体和方块实体。目标是一个录制用的 `MultiBufferSource`（每种 RenderType 一个 `BufferBuilder`），而不是帧缓冲。
  - 不依赖远端自己的世界 pass 和视锥：相机背后的物体也会录制，近端的阴影 pass 和快速转身都能用上。
  - 任何渲染器都经由这两个分发器绘制方块实体和实体，Sodium 自己调度方块实体时同样如此。
- **归属规则**：与方块相同，按命名空间判断：
  - 非 `minecraft` 命名空间的实体类型；
  - 携带远端物品的 `ItemEntity`、`ItemFrame`，以及远端方块的 `FallingBlockEntity`；
  - 玩家不属于远端。
- **每个批次携带的信息**：
  - RenderType 名称：经 `RenderStateShardAccessor` 读取，例如 `entity_cutout_no_cull`。
  - 所绑定的纹理：对该 RenderType 执行一次 `setupRenderState` 读出 GL 纹理，再经 `TextureManagerAccessor` 反查资源名；未注册的纹理记为 `gl:<id>`。
  - 顶点格式名与图元模式。
- **编码**：用新增的 `RenderDynamicCodec`（common，有单元测试），以远端相机位置为 double 原点。
- **纹理传输**：每个场景代次传一次，不超过 100 万像素的纹理每 10 秒刷新一次，用于字形页、地图等会增长的纹理。远端方块图集走静态图集通道，不重复传。
- **近端重建 RenderType**：按名称映射到近端同名的原版 RenderType 工厂（`entityCutoutNoCull(loc)` 等），纹理为近端注册的 `mmlf:remote/<runtime>/<hash>`。
  - 区块层名称：用图集替换版。
  - 未知名称：按顶点格式回退，`NEW_ENTITY` 用 `entityTranslucent`，`BLOCK` 用半透明区块层。
  - 格式或模式不一致：不绘制，并记录 `dynamic_type_unsupported`。
- **顶点写出**：按元素逐个写出（位置、颜色、UV0、UV1、UV2、法线），光影 Mod 的扩展顶点格式由目标缓冲自行补齐。
- **替换旧路径**：本步取代了 Codex 的 `GeneralDynamicCapture`、`RenderTypeCaptureMixin` 与 `dynamic_batches` 实验（只能捕获方块图集上的立即批次，每 250 ms 一次，实体光照需烘焙进颜色），这些代码和 `EntityVertexConverter` 已删除。实例通道 `RenderInstanceChannel` 仅保留传输探针。

### 第 3 步 半透明层

- **提交与接收**：远端提交全部五个区块层，近端接收能力加入 `translucent` 与 `tripwire`。
- **排序**：半透明的替换 RenderType 设置 `sortOnUpload=true`，由近端缓冲源按近端相机排序，与原版半透明实体的处理一致。
- **隐藏规则**：`BridgeCore.renderOwnedInSection` / `renderOwnedFluidInSection` 不再为半透明层保留例外。确认后，远端原生区块网格不再含任何方块和流体（原版编译路径）。

### 第 4 步 远端停止渲染世界（`NativeWorld`）

- **确认后隐藏的内容**：
  - 远端世界 pass 不再绘制它提交的内容（区块网格为空，`BlockEntityRenderDispatcherMixin` 跳过方块实体，`EntityRenderDispatcherMixin.shouldRender` 跳过实体）。
  - 近端世界已有的部分也不再绘制：云（`OptionsMixin` 让 `getCloudsType` 返回 `OFF`）、原版类的粒子（`ParticleEngineMixin`，类名以 `net.minecraft.` 开头）。
  - 天空和天气不在方法头取消：其他 Mod 在这两个方法头尾成对注入，且天空不写深度。
- **残留检测**：此时远端帧里只剩没有通用路径可达的内容，例如直接画到 GPU 的 Mod。远端在已有的导出回读中扫描深度（`FrameExporter.drain` → `NativeWorld.observeDepth`）：
  - 有像素小于 1.0，就说明有残留。
  - 连续 2 秒且至少 10 帧没有残留，就跳过世界 pass。
- **如何跳过**：经 `MinecraftMixin` 在 `runTick` 中把传给 `GameRenderer.render` 的 `renderLevel` 置为 false。这与原版 `noRender` 用的是同一个开关，其他 Mod 在 `renderLevel` 头尾的成对注入都不会执行，不会失配。
- **探针帧**：跳过期间每秒渲染一帧探针，一旦发现残留就恢复世界 pass，合成帧随之恢复显示残留内容。跳过时，回读仍经 `BridgeCore.drainExportedFrames` 发布。
- **不跳过的情况**：远端打开了界面（托管 GUI 叠在世界上）、正在导出原生手持或物品图标时。
- **模式开关**：`MMLF_REMOTE_WORLD` 或 `-Dmmlf.remoteWorld`：
  - `auto`（默认）：如上。
  - `off`：一旦确认就跳过，不做残留检测。
  - `on`：始终渲染，用于诊断。

### 纹理动画（`AtlasAnimation`，`RenderAtlasPatchCodec`）

- **为什么需要**：远端方块图集每个场景只整张传一次，此前水、岩浆、火和 Mod 的动画纹理在近端是静止的。
- **远端**：每个客户端 tick（原版推进动画的时机）把图集挂到一个只读帧缓冲上，用 `glReadPixels` 读出所有动画精灵所在的矩形，每个 mip 层都读。动画精灵通过 `TextureAtlasAccessor` 查找：帧数大于 1 的精灵。内容与上一 tick 相同时不发布。
- **近端**：每帧取最新补丁，用 `glTexSubImage2D` 写入自己持有的远端图集副本。
- **为什么通用**：不关心动画由谁驱动（原版 mcmeta 或 Mod 自己的 ticker），只读图集纹理里的当前帧。
- **安全校验**：补丁带场景代次和图集尺寸，不匹配就丢弃。

### 时间与天气同步（`WorldClock`，`ServerLevelClockMixin`）

- **问题**：此前用户在 NeoForge 端改了世界时间，Fabric 端没有同步。两个运行时的集成服务端各自计时。
- **显式改动即时同步**：任一端的显式改动立即发给对端应用。挂在 `ServerLevel.setDayTime`（每 tick 加 1 的正常推进除外，指令、睡觉跳夜都算）与 `ServerLevel.setWeatherParameters`（只有指令调用，自然天气循环直接改字段）上。应用时置位，不会回传。
- **周期纠偏**：主端每秒广播一次时间与天气，其他端在时间相差超过 20 tick 或晴雨/雷暴状态不一致时采用主端的值，用于纠正远端自身的随机天气循环和 tick 速率差异。
- **未同步的指令**：其他指令（如 gamerule）仍不同步。

### 帧对齐（可选，`MMLF_FRAME_ALIGN_MS`）

- **原理**：动态帧现在携带它所跟随的主端相机位姿序号（`RenderDynamicCodec` 升到第 2 版）。远端录制前先读取最新位姿并据此设置相机。
- **等待机制**：设置 `MMLF_FRAME_ALIGN_MS>0` 后，近端每帧开始时最多等待这么久，直到收到跟随自己上一帧位姿的动态帧。每 600 帧记录一次 `frame_align`，含未等到的次数。
- **默认关闭**：等待会以帧率换对齐，按之前“用帧率换帧对齐”的 TODO 做成开关。静态网格与位姿无关，不需要对齐。残留合成帧不在此范围内。

### 传输（`RenderChannel`）

- **同一 JVM 内**：几何、图集、动态帧、纹理改走 JVM 级登记表（`InJvm.registry()`），每个通道一个 `AtomicReference<byte[]>`，没有文件读写，动态帧可以逐帧传递。
- **跨进程**：每个通道一个文件，原子替换，按修改时间与大小判断是否变化（原先只比较毫秒级修改时间）。跨进程时动态帧至多约 30 帧/秒。

## 发现的问题

- **视锥获取**：Iris 阴影 pass 自行遍历实体，不经过 `blockentities` 注入点，原方案中远端内容不会投射阴影。改为代理实体后，阴影 pass 会像对待普通实体一样绘制它。
- **旧动态捕获的缺陷**：
  - 只认方块图集，每 250 ms 一次，动画不连贯；
  - 依赖远端视锥，近端转身或阴影需要的物体不在远端帧里。
- **远端无世界 pass 时**：相机不会更新，`OwnedSectionCompiler` 的排序中心、方块实体距离判断都会失效。动态录制在跳过世界 pass 时自行调用 `Camera.setup`（`CameraMixin` 照常套用主端位姿）。
- **头部取消的风险**：其他 Mod 常在 `renderSky`、`renderLevel` 等方法头尾成对注入，在头部取消会跳过它们的尾部注入。因此取消方法的做法只用在方块实体分发器上（Iris 在此只记录当前方块实体编号），世界 pass 用原版开关跳过。
- **残留帧发布延迟**：跳过世界 pass 时 `captureFrame` 不执行，探针帧的回读要到下一次探针才发布。已在跳过的帧中补做回读发布。
- **方法名冲突**：`RemoteProxy` 的静态 `discard()` 与 `Entity.discard()` 冲突，编译失败，已改名为 `removeCurrent()`。

## 第一轮实机反馈（2026-11-09，用户手动测试 `iris-create` 两个方向）

| 方向 | 现象 | 原因 | 处理 |
|---|---|---|---|
| Fabric 近端 | 远端 Create 方块没有光影阴影 | Complementary 默认 `ENTITY_SHADOWS_DEFINE -1`，即 `shadowEntities=false`、`shadowBlockEntities=false`。已核对 Iris 1.8.14 的 `ShadowRenderer.renderEntities`：阴影 pass 会遍历所有实体并调用 `shouldRender`，代理实体本身会进入阴影 pass，只是光影包关闭了实体阴影 | 未修改。经实体通道绘制的内容受光影包实体阴影设置约束，要像地形一样投影，需要让远端内容进入近端地形管线，与 Flywheel 问题一起讨论 |
| Fabric 近端 | 掉落物下出现 4×4 圆形阴影 | 原版实体圆形阴影的 UV 超出 [0,1]，依赖纹理 CLAMP；`RemoteTexture` 设成了 REPEAT | 改为 `CLAMP_TO_EDGE`。另外，近端光影包启用时（`RenderShaderCapabilities.localActive()`），丢弃远端的 `entity_shadow` 批次，由光影包自己的阴影代替 |
| Fabric 近端 | 按 Q 丢出的远端物品显示为纸 | 主端世界中的掉落物只持有占位物品（纸 + 引用） | 新增 `ProxyItems`：主端每帧把占位掉落物的位置、年龄、朝向和引用发给物品所属的运行时；所属端用自己的原版 `ItemEntity` 渲染器把真实物品画进动态提交；主端在 `EntityRenderDispatcherMixin.shouldRender` 中跳过这些占位掉落物 |
| Fabric 近端 | 相邻放置的 Create 马达会被弹掉 | 推测是 Create 自身规则：转速冲突的动力源会弹掉其一。尚未用单独的 NeoForge+Create 对照确认 | 未修改 |
| NeoForge 近端 | 头顶一个大箱子 | 原版由 `LevelRenderer` 先把 `PoseStack` 平移到方块位置，再调用 `BlockEntityRenderDispatcher.render`，分发器自己不平移。录制时漏了平移，Fabric 测试模组的箱子方块实体（`chest.png` 图集）被画在相机处 | 录制前按方块位置减相机位置平移 |
| NeoForge 近端 | 远端的门（`mcwdoors`）消失或闪烁，测试平台的门不见了 | Fabric 远端已停止渲染世界（日志 `native_world rendered=false`），而原版客户端的光照队列（`ClientLevel.pollLightUpdates`、`LevelLightEngine.runLightUpdates`）只在 `LevelRenderer.renderLevel` 中处理，只剩每秒一帧的探针会推进光照。放置或开关门后，提交用的区块按缺失或过期的光照编译，每秒又被更新一次 | 跳过世界 pass 的帧中由 `NativeWorld.keepClientWorld` 补做这两步 |
| NeoForge 近端 | 远端物品没有光影 | 近端开光影符合设计（光影包只装在主端）。手持的远端物品（第一人称）和物品图标仍是远端渲染的像素层（`VisualLayers`），远端没有光影 | 未修改，尚未纳入统一渲染 |

两个方向问题不同，主要是因为：
- 远端内容不同：NeoForge 远端是 Create（Flywheel），Fabric 远端是 Macaw's Doors 和测试模组。
- 远端状态不同：NeoForge 远端因 Flywheel 残留一直在渲染世界，光照照常推进；Fabric 远端停止渲染后才暴露出光照问题。

修复后重新编译两端、运行 common 测试，均通过，尚未再次实机验证。

## 第二轮实机反馈（2026-11-09）

| 方向 | 现象 | 原因 | 处理 |
|---|---|---|---|
| NeoForge 近端 | F5 第三人称手上拿的是纸 | 主端世界里的远端物品只是占位物品（纸 + 引用），第三人称手持、物品展示框等都会由主端的 `ItemRenderer` 画成纸 | 新增 `ItemRendererMixin`：主端在主 pass 中遇到属于提交端的占位物品，不再画纸，而是把变换（相机相对的 `PoseStack`，在物品模型自身变换之前）连同引用记下来，下一帧经 `ProxyItems` 通道发给物品所属端；所属端用自己的 `ItemRenderer.render` 按同一变换画出真实物品，作为动态提交返回。阴影 pass、轮廓 pass 中只跳过，不记录。约有 1～2 帧延迟 |
| NeoForge 近端 | 自己放的门闪烁，放置失败或延迟 | 远端导出的几何顶点数在每次放门后先加 124、约 250 ms 后又减回，随后才稳定：远端客户端预测放置成功（`native_result SUCCESS`），服务端却没有产生方块（没有 `native_placement`），客户端回滚。属于协作层的放置转发问题，不是渲染问题。被拒的几次都在 z=3 一排，具体原因未确认 | 新增诊断 `native_placement_absent`，记录服务端读到的状态和区块原始状态、是否被 `NeighborMirror` 镜像、服务端玩家位置与距离、服务端手持物、放置位置上的实体。待下一轮日志确认 |
| NeoForge 近端 | 测试平台原来的门不见了 | `mcwdoors:oak_four_panel_door` 在已安装的 Macaw's Doors 1.1.5 中存在。会话日志里，对门下方的点击被路由到了近端（远端射线没有命中），说明门不在远端客户端世界中。原因未确认 | `DemoScene` 布置后 40 tick 自检每个本端放置的方块，丢失时记录 `placement_lost`（区块原始状态、读到的状态、是否镜像） |
| 两端 | 破坏远端方块没有粒子 | 破坏粒子在远端客户端生成，远端隐藏后不再绘制原版类粒子，而粒子又没有提交 | `DynamicCapture` 录制远端粒子：遍历 `ParticleEngine` 中四种标准粒子层（`terrain_sheet`、`particle_sheet_opaque/translucent/lit`）的粒子，调用其自身的 `render` 写入 `PARTICLE` 格式缓冲，并随动态帧提交。归属规则：Mod 粒子类，以及方块粒子 `TerrainParticle`（在远端世界中只会来自远端方块被破坏）；其余原版粒子（天气、环境）属于近端自己的世界。近端按原版粒子层的状态（粒子着色器、光照贴图、对应图集）重建 RenderType。远端世界 pass 隐藏后，四种标准粒子层的粒子都不再绘制，自定义渲染的粒子仍留在合成帧中兜底 |
| Fabric 近端 | Create 方块上有纯白斑点 | 待确认。如果是 Flywheel 部分，应来自远端合成帧（无光影但颜色正常），纯白不像这种情况；更可能是光影对代理实体所画网格的材质处理，或半透明子层的处理问题 | 未修改，需要关闭光影对照，并确认方块名称 |
| 测试场景 | 不再测试红石 | — | 新增 `config/visual-scene.json`（去掉信号/灯方块、拉杆、红石线、红石灯、比较器、篝火与探针，33 项减为 19 项）。`SessionHost` 在非自测会话中优先使用它，自测仍使用完整的 `config/demo-scene.json` |

验证：两端编译成功，common 测试通过，launcher 编译成功，均未实机验证。

## 第三轮实机反馈（2026-11-09）

- **现象**：
  - NeoForge 近端 F5 时，纸不见了，但物品模型也没有；
  - 第一人称手持物比手的动作慢；
  - 两端都有。
- **原因**：
  - 上一版第三人称方案是近端记录变换、远端绘制后再传回，必然延迟 1～2 帧。
  - 记录只在缓冲源等于 `renderBuffers().bufferSource()` 时进行，而 Iris 开启时主 pass 用的是另一个缓冲源，所以从未记录，模型就丢了。
  - 第一人称一直是远端渲染的手部像素层（`VisualLayers`），不在统一渲染层内。
- **处理**：`ProxyItems` 改为传网格：
  - 近端遇到属于提交端的占位物品（GUI 以外的所有显示用途，包括第一人称），按“引用 + 显示用途 + 左右手”向所属端请求网格。
  - 所属端用自己的 `ItemRenderer.render` 在单位变换下录制（物品模型空间，已含该用途的模型变换），作为批次发布；内容不变时不重复发布。
  - 近端在每个 pass 中用自己当时的 `PoseStack`、光照和覆盖层直接绘制该网格（`GeometryLayer.resolveBatches` / `drawResolved`，光照和覆盖层按本次绘制替换）。手持物因此跟手、没有延迟，并经过近端光影与阴影。
  - 统一模式下，主端不再用远端手部像素替换自己的手（`VisualLayers.replacePrimaryHand`），远端不再导出手部帧，也不再因手部而保留世界 pass。
  - 掉落物改为正常绘制，其物品绘制同样替换为网格，此前的掉落物替身实体方案已移除。
  - 首次出现某个物品时，网格到达前的一两帧什么都不画。
- **现象**：Fabric 近端 Create 方块上的纯白斑点，关掉光影后仍在，测试平台的创造马达（动画纹理）上也有。
  - **推测**：问题出在图集动画补丁上：`glReadPixels`、`glGetTexImage`、`glTexImage2D`、`glTexSubImage2D` 均未保证像素传输状态，若其他代码留下了已绑定的像素缓冲或行步长，数据会读写到错误位置。
  - **处理**：新增 `GlPixelState`，在所有远端纹理的读取和上传前后设置并恢复紧密排列、不绑定像素缓冲的状态。是否就是白斑的原因，尚未验证。
- **用户问题**：既然有统一渲染层，为什么还有两端画面融合的错误？
  - 统一渲染层只覆盖经通用路径提交的内容。以下几类仍是远端像素合成：第一人称手部（本轮已改为网格）、GUI 物品图标、Flywheel 等直接画到 GPU 的内容、远端 Sodium 的流体、自定义渲染的粒子。
  - 第三人称物品丢失是上一版方案的缺陷。

验证：两端编译成功，common 测试通过，未实机验证。

## 门放置失败的诊断结论（第二轮诊断日志，会话 `session-16882210428801396625`，NeoForge 近端）

- **现象**：
  - `native_placement_absent` 大量出现：远端客户端 `use` 返回 `SUCCESS`（客户端已预测放置），服务端约 150 ms 后仍没有方块。
  - 同一位置紧接着再点一次往往成功，例如 `(0,100,5)`、`(-5,100,0)`。
- **已排除**：
  - 服务端玩家距离 0.5～3.8 格，在交互范围内；
  - 手持物为门，服务端已于 04:21:00 切到创造模式，不存在消耗问题；
  - 大部分失败位置没有被 `NeighborMirror` 镜像，原始区块状态与读到的状态都是空气；
  - 游戏日志中没有 `moved wrongly` 或 `moved too quickly`。
- **判断**：原版 `ServerGamePacketListenerImpl.handleMovePlayer` 在玩家新碰撞箱撞上之前没撞到的东西时（`isPlayerCollidingWithAnythingNew`），会不打日志地把玩家传送回去。在客户端确认传送之前（`awaitingPositionFromClient != null`），`handleUseItemOn` 直接丢弃使用包。远端玩家的位置由主端驱动，而远端世界里有主端玩家看不到的东西（远端自己的门、服务端读取时叠加的镜像方块）。日志里多次出现 `entitiesInPlace: ["player"]`，即远端玩家站在门的位置上。
- **处理**：
  - 新增 `FollowerMovementMixin`：已连接的后台运行时跳过这项检查，其玩家位置以主端为准。
  - 诊断中增加 `awaitingTeleport` 字段，用于验证这一判断。
- **测试平台的门**：场景自检显示远端服务端上门一直存在，没有 `placement_lost`。问题在远端客户端或近端绘制一侧，待下一轮确认。

## 第四轮反馈（2026-11-09）

- **用户确认**：门放置失败已没有体感（`FollowerMovementMixin` 生效）；Fabric 端此前的修复已验证。
- **NeoForge 近端测试平台的门仍不在**。会话 `session-6515436272049567270` 的分析：
  - 远端服务端自检通过：门在场景布置 2 秒后仍在，`placements_checked` 未报 `placement_lost`。
  - 远端首次完整导出为 3 个区块、244 个顶点（04:41:26.7）。约 5 秒后、用户尚未操作时，又发生两次重新编译，结果为 208 个顶点（04:41:31.97），少了 9 个面。
  - 当时主端世界尚未就绪（04:41:35 才 `runtime_world_ready`），因此不是镜像方块或交互引起的，而是远端世界中某个本端方块被改变或移除，且发生在服务端自检之后。
  - 近端收到的第一份几何就是这份 208 个顶点的版本。
- **新增诊断**：
  - 场景中本端放置的方块（含双格方块的上半）在 120 秒内发生任何变化时，记录 `scene_block_changed`，包括服务端或客户端、旧状态、新状态。
  - 服务端变化另附调用栈前 10 帧，用于确定是哪段代码改动或移除了门。

## 测试平台的门：定位与修复（会话 `session-16187824109754468089`）

- **诊断结果**：`scene_block_changed` 记录到 04:50:58.566 远端服务端 `(-3,100,0)` 门下半被设为空气，调用栈为 `Level.setBlock ← NeighborMirror.set:102 ← NeighborMirror.receive`。17 ms 后，门上半被原版形状更新连带拆除。
- **原因**：
  - 主端把远端物体周围位置的原版方块状态发给远端（`NeighborMirror`），远端用这些镜像状态对本端方块做形状更新。
  - 当时主端世界还在布置平台（主端 `placements_checked` 晚了 4 秒），平坦世界 y=99 尚未铺地板，门下方发来的是空气。
  - 门的形状更新据此判定下方没有支撑，把自己拆掉了。这一步不可逆。
  - Fabric 近端方向不受影响，因为门属于主端自己，不经过镜像。
- **修复**：`NeighborMirror.set` 中由镜像邻居引起的形状更新，只在结果仍是同一种方块时应用（改变连接形态）；会把本端方块替换成其他方块或空气的结果被忽略，并记录 `shape_removal_ignored`。
- **验证**：两端编译成功，未实机验证。

## 第五轮反馈（2026-11-09）

- **用户确认**：
  - NeoForge 端白斑已消失，证实原因是远端纹理读写时像素传输状态不受控，已由 `GlPixelState` 修复；
  - 测试平台的门已出现（`NeighborMirror` 修复生效）。
- **快速右键开关门有延迟**：
  - 日志显示，从点击到近端加载新几何常见 100～230 ms。提交链路有三处节流：远端编译按客户端 tick 启动（50 ms）、远端导出最多每 250 ms 一次（快速连点时中间状态被合并）、近端每 100 ms 轮询一次。
  - 同一 JVM 内通道是内存交接，节流没有意义。改为在同一 JVM 中：远端每帧启动已知变化的编译并立即导出变化（`GeometryLayer.submitterFrame`、`OwnedSectionCompiler.frame`），近端每帧取最新几何。跨进程时仍保留原节流。
- **测试平台时间**：手动会话改为 10d（240000 tick，一天中的时刻为 0，即日出，阴影最长）；自测仍为 6000。
- **验证**：两端编译成功，未实机验证。

## 用户实机验证结论（2026-11-09）

用户手动测试 `iris-create` 两个方向后确认“都可以了”，覆盖本轮及此前未验证的改动：
- 快速开关门跟手（同一 JVM 内取消提交节流）；
- 远端手持物在第一人称和第三人称下正常、跟手（物品网格方案）；
- 白斑消失（`GlPixelState`）；
- 测试平台的门正常（`NeighborMirror` 不再因镜像状态拆除本端方块）；
- 门放置不再失败（`FollowerMovementMixin`）；
- 破坏粒子、掉落物、头顶箱子等此前各轮的修复。

以上为用户的画面与交互验证；没有自动化画面测试，也没有性能测量。

仍未解决：
- Flywheel 等直接画到 GPU 的内容只能经残留合成显示；
- 在光影包关闭实体阴影时（Complementary 默认），远端方块不投射光影阴影；
- 物品栏图标仍是远端像素；
- 远端 Sodium 的流体不受隐藏控制。

## 第六轮反馈（2026-11-09）

- **现象**：掉落物底部的圆形阴影没了。
- **原因**：上一版在近端启用光影时一律丢弃远端的 `entity_shadow` 批次，即假定光影包会代替原版圆形阴影。但是否显示原版圆形阴影，取决于近端自身的设置和光影模组，应与近端对自己实体的处理一致。
- **修复**：
  - 在 `EntityRenderDispatcher.renderShadow` 末尾挂钩。光影模组或选项若抑制原版阴影，会提前返回，执行不到这里。
  - 近端 2 秒内确实为自己的实体画过圆形阴影，就也画远端的；否则按“实体阴影”选项且未启用光影时才画。
- **说明（远端物品没有光影阴影）**：Complementary 默认关闭实体阴影（`ENTITY_SHADOWS_DEFINE -1`），近端自己的物品与生物同样不投射光影阴影，远端物品与之一致。Flywheel 绘制的部分来自残留合成帧，不经过近端光影。
- **验证**：两端编译成功，未实机验证。

## Flywheel：远端关闭后端（2026-11-09 与用户确认）

- **讨论结论**：
  - Flywheel 是独立渲染库，零件“画什么、怎么动”由各模组自己的实例类型和 GPU 着色器决定。例如 Create 的 `assets/create/flywheel/instance/rotating.vert` 用 `offset + flw_renderSeconds * speed` 计算转角。
  - 在近端运行 Flywheel，需要连同 Create 的客户端代码一起搬过来，不可行。
  - 用 Flywheel 公开接口接管，需要在近端编译各模组的着色器，会绕开近端光影包；截取 GPU 输出顶点则依赖 Flywheel 内部实现，且没有性能收益。
  - 已核对 Flywheel 1.0.6：`Backends` 在 `ShadersModHelper.isShaderPackInUse()` 为真时判定实例化与间接后端都不支持，退回关闭。即启用光影包时 Create 本来就走方块实体渲染器。
- **实现**：新增 `mmlf/compat/remote-overrides.json`，列出“远端（提交端）装有某模组时写入其游戏目录的配置文件”。`run_compat.apply_remote_overrides` 只在实验含 `geometry_replay` 时，对非主端的加载器应用，`run_visual.py` 启动时调用并打印 `Submitter override: ...`。脚本只读数据，不含模组专门逻辑。当前唯一条目：远端装有 `create` 时写入 `config/flywheel-client.toml`，内容为 `backend = "flywheel:off"`。
- **验证**：
  - 脚本函数单测：`iris-create`、主端 fabric 时只写 `neoforge/config/flywheel-client.toml`；主端 neoforge、或实验不含 `geometry_replay` 时不写入。
  - 尚未实机验证 Flywheel 是否读取该配置并关闭，以及 Create 零件是否改由动态录制提交。
- **后续**：远端静态方块进入近端地形管线、通用实例化通道等已写入 `docs/ai/TODO.md` 的“统一渲染层”一节。

## 验证结果

- `gradlew.bat :common:test :launcher:test --tests *DevShaderStaging* :fabric-bridge:compileJava :neoforge-bridge:compileJava`：BUILD SUCCESSFUL。
  - common 共 28 个测试类，0 失败，其中新增 `RenderDynamicCodecTest` 3 项与 `RenderAtlasPatchCodecTest` 2 项全部通过。
  - 两端编译成功，只有 4 条既有的 Mixin 映射警告。新增 Mixin（`EntityRenderDispatcherMixin`、`BlockEntityRenderDispatcherMixin`、`ParticleEngineMixin`、`OptionsMixin`、两个访问器、`MinecraftMixin` 新增的 `WrapOperation`）在 Fabric 端编译期映射均无警告。
- 用 `javap` 核对了 Loom 映射的原版 jar 中所用的方法与字段：`EntityRenderDispatcher.render/shouldRender/getRenderer/prepare`、`BlockEntityRenderDispatcher.render/prepare`、`ParticleEngine` 对 `Particle.render` 的调用、`Minecraft.runTick` 对 `GameRenderer.render(DeltaTracker,Z)` 的调用、`TextureManager.byPath`、`RenderStateShard.name`、`Marker` 构造器、`ClientLevel.addEntity/removeEntity`、各 RenderType 工厂与名称常量。
- **未启动 Minecraft，未做任何画面或交互验证。** 以下都只经过编译：
  - 代理实体在原版、Sodium、Iris 近端是否被绘制，Iris 阴影 pass 是否包含它；
  - 远端动态录制在装有 Sodium/Iris 的远端能否运行；
  - 各 RenderType 映射的实际效果；
  - 半透明排序；
  - 远端跳过世界 pass 与探针恢复；
  - NeoForge 端 `ParticleEngine.render` 重载是否命中（`require=0`）。

## 尚未完成与限制

- **需要实机验证**（用户只测完整配置 `iris-create` 并做双端对照）：
  - 远端方块、方块实体、实体是否缺失或重复；
  - 半透明方块；
  - 光影下的明暗与阴影；
  - 远端是否进入 `native_world rendered=false`。
- **Flywheel 等直接画到 GPU 的内容**无法通过通用路径录制（Create 的动力方块在 Flywheel 后端开启时不经过方块实体渲染器）。这些内容会被残留检测发现，远端继续渲染世界，经合成帧显示，因此 Create 场景下远端很可能不会停止渲染世界。
- **远端装有 Sodium 时**，它自己的流体网格器不受隐藏控制。远端世界中的原版水会作为残留留在合成帧中，同样会阻止远端停止渲染。
- **粒子**：不提交。远端 Mod 类的粒子留在合成帧兜底；用原版粒子类实现的 Mod 粒子会在隐藏后消失。
- **光照与动画**：方块与动态内容的光照值来自远端世界。动画只覆盖方块图集，其他纹理按 10 秒刷新。静态网格每帧在 CPU 上重写顶点，动画补丁每 tick 同步回读一次。性能均未测量。
- **半透明遮挡**：远端半透明内容在近端半透明地形之前绘制并写深度，两者相互遮挡时，可能遮住其后的近端水面。
- **静态区块**：仍是每 250 ms 一次完整快照，区块多时需要改为增量。
- **识别失败的 RenderType**：未能映射的动态 RenderType（如 `energy_swirl`、`beacon_beam`、`breeze_wind` 等带额外参数的类型）不绘制，但远端世界 pass 已隐藏实体，这部分会丢失，需按日志 `dynamic_type_unsupported` 补充。

## 远端方块进入近端地形管线（进行中，2026-11-09）

### 第 1 步：协作层占位镜像（已实现，已编译，未进游戏验证）
- 问题：远端方块只存在于远端世界，主端世界在那里读到空气，导致光照穿透、相邻面不剔除、水会流入、生物会刷在里面，地形管线也没有可编译的东西。
- 新增 `StandInBlock`（`mmlf_bridge:stand_in`）：
  - 属性 `solid` / `light`；
  - 无轮廓，射线照常穿过，交互仍转发给所属端；
  - 碰撞箱按位置取远端的 `_collision`；
  - 不可破坏，不传导红石，阻挡流体；
  - 暂时 `RenderShape.INVISIBLE`。
- 新增 `Occupancy`，只在主端生效：
  - 来源：根据远端发布的边界对象登记占位；
  - 服务端：读取时由 `LevelChunkMixin` 返回占位方块，不真正放置；主端自己的方块优先；
  - 客户端：区块数据不经过读取，所以直接把占位方块写进客户端区块，区块重新加载时再写一次；
  - 变化时：重算光照，重新计算相邻方块的形状（只改连接，不删除方块）；
  - 运行时离开或断线：只撤销占位，不触发相邻方块更新。
- `Boundary.publish` 新增 `_occlusion`（`partial`）和 `_light`；`Boundary.owns` 排除占位方块；`NeighborMirror.reflag` 让两种覆盖共用 `mmlf$overlaid` 标记。
- 两个加载器分别注册该方块：Fabric 在 `FabricBridge`，NeoForge 用 `DeferredRegister.Blocks`。方块状态文件为 `assets/mmlf_bridge/blockstates/stand_in.json`。
- 开关：`MMLF_OCCUPANCY=off` 恢复原来的行为。
- 验证：`:fabric-bridge:compileJava`、`:neoforge-bridge:compileJava` 编译通过。尚未在游戏内验证。

### 第 2 步：渲染层接入地形管线（只做了设计，未写代码）
1. **图集**：
   - 近端拼方块图集时，加入预留精灵 `mmlf_bridge:remote_reserve`，大小与远端图集相同（默认 1024²，尺寸记录在配置中；不够时重载一次资源）；
   - 内容填半透明，避免 Sodium 把半透明层降级为镂空；
   - 用读帧缓冲加 `glCopyTexSubImage2D`，把 `RemoteTexture` 各级复制进预留区；动画补丁同步复制。
2. **模型**：
   - mixin `BlockModelShaper.getBlockModel`，对占位方块返回占位模型；
   - Fabric 用 FRAPI 的 `emitBlockQuads`（能拿到位置，按层设置混合模式，关闭环境光遮蔽和方向明暗）；
   - NeoForge 用 `getModelData` 传位置，配合 `getRenderTypes` 和 5 参数 `getQuads`。
3. **网格分配**：
   - 远端区块段网格按“面中心向法线反方向偏移”分到具体格子；
   - 落在非占位格子的面，分给同一区块段内最近的占位；
   - 区块段内没有占位时，仍由代理实体绘制。
4. **顶点处理**：位置换算到相对占位格子的坐标，UV 换算到预留区，烘焙光照清零（交给近端光照），保留远端的顶点色。
5. **代理实体**：
   - 跳过已进入地形管线的区块段；
   - 判断“近端是否在画”（`confirmConsumption`、`RemoteProxy.place`）时，改为只看仍需代理绘制的内容。

### 第 2 步进度：图集预留资源（进行中，2026-11-09）

- 新增方块图集源 `assets/minecraft/atlases/blocks.json`，添加 `mmlf_bridge:remote_reserve`，并提供 1024×1024 的半透明 RGBA 精灵。两个加载器均从 `bridge-common` 引用共享资源。该资源目前会在两个运行时的方块图集中占用空间。
- 当前只建立了固定尺寸的预留资源；尚未复制远端纹理或动画、根据远端尺寸调整资源、接入模型及触发区块重编译。代理实体仍负责绘制，地形管线效果尚未实现。较大的远端图集无法放入当前预留区；增加图集尺寸也可能触及显卡纹理大小限制，均需后续处理。
- 资源检查：使用 Python 标准库解析 JSON，校验 PNG 的各块 CRC、尺寸及所有像素的 RGBA 值，通过。
- `gradlew.bat :fabric-bridge:build :neoforge-bridge:build` 未完成：包装器尝试下载 Gradle，网络权限被拒绝。使用仓库缓存的 Gradle 执行 `:fabric-bridge:processResources :neoforge-bridge:processResources --offline` 未完成：当前 Gradle 用户缓存缺少 Fabric Loom 插件；指定 `C:\Users\ZZY\.gradle` 后则无法加载 `native-platform.dll`。因此本轮没有编译或打包通过的结果，也没有启动 Minecraft 或验证真实画面和交互。
- 下一步：恢复可用的构建环境，验证资源在两个加载器的实际拼图；实现远端图集和各级动画补丁复制、尺寸扩容及资源重载，再实现模型适配、网格分配、失效重编译和代理回退。

### 第 2 步继续：图集复制与网格分配基础（进行中，2026-11-09）

- 发现固定预留资源如果在远端也装入，会使远端图集变大，可能大于预留区。`SingleFileMixin` 在后台运行时及未启用统一渲染时跳过该资源；此判断只覆盖默认主端渲染，切换渲染宿主的实验模式仍需处理。
- `TerrainAtlas.copy` 在近端收到远端图集或动画补丁后，将远端各级纹理复制到预留区；缺失预留区或尺寸不够时记录日志并保持现有代理绘制。大小仍固定为 1024×1024，尚无自动扩容。
- 新增 `TerrainQuadPartition`：根据面中心和法线确定占位格，落在空格的面分到同区块段最近的占位格，未找到占位格时留给代理处理。该算法尚未接入渲染。
- 在允许访问本机 Gradle 缓存和加载 DLL 的环境中执行 `:fabric-bridge:compileJava :neoforge-bridge:compileJava :fabric-bridge:processResources :neoforge-bridge:processResources --offline`：BUILD SUCCESSFUL，Fabric 报告 4 条既有 Mixin 映射警告。执行 `:common:test --tests dev.mmlf.common.TerrainQuadPartitionTest --offline`：BUILD SUCCESSFUL。之后又调整了精灵的启用条件，仍需再次编译。
- 未启动 Minecraft，图集复制、Mixin 实际命中、动画和画面均未验证。未实现尺寸扩容、两个加载器的模型适配、网格失效重编译或代理切换；第 2 步仍未完成。

### 第 2 步补充：模型适配草稿（进行中）

- 新增 `TerrainMeshes`，准备将网格按占位格转换为本地图集坐标的 `BakedQuad`，并以不可变快照向编译线程提供数据；区块没有占位或包含不支持的格式时不纳入地形绘制。另新增 Fabric 和 NeoForge 的模型适配草稿。**这些代码尚未接入模型查找、区块变更或代理切换，不能产生地形画面。**
- 图集预留和复制现由 `MMLF_TERRAIN=on` / `-Dmmlf.terrain=on` 显式启用，默认关闭；即使开启，当前也只是实验性贴图复制，不能视为完成的地形模式。
- `:common:test :fabric-bridge:compileJava :neoforge-bridge:compileJava :fabric-bridge:processResources :neoforge-bridge:processResources --offline`：BUILD SUCCESSFUL；Fabric 仍有 4 条既有 Mixin 映射警告。未启动 Minecraft，未验证图像、交互或 OpenGL 状态。
- 剩余工作：模型注册与接入、跟踪占位及网格更新、支持远端图集超过预留尺寸时扩容、处理异步区块重建完成后才切换代理及过期编译结果、支持 Sodium 的完成通知、两个方向实机验证。尤其不能在提交区块重建时立即停画代理，否则会出现空洞；也不能永久同时绘制，否则会出现重叠。

### 第 2 步：实验接入与运行检查（进行中，2026-11-09）

- 两个加载器分别安装模型适配；占位方块在 `MMLF_TERRAIN=on` 且主端启用统一渲染时输出地形模型。`TerrainMeshes` 根据占位位置分配四边形、转换位置与 UV、清除远端烘焙光照并保留颜色；不支持的网格或无占位的区块段继续由代理绘制。网格或占位变化会重新分配并标记区块段重建。
- 为避免异步重建时直接关掉代理，版本号随编译任务传递；只有相同版本的网格上传后，才跳过该区块段的代理。当前适配原版编译器和 Sodium 0.8.13；编译期间旧地形与代理可能短暂重叠，尚未进行性能评估。
- 接收端用动态半透明精灵预留纹理位置。远端图集超出预留尺寸时，按显卡纹理上限保守扩容并重载资源；超出限制或复制失败时保留代理。当前每次动画补丁会复制完整远端图集，开销未测。背景运行时跳过预留，避免图集递归增大；实验性非主端渲染宿主不在当前支持范围。
- `:common:test`、两个加载器 `compileJava` 和 `processResources` 均执行成功；Fabric 编译仍有 4 条既有映射警告。曾出现私有 `SectionTaskResult` 导致编译失败，已改为不依赖其可见性的结果检查并重新编译成功。
- 实际启动 Fabric 近端 `iris-create`：进入世界，日志记录远端图集从 1024 预留扩至 2048，收到 3 个区块段、496 个顶点（会话 `runtime/session-2384129264433652723`）。没有验证交互，且该次未启用光影包。
- 实际启动 NeoForge 近端 `iris-create`：进入世界，日志记录 3 个区块段、9 个占位格，并确认版本 4 的 3 个区块段上传（会话 `runtime/session-6634370239108338679`）。观察到测试平台上的门、蓝色与红色方块等可见；没有逐项验证画面正确性与交互，且该次未启用光影包。
- 初次运行 `run_visual.py --profile iris-create --primary fabric` 因配置中的 Iris 版本范围检查退出；加 `--loader-validates` 后由加载器验证并启动。新版带 Complementary 光影的 NeoForge 近端场景已启动，正在检查。
- 仍需人工分别评估两个近端方向的贴图、面剔除、光照、阴影、动态更新、半透明、交互及占位物理行为；尚未做自动化画面测试或性能测量。`MMLF_TERRAIN=on` 仍是实验开关，默认关闭；资源包重载、显卡纹理上限与不同 Sodium 版本仍有风险。

### 带光影的人工评估场景（待用户评估）

- 执行 `$env:MMLF_TERRAIN='on'; python scripts/compat/run_visual.py --profile iris-create --primary neoforge --loader-validates --shaderpack runtime/shader-baseline/neoforge/shaderpacks/ComplementaryReimagined_r5.2.2.zip`（同时使用现有 `GRADLE_USER_HOME` 缓存）。
- 会话 `runtime/session-2474032747417922250` 已进入世界，`shader_candidate` 记录 NeoForge 光影 `ACTIVE`；日志记录 3 个区块段的版本 4 均已上传。截图观察到地面及角色阴影，未观察到足以判断远端方块阴影正确性的画面，也未验证交互。窗口保持打开，等待用户评估。
- 复现反向场景可改为 `--primary fabric --shaderpack runtime/shader-baseline/fabric/shaderpacks/ComplementaryReimagined_r5.2.2.zip`，保留其他参数及 `MMLF_TERRAIN=on`。关闭实验开关可回到既有代理路径。
