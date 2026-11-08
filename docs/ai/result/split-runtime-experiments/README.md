# 拆分架构验证实验报告（进行中：手册 §9 的 8 步中做了 3 步的最小验证，其余未执行）

日期：2026-10-29。Windows 11、RTX 3060 Laptop GPU（驱动 591.44，另有 Intel Iris Xe 核显）、Temurin JDK 21；Minecraft 1.21.1，Fabric 主端（近端）+ NeoForge 后台（远端）。第三方 Mod：Farmer's Delight 1.3.4、Tom's Simple Storage 2.4.2、Macaw's Doors / Trapdoors 1.1.5。

依据：`docs/ai/split-runtime-manual.md`（拆分架构技术手册）。目的：在动手构建之前，用最小实验检验手册里风险最高、最能决定方向的几项假设。

## 0. 结论摘要

| 手册假设 | 实验 | 结果 | 验证层级 |
|---|---|---|---|
| §6.7 共享显存可用：Vulkan 分配、两个 OpenGL 进程按名字导入、信号量同步 | E1 独立程序 | **成立**。四个扩展齐全，120/120 帧跨进程校验正确；1080p 一帧在显卡内拷入共享缓冲 0.28 ms，对端上传 0.05 ms | 独立程序在真实驱动上运行；**未接入 Minecraft** |
| §6.8/§10 直接调 GL 的 Mod 不多 | E2 字节码扫描 | 演示用的 4 个 Mod 中 **0 个**直接调 GL；`RenderSystem`/`GlStateManager` 只出现在 GUI 界面类 | 静态扫描；样本只有 4 个 Mod，**不能代表整个生态** |
| §6.2/§9 步骤 1 几何层：远端区块网格交给近端绘制 | G1、G2 真实 Minecraft | **成立**。关闭远端世界像素层后，远端方块由近端自己的管线绘制，截图与像素合成对照几乎一致；转头时远端方块无裁切；远端门板开关状态正确更新；全部现有自测通过（G2 一项边界自测除外，见 §3.3） | 编译、真实启动、自动化自测、截图人工对比 |
| §5.1 视距是远端内存的主要杠杆 | G2 | **部分成立**。视距 12→2 后加载区块 2601→961，RSS −7%；但 961 是"玩家票据"的下限，要再降必须不用玩家票据（§3.4） | 真实 Minecraft，指标日志 |

一句话：**几何层主路径和共享显存这两个最大的技术前提在本机都验证可行**，可以按手册方向推进；Sodium/Iris 对接（步骤 3）、动态几何（步骤 2）、Create/Flywheel（步骤 4）、lockstep 去程多 1 tick（步骤 7）本轮未做。

## 1. 实现了什么

### 1.1 E1：跨进程共享显存最小验证（手册步骤 5 的前半）

独立程序 `tools/SharedGpu.java`（不进入 Mod），使用 Minecraft 自带的 LWJGL 3.3.3（lwjgl、glfw、opengl）加上从 Maven Central 下载的 `lwjgl-vulkan-3.3.3.jar`（Windows 上无原生库，使用系统 `vulkan-1.dll`）。

- 生产者进程：创建隐藏 GLFW 窗口和 OpenGL 4.5 上下文，读取 `GL_DEVICE_UUID_EXT`；在 Vulkan 中选出 **UUID 相同**的物理设备（本机是双显卡，必须对上同一块 GPU），分配 `VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT` 可导出显存（命名 NT 句柄 `Local\mmlf_sharedgpu_mem`，8 294 400 字节 = 1920×1080×4）和两个可导出信号量；自身的 GL 按句柄导入为缓冲。
- 消费者进程（另一个 JVM）：按**名字**导入同一块显存和两个信号量（`glImportMemoryWin32NameEXT`、`glImportSemaphoreWin32NameEXT`）。
- 每帧：生产者清屏为一个随帧号变化的颜色 → `glReadPixels` 写入共享缓冲（PBO，显卡内拷贝）→ 信号量 `ready` 通知 → 消费者等待信号量后 `glTextureSubImage2D` 从共享缓冲上传到自己的纹理 → 读一个像素校验 → 信号量 `consumed` 归还。共 120 帧。
- 对照：同一程序中测量当前做法（读回到 CPU 内存）的耗时。

### 1.2 E2：Mod 渲染方式静态扫描

`tools/modscan.py`：读取 Mod jar 中每个类的常量池类引用，Fabric jar 的 intermediary 名用 Loom 的 `mappings.tiny` 翻译成 Mojang 名，按类别统计：直接 GL（`org/lwjgl/opengl/*`）、GL 状态（`GlStateManager`、`RenderSystem`、`BufferUploader`、`VertexBuffer`、`RenderTarget`）、原版缓冲（`MultiBufferSource`、`VertexConsumer`、`BufferBuilder`、`Tesselator`）、渲染器（方块实体/实体/粒子）、自定义 RenderType/着色器、GUI 界面；另统计资源（模型、方块状态、着色器）。

### 1.3 G1/G2：几何层原型（手册步骤 1）

开关 `geometry`（默认关闭，`MMLF_EXPERIMENTS`）。

| 位置 | 作用 |
|---|---|
| `bridge-common/.../render/GeometryLayer.java`（新增） | 远端：保存每次区块编译的网格副本，最多每 250 ms 把全部非空网格写入会话目录 `<runtime>.geometry`（临时文件 + 原子替换）；首次就绪时把方块图集第 0 级 mip 读出写入 `<runtime>.atlas`。近端：每 100 ms 检查文件修改时间，有新版本就重建全部 `VertexBuffer`；图集上传为独立纹理并生成 mipmap；在自己每个区块层（solid、cutout_mipped、cutout、translucent、tripwire）绘制完后，用同一个 RenderType 状态、同一个着色器、同一组矩阵绘制远端网格，只把 Sampler0 换成远端图集 |
| `mixin/SectionCompilerMixin.java` | 新增 `compile*` 返回处注入：拿到 `SectionCompiler.Results.renderedLayers`（`Map<RenderType,MeshData>`），在上传显卡之前复制顶点。远端网格本来就只含远端拥有的方块（已有的归属过滤） |
| `mixin/LevelRendererMixin.java`（新增） | `LevelRenderer.renderSectionLayer` 末尾调用近端绘制 |
| `BridgeCore.java` | `geometry` 开启时近端不再把远端世界像素层合成到画面（手持物、图标、GUI 仍走原像素路径） |
| `Experiments.java`、`DemoScene.java` | 新开关 `geometry`、`small_view`（远端渲染距离设为 2） |

取舍（原型的简化，非最终设计）：

- **图集不合并**：手册 §6.4 设想把远端 sprite 加入近端图集并重映射 UV。原型直接把远端整张图集作为单独纹理，UV 不需改动。代价：动画贴图冻结在读出那一刻；光影包的法线/高光贴图对不上（步骤 3 时必须改成合并图集）。
- **顶点格式只支持 `BLOCK` + `QUADS`**（区块网格就是这种格式）；遇到其他格式记日志跳过，本次运行未出现。
- **传输走文件轮询**，每次全量发送（只有 4 个区块段，约 11 KB）。
- **半透明层不重新排序**：远端网格按远端编译时的视角排序。
- 远端仍在渲染完整画面（像素读回也仍在进行），因为原型依赖远端的正常渲染流程来触发区块编译；"远端不绘制"本轮未实现。

## 2. 实验结果

### 2.1 E1 共享显存

命令（工作目录为 `tools/` 所在临时目录，类路径为 Minecraft LWJGL 3.3.3 的 lwjgl/glfw/opengl jar 及其 `natives-windows` jar，加 `lwjgl-vulkan-3.3.3.jar`）：

```
javac -cp "<cp>" -d . SharedGpu.java
java -Dorg.lwjgl.system.stackSize=4096 -cp "<cp>" SharedGpu
```

完整输出：`tools/sharedgpu-run.log`。结果（第二次运行；第一次结果一致）：

| 项目 | 值 |
|---|---|
| GL 渲染器 | `NVIDIA GeForce RTX 3060 Laptop GPU/PCIe/SSE2`，`4.5.0 NVIDIA 591.44`（两个进程都是独显） |
| `GL_EXT_memory_object` / `_win32` / `GL_EXT_semaphore` / `_win32` | 全部 true |
| Vulkan 设备匹配 | GL deviceUUID `a1a49497…5e16` 与 Vulkan NVIDIA 设备一致；Intel 核显被排除 |
| 跨进程校验 | 120 帧，正确 120，错误 0 |
| 生产者显卡内拷贝到共享缓冲（GPU 计时） | P50 0.281 ms，P95 0.317 ms |
| 消费者从共享缓冲上传到纹理（GPU 计时） | P50 0.046 ms，P95 0.048 ms |
| 一帧往返（生产者绘制+拷贝+发信号 → 消费者等待+上传+读 1 像素+经管道回复） | P50 1.52 ms，P95 1.76 ms，最大 12.4 ms |
| 对照：同步读回 1080p 颜色到 CPU 内存 | P50 0.78 ms，P95 1.07 ms |

说明：

- 对照项是单独测的纯读回，比 `gpu-composite` 报告中游戏内约 7.6 ms 的读回小得多。游戏内的数字包含深度、真实场景的 GPU 排队和等待；本程序的场景只是清屏。**所以这里不能得出"游戏内节省多少毫秒"**，那要在替换像素合成后实测（步骤 5 后半，未做）。
- 能确定的是：数据可以不经过 CPU 和共享文件在两个 Minecraft 进程之间传递，同步可用信号量完成。
- 往返时间中包含 Java 管道通信，不代表最终方案的延迟。

### 2.2 E2 Mod 扫描

命令：`python tools/modscan.py <loom mappings.tiny> mmlf/mods/*.jar`，结果 `tools/modscan-result.json`。

| Mod | 类数 | 直接 GL | GL 状态类 | 原版缓冲 | 渲染器 | 自定义 RenderType | 着色器 |
|---|---|---|---|---|---|---|---|
| Farmer's Delight（NeoForge） | 292 | 0 | 2（`CookingPotScreen`、`HUDOverlays`） | 9 | 12 | 2 | 0 |
| Tom's Simple Storage（Fabric） | 258 | 0 | 5（全部是界面/EMI 相关） | 1 | 0 | 3 | 0 |
| Macaw's Doors（Fabric） | 14 | 0 | 0 | 0 | 0 | 1 | 0 |
| Macaw's Trapdoors（NeoForge） | 7 | 0 | 0 | 0 | 0 | 0 | 0 |

解读：

- 门、活板门是纯 JSON 模型（1134/183 个方块模型），完全走区块网格，即几何层路径 1。
- Farmer's Delight 的 12 个渲染器类（灶台、砧板、煎锅上的物品等）通过原版缓冲绘制，属于手册的"动态几何"，可在 `MultiBufferSource` 层截取（步骤 2，未做）。
- 所有 GL 状态调用都在 GUI 界面，GUI 继续走像素路径。
- **局限**：只有 4 个 Mod。已知重度直接调 GL 的类型（Create/Flywheel、光影、小地图、部分科技 Mod 的自定义渲染）不在样本中。要回答"生态中有多大比例"需要扫描一批热门 Mod（工具已就绪，需要下载 jar）。

### 2.3 G1/G2 几何层（真实 Minecraft）

构建：`./gradlew.bat :fabric-bridge:compileJava :neoforge-bridge:compileJava` 编译成功（只有已存在的 mixin 映射警告）。
运行：临时脚本 `run-experiment.ps1`（设置 `MMLF_EXPERIMENTS`，调用 `scripts/run-demo.ps1 -SelfTest -Primary fabric -LogLevel INFO`，等全部自测完成后结束进程，每秒 `nvidia-smi` 采样）。

| 运行 | 开关 | 会话 | 自测 |
|---|---|---|---|
| C0（对照，前一轮报告） | 无 | `runtime/session-1693592014902812274` | 全部通过 |
| G1 | `geometry` | `runtime/session-2963641515378269672` | **全部通过**（相机、碰撞、GUI 三项、生存放置/破坏掉落、红石 6 个阶段、邻居、容器、边界 3 个阶段） |
| G2 | `geometry,thin_world,on_demand,small_view` | `runtime/session-874301078858890167` | 除 `BOUNDARY phase=unloaded objects=5 passed=false` 外全部通过（含生存掉落、GUI、红石重载） |

画面（`media/`，近端截图，远端世界像素层已关闭，远端方块全部来自几何层）：

| 场景 | 像素合成（C0） | 几何层（G1） |
|---|---|---|
| 正面 | `C0-pixel-core.png` | `G1-geometry-core.png` |
| 转头中（第 125 tick） | `C0-pixel-turning.png` | `G1-geometry-turning.png` |
| 远端活板门被交互打开后 | — | `G1-geometry-trapdoor-open.png` |
| 远端虚空世界 + 视距 2 | — | `G2-geometry-thin-core.png` |

人工对比：远端的橙色测试方块、Macaw's 活板门、Farmer's Delight 炉灶与厨锅、原版活板门等在位置、贴图、光照、遮挡上与像素合成一致。差别有三处：（1）远端的**粒子**（厨锅蒸汽）消失，因为粒子不在区块网格里；（2）炉灶火焰贴图静止（图集只读一次）；（3）手臂皮肤不同，那是两次运行随机的玩家皮肤，与本实验无关。G2 中远端是没有地板的虚空世界，远端方块的光照和环境光遮蔽在截图中看不出差异。

几何流与绘制指标（G1，日志 `experiment/geometry_*`）：

| 项目 | 值 |
|---|---|
| 远端网格 | 4 个区块段，340–408 个顶点，11–13 KB/次 |
| 远端图集 | 1024×1024，4 MB，读出 10 ms（一次性） |
| 远端导出（序列化+写文件） | P50 1.6 ms，P95 2.7 ms |
| 近端解析+重建顶点缓冲 | P50 0.38 ms，P95 0.69 ms |
| 导出 → 近端上传 | P50 77 ms，P95 133 ms（受 100 ms 轮询和文件时间戳精度限制） |
| 近端绘制远端网格（CPU 提交耗时） | 稳定后每帧 16–31 µs，每帧 5 次绘制调用 |
| 近端帧率 | 67.1 fps（C0 65.8；上限 60 并有波动，等价） |

资源对比（整局平均，跳过前 2 个 10 s 窗口，`tools/summary.py`）：

| 运行 | 远端 fps | 远端 CPU（单核 %） | 远端 RSS MB | 远端堆 MB | 远端加载区块 | 远端 MSPT | 近端 CPU | GPU 占用 % |
|---|---|---|---|---|---|---|---|---|
| C0 | 130.3 | 130.2 | 1716 | 702 | 2452 | 3.4 | 102.6 | 23.8 |
| G1 | 129.5 | 116.5 | 1719 | 626 | 2463 | 3.6 | 89.3 | 24.7 |
| G2 | 47.9 | 64.7 | 1602 | 523 | 961–1490 | 2.7 | 75.8 | 22.1 |

- G1 资源与 C0 持平：符合预期，因为原型中远端仍完整渲染并读回。近端 CPU 略降，可能因为跳过了远端世界层的重投影合成，但只有一次运行，可能是噪声。
- G2 的下降主要来自空闲降帧（上一轮已测）和更小的视距；远端 CPU 降约一半，RSS 降约 7%。

## 3. 发现的问题

### 3.1 双显卡机器必须按 UUID 选设备
本机 Vulkan 枚举出 NVIDIA 与 Intel 两个设备。若 Vulkan 选错设备，导入会失败或跨适配器拷贝。E1 用 `GL_DEVICE_UUID_EXT` 与 `VkPhysicalDeviceIDProperties.deviceUUID` 匹配解决。另外注意：两个 Minecraft 进程必须都运行在同一块独显上。本机 `D:\jdk21\bin\java.exe` 在 Windows 图形设置中为"高性能"，所以两个进程都在独显上；在其他机器上需要检查。

### 3.2 LWJGL 栈空间不足
`new VkInstance(...)` 枚举设备扩展时抛出 `OutOfMemoryError: Out of stack space`（默认 64 KB MemoryStack）。加 `-Dorg.lwjgl.system.stackSize=4096` 解决。集成进 Mod 时不能改 Minecraft 的全局参数，应改用堆分配（`MemoryUtil`）枚举扩展。

### 3.3 边界一致性缺陷再次出现（手册 §5.4，预期之内）
G2 的远端视距（2）比近端小。边界自测"玩家走远后，远端区块卸载，近端边界对象应清零"一项失败：`objects=5`。重新加载后恢复正常（`reloaded` 通过）。这与上一轮报告中的现象同源，证实**拆分架构下两端视距不同会稳定触发此缺陷**，手册步骤 0 必须先做。G1（两端视距相同）中该项通过。

### 3.4 远端加载区块的下限来自玩家票据
`small_view` 把远端渲染距离设为 2 后，加载区块是 961 = 31×31，并非预想的几十个。原因：玩家所在区块的票据会按等级梯度带出视距 + 13 圈的区块（视距 12 时 2601 = 51×51，同一规律）。玩家走动时临时升到 1490。要把远端降到"只加载对象所在区块"，必须让远端玩家**不产生区块票据**（例如旁观者模式不生成区块，或不把玩家加入区块追踪），并按对象所在区块加 `ForcedChunk`/自定义票据。这是手册 §5.1 的修正：视距只是一部分，玩家票据才是主要杠杆。

### 3.5 原型自身的不足
- 导出触发过多：远端每次有区块段编译就导出一次，即使编译的是不含远端方块的空段，内容未变也全量重发（57 次导出中多数内容相同）。应按内容哈希去重、只发送变化的段。
- 变化延迟：未直接测量端到端值。按实测分段估算，方块变化到近端显示约 100–350 ms（远端编译 + 最多 250 ms 导出节流 + 导出到上传 P50 77 ms）；这个量级在门开关时肉眼可见。改为消息通知 + 共享内存后应降到 1–2 帧，**未实测**。
- 粒子、方块实体渲染、实体、动画贴图均不在区块网格中，本原型不处理。

## 4. 对手册的影响

| 手册条目 | 原状态 | 本轮后 |
|---|---|---|
| §6.1 路径 1 几何层 | 待原型 | 区块网格部分已有可运行原型（仅 Fabric 近端 + NeoForge 远端、原版着色器） |
| §6.4 资源清单 | 设计 | 原型用"整张远端图集作为独立纹理"代替 sprite 合并；接光影前必须改为合并 |
| §6.7 共享显存 | 待验证 | 本机驱动可用，跨进程按名字导入与信号量同步验证通过；未接入 Minecraft |
| §5.1 远端视距 | 待验证 | 修正：主要杠杆是玩家票据，不是视距 |
| §5.4 边界一致性 | 已发现缺陷 | 再次复现，确认为拆分架构的前置条件 |
| §10 直接调 GL 的覆盖面 | 无数据 | 4 个演示 Mod 为 0；需要更大样本 |

## 5. 尚未完成、限制与下一步

未执行（不得视为通过）：

- **步骤 2 动态几何**（方块实体渲染器、实体、粒子）：未做。
- **步骤 3 光影**：近端未安装 Sodium/Iris，未测试。这是手册所列最大风险，至今没有数据。
- **步骤 4 Create/Flywheel**：未安装，未测试。
- **步骤 5 后半**：共享显存未接入 Minecraft，像素合成的读回未替换，游戏内收益未测。
- **步骤 6**：远端玩家不产生票据、按对象加票据、掉落物交给近端：未做（只做了视距实验）。
- **步骤 7** lockstep 去程多 1 tick：未查。
- **步骤 0** 边界一致性修复：未做。
- NeoForge 作为近端的几何层：未运行（`renderSectionLayer` 签名两端相同，但未实测）。
- 每个配置只跑了 1 次，资源数字没有重复性统计。

建议的下一步（按信息价值排序）：

1. **步骤 3 的最小验证**：近端装 Sodium + Iris，看远端几何能否被注入并着色。这决定"光影兼容"能否兑现，应在继续投入几何层之前做。
2. 步骤 2：截取 Farmer's Delight 炉灶上物品的方块实体渲染（`MultiBufferSource` 层），验证动态几何年龄 ≤ 1 帧。
3. 扩大 E2 扫描样本（例如 Modrinth 1.21.1 下载量前 100 的内容 Mod），得到直接调 GL 的比例。
4. 步骤 0（边界一致性）与步骤 6（远端玩家无票据），二者都与现有架构兼容。

## 6. 产物位置

- 报告与证据：`docs/ai/result/split-runtime-experiments/`（`media/` 截图，`tools/` 实验程序、扫描结果、共享显存运行日志）。
- 代码：`bridge-common/src/main/java/dev/mmlf/bridge/render/GeometryLayer.java`、`mixin/LevelRendererMixin.java`、`mixin/SectionCompilerMixin.java`（新增注入）、`Experiments.java`（`geometry`、`small_view`）、`BridgeCore.java`、`DemoScene.java`、`mmlf.bridge.mixins.json`。按代码，所有新逻辑都在开关判断之后；但**本轮没有在改动后重跑全部开关关闭的对照**，"关闭时行为不变"未经运行验证。
- 会话日志：`runtime/session-2963641515378269672`（G1）、`runtime/session-874301078858890167`（G2）。
