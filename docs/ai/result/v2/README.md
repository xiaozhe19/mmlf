# MMLF v2 汇总（进行中：双向默认配置各完成一轮实机自测；画面、兼容性和发行验证未完成；不具备发布条件）

日期：2026-11-05。需求：`docs/manual/Single JVM Two Runtime v2.md`。各模块的详细报告：

| 工作流 | 报告 | 状态 |
|---|---|---|
| A 稳定性与隔离 | `../v2-jvm-stability/` | 代码完成；自动化测试通过；真实运行未验证 |
| B3 帧通道 | `../v2-frame-channel/` | 内存通道完成并通过测试；GPU 共享探针 idle 通过、current 失败；未接入 |
| B1/B2/B4/B5 统一渲染 | `../v2-unified-render/` | 指标、天空/天气归属、Sodium 归属过滤已写；未做画面验证 |
| C 兼容性 | `../v2-compat/`（`matrix.md`） | baseline Fabric 近端 L1 通过但存在自测失败；Sodium 等验证进行中 |
| D 性能 | `../v2-perf/` | 工具就绪；没有新数据 |
| E 发行路径 | `../v2-release-path/` | 代码完成；自动化测试通过；任何启动都未执行 |

**当前状态**：2026-11-05 双向默认配置各执行一轮实机自测；未做截图和人工画面交互核对。2026-11-09 GPU 共享探针重跑：idle 通过，current 失败。兼容性、性能和发行验证仍在推进，详见各模块报告。

## 1. v2 架构说明（相对 v1 的变化）

```
同一 JVM
├─ 近端（启动入口）：bridge ─ SessionBootstrap.adopt(RuntimeManager)
│   ├─ 开发：SessionBootstrap.hostIfRequested      发行：ReleaseSession.hostInJvm（新，默认）
│   ├─ RuntimeManager ─ InJvmBackend
│   │    ├─ ScopedProperties（新）：按线程组 / 上下文加载器区分的系统属性覆盖层
│   │    ├─ 阶段监视线程（新）：loader → bridge → game → world → stopping，按阶段超时、停滞上报
│   │    ├─ TrappingLoader（新）：Fabric Loader 自身的 System.exit → InJvmExitTrap
│   │    └─ finish：CAS 只执行一次，运行远端留下的清理回调（新）
│   ├─ Minecraft.destroy HEAD：先有序停止远端（新）；远端退出后检查 LWJGL 槽位（新）
│   └─ 合成：Frames → FrameFile（文件 | 内存，新）→ RemoteCompositor；PoseHistory 端到端指标（新）
└─ 远端：原生 Loader（Knot / BootstrapLauncher）
    ├─ 通过注册表上报阶段与清理回调（新）
    ├─ RenderOwnership：safe 模式下跳过天空 / 雨雪（新，默认关闭）
    └─ SodiumBlockRendererMixin：Sodium 网格器也按归属过滤（新）
```

v1 中跨 Loader 只传 JDK 类型的原则保持不变。新增的注册表键：`phase:`、`cleanup:`、`frames:`、`lwjgl:host/guestMissingTable`。

## 2. JVM 稳定性问题及修复记录

见 `v2-jvm-stability` 第二节。要点：

- 远端退出后链接仍在重连和投递 → 改为注册表清理回调；
- 宿主属性最长 180 s 被改写 → 改为 `ScopedProperties`；
- 加载中的远端无法被停止 → 停止检查前移；
- 近端关闭时远端可能仍在执行 GL 调用 → 有序停止；
- Fabric Loader 早期调用 `System.exit` 会结束整个 JVM → 改为陷阱。

NeoForge 早期的 `System.exit` 和原生崩溃仍会结束整个 JVM：**单 JVM 没有进程级故障隔离**。

## 3. 统一渲染的实现与数据流

见 `v2-unified-render` 和 `v2-frame-channel`。数据流：

- 远端读回（PBO）→ `Frames` 选择通道：
  - `file`（默认）：mmap + 文件锁 + CRC；
  - `direct`：注册表中的直接缓冲 + CAS，无 CRC。
- → 近端读取，按 `cameraSequence` 对应 `PoseHistory`，得到端到端延迟
- → `RemoteCompositor` GPU 深度合成（未改）。

## 4. 渲染优化前后的性能数据

**无**。本轮没有运行任何性能测试。测量工具见 `v2-perf`。

## 5. 真实 Mod 兼容性矩阵

见 `v2-compat/matrix.md`。20 行中：

- 15 行"未测"（已下载并校验，等待运行）；
- 3 行"版本不匹配"：Terralith、Lithostitched、Naturalist 的 Fabric 版要求 Fabric Loader ≥0.17–0.19.3，项目为 0.16.10；
- 2 行"不测"（Axiom）。

Create、AE2、Sophisticated Storage 按用户决定本轮未纳入。

## 6. 双向启动与交互测试结果

2026-11-05，在 GPU 短暂空闲时，两个方向各跑了 1 轮默认配置的自测，均退出码 0：
- 除已知的 `BOUNDARY phase=unloaded` 外，自测全部通过；
- 阶段完整；
- 属性跨层读取 0 次；
- 有序关闭耗时 683 / 466 ms；
- LWJGL 槽位校验正常。

详见 `v2-jvm-stability` 末节。

未执行的部分：
- `direct` 通道和 ownership `safe` 的组合；
- 截图与画面核对。

`ShareProbe` 修复后重跑：`idle` 通过 120 帧校验，`current` 在 `wglShareLists` 失败；尚不能接入，见 `v2-frame-channel` 末节。

## 7. 发行版启动路径验证结果

只有自动化测试（`ReleaseRemoteTest`）。模拟环境启动和真实启动器启动**均未执行**。

## 8. 修改、新增、删除的文件

| 类别 | 文件 |
|---|---|
| 新增（主代码） | `launcher/.../runtime/ScopedProperties.java`、`InJvmExitTrap.java`；`launcher/.../ReleaseRemote.java`；`common/.../PoseHistory.java`；`bridge-common/.../Frames.java`、`render/RenderOwnership.java`、`mixin/SodiumBlockRendererMixin.java` |
| 新增（测试） | `InJvmLifecycleTest`、`fake/FakeRuntime`、`ScopedPropertiesTest`、`ExitTrapTest`、`ReleaseRemoteTest`、`StageModsTest`、`FrameMemoryTest`、`PoseHistoryTest` |
| 新增（脚本 / 数据） | `scripts/compat/{resolve_mods,fetch_mods,run_compat,matrix}.py`；`scripts/perf/{perf_run,perf_summary}.py`；`mmlf/compat/{profiles,mods,fetched}.json`；`docs/ai/result/v2-frame-channel/tools/ShareProbe*.java` |
| 修改 | `InJvmBackend`、`RuntimeHandle`、`RuntimeManager`、`Manager`、`SessionHost`、`DevRunSpecs`；`FrameFile`；`BridgeCore`、`SessionBootstrap`、`ReleaseSession`、`InJvmLwjgl`、`DemoScene`、`GuiClient`、`GuiHost`、`RemoteCatalog`、`VisualLayers`；`mixin/MinecraftMixin`、`mixin/LevelRendererMixin`；`mmlf.bridge.mixins.json`；两个 bridge 的 `build.gradle`；`.gitignore` |
| 删除 | 无。旧 `Manager` 进程路径按需求保留，作为对照 |

## 9. 尚未解决的问题及严重程度

| 严重程度 | 问题 |
|---|---|
| **高** | 只有默认配置在两个方向各跑过 1 轮，均通过。`direct` 通道、ownership `safe`、Sodium 过滤、GPU 共享都没有实机运行过。`ScopedProperties` 只验证了 1 轮；若出问题，可用 `MMLF_INJVM_PROPERTIES=window` 回退 |
| **高** | 发行版单 JVM 路径（尤其生产版 NeoForge 作为远端）从未运行 |
| **高** | baseline 有一轮 L1 启动记录，但存在自测失败；其他 Mod 兼容性证据不足 |
| 中 | NeoForge 早期 `System.exit`、原生崩溃会结束整个 JVM（单 JVM 的固有限制） |
| 中 | `System.out/err` 双重包装；`java.io.tmpdir` 和 CWD 无法按 Runtime 区分 |
| 中 | 远端半透明层（蒸汽拖影）未处理；实体和方块实体的归属未做 |
| 中 | 远端类加载器的回收、同一 JVM 内重启远端：未验证、不支持 |
| 低 | 近端同步 `glTexSubImage2D` 尚未改为 PBO 异步上传；`RenderSchedule` 重构未做 |

## 10. v2 是否具备正式发布条件

**不具备。** 原因：

- 上表中的高风险项均未解决；
- 双向默认配置已有各一轮启动自测；画面融合和真实 Mod 兼容性最终验收仍无充分证据；
- 发行路径未经任何实际启动。

按需求，本次不宣布 v2 完成，不发布版本。

## 恢复测试时的建议顺序

1. 两个方向的默认配置自测（`MMLF_SELF_TEST=true MMLF_TEST_SECONDS=300 gradlew :<loader>-bridge:runClient`），确认 v2 的基础改动没有回归。
2. `ShareProbe` 探针。
3. `run_compat.py` 的 `baseline` 和 `sodium` 组合。
4. `perf_run.py --configs injvm-file,injvm-optimized`。
5. 发行路径的模拟环境启动。

## 2026-11-09 测试补充（按用户要求停止）

- baseline Fabric 近端：L1=true，但 21 条自测中 4 条失败，详见 `v2-compat`。
- Sodium 双方向各一轮：L1=true、各 15 条已输出自测通过；均未覆盖边界卸载和重载，未核对画面或人工交互。单端对照中止。
- ShareProbe：idle 120/120 通过，current 的 `wglShareLists` 失败；探针错误退出码已修复。未做性能对比，也没有据此接入 GPU 共享。
