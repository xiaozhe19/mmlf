# v2 工作流 C：真实 Mod 兼容性工程（进行中：测试体系已建立，Mod 已核实并下载校验；尚未运行任何真实游戏测试）

日期：2026-11-05。需求：`docs/manual/Single JVM Two Runtime v2.md` 第四节；实施计划 M3。

**状态**：用户 GPU 正在训练模型，本轮没有启动游戏。矩阵中没有任何 Mod 被标记为兼容，全部是"未测"、"不测"或"版本不匹配"。本轮只覆盖用户选定的三类：渲染/Shader、工具/原版机制、世界生成/生物。机械与动画（Create）、物品与存储（AE2、Sophisticated Storage）**本轮不测**。

## 一、实现了什么

### 测试流水线（`scripts/compat/`）

| 步骤 | 脚本 | 作用 |
|---|---|---|
| 1 核实 | `resolve_mods.py` | 通过 Modrinth API 为每个候选 Mod 和每个 Loader 找出 1.21.1 的最新正式版（没有正式版时取最新版）。记录主文件、sha512、许可、客户端/服务端属性，并递归解析必需依赖。输出 `mmlf/compat/mods.json` |
| 2 下载 | `fetch_mods.py` | 只从 `https://cdn.modrinth.com` 下载，逐个比对 sha512。读取 jar 内 `fabric.mod.json`/`neoforge.mods.toml` 声明的 Loader、Minecraft、Java 要求，与 `gradle.properties` 比较，不满足的标为 `incompatible_with_dev_environment`。Fabric API 由开发环境提供，不下载。为每个 Mod 记录依赖闭包，输出 `mmlf/compat/fetched.json` |
| 3 组合 | `mmlf/compat/profiles.json` | 每个组合 = 演示 Mod + 列出的 Mod（含依赖），按 Loader 分开 |
| 4 运行 | `run_compat.py --profile X --primary fabric\|neoforge [--solo]` | 组合暂存到 `mmlf/compat/stage/<组合>`，以 `MMLF_MODS_DIR`、`MMLF_SELF_TEST`、`MMLF_TEST_SECONDS` 运行 `gradlew :<primary>-bridge:runClient`，解析控制台和会话 JSONL，写出 `mmlf/compat/results/<组合>-<近端>-<时间>/result.json` |
| 5 矩阵 | `matrix.py` | 合并 2 和 4 的结果（以及人工记录 `mmlf/compat/manual.json`），生成 `matrix.md` 和 `matrix.json` |

工程支持：

- `MMLF_MODS_DIR`：两个 bridge 的 `stageHostMods` 和 `DevRunSpecs.prepareGameDirectory` 都改用这个来源，默认仍是 `mmlf/mods`。
- 暂存记录：`SessionHost.stageMods` 在目标目录写 `.mmlf-staged`，下次暂存前先删除上次放入的 jar。上一个组合的 Mod 不会残留到下一次运行，手工放入的 jar 保留。有单元测试 `StageModsTest`。
- `MMLF_SOLO=true`：近端托管会话，但不启动另一端，并直接打开演示世界。用作同一世界、同一组 Mod 的单 Loader 原生基线（C3 和 D 都需要）。
- `.gitignore` 新增 `mmlf/compat/jars/`、`stage/`、`results/`，第三方 jar 不入库。

### 等级判定规则（不得以启动成功代替兼容）

- **L1（自动）**：退出码 0；两端都输出 `MMLF bridge initialized`；远端发出 `world_ready`（Solo 只检查近端）；会话中没有崩溃报告。
- **L2/L3/L4**：只来自人工记录 `mmlf/compat/manual.json`，例如 `{"project":"carpet","loader":"fabric","level":"L2","evidence":"..."}`。脚本不推断这三级。各 Mod 计划检查的项目：
  - Sodium/Embeddium：日志中渲染器已切换；远端无重复绘制（见 `v2-unified-render` 中 `SodiumBlockRendererMixin`）；截图。
  - Iris：加载一个光影包后近端画面；远端在光影下的合成。
  - Carpet：在 Fabric 端执行 `/carpet` 和 `/tick` 类命令。
  - Litematica/MiniHUD/Tweakeroo：HUD 显示，以及 Fabric 作为远端时的 GUI 和按键路由。
  - Flashback：录制和回放。
  - Lithium：世界 tick 正常，MSPT 与基线对比。
  - Terralith/BOP：远端世界的生物群系 ID。注意：演示世界是超平坦，需要另建普通世界才能检查。
  - Naturalist：生物生成与行为。

## 二、发现的问题

| # | 问题 | 依据 | 处理 |
|---|---|---|---|
| 1 | Terralith（Fabric 版，及其依赖 Lithostitched）要求 Fabric Loader ≥0.18 / ≥0.17；Naturalist（Fabric 版）要求 Loader ≥0.19.3、Fabric API ≥0.116.4。项目为 0.16.10 / 0.115.4 | jar 内 `fabric.mod.json` 的 `depends` | 标为"版本不匹配（未测）"。是否升级项目的 Fabric Loader/API，或改测旧版本，**待用户决定** |
| 2 | Axiom 采用 All-Rights-Reserved 许可，且没有 NeoForge 1.21.1 版本 | Modrinth 项目信息 | 只记录、不测试 |
| 3 | Sodium 替换区块网格器后，远端的归属过滤会失效 | 字节码审查（`ChunkBuilderMeshingTask` → `BlockRenderer.renderModel`） | 新增 `SodiumBlockRendererMixin`（见 `v2-unified-render`），**未实测** |
| 4 | 多数工具类 Mod 只有 Fabric 版（Carpet、malilib 系、Flashback） | Modrinth | 这些 Mod 只能放在 Fabric 端；两个方向的测试分别对应它在近端和在远端 |

许可说明：Sodium（Polyform Shield）、Flashback、BOP（ARR）、Naturalist（Custom）只在本机下载、用于测试，不入库、不分发。

## 三、实际执行与结果

| 层级 | 内容 | 结果 |
|---|---|---|
| 解析 | `python scripts/compat/resolve_mods.py` | 13 个候选、22 个"Mod × Loader"组合全部解析（结果见 `matrix.md`） |
| 下载与校验 | `python scripts/compat/fetch_mods.py` | 全部 sha512 一致；3 个版本不匹配、2 个跳过 |
| 组合解析（离线演练） | 对 `profiles.json` 每个组合调用 `run_compat.profile_jars` | 10 个组合都能解析出完整的 jar 集合，没有缺失依赖 |
| 自动化测试 | `StageModsTest` | 通过 |
| 真实游戏 | — | **未执行**（GPU 被占用） |

## 四、下一步（GPU 空闲后）

建议的执行顺序：先跑最有信息量的组合。

```
python scripts/compat/run_compat.py --profile baseline --primary fabric
python scripts/compat/run_compat.py --profile sodium   --primary fabric
python scripts/compat/run_compat.py --profile sodium   --primary neoforge
python scripts/compat/run_compat.py --profile sodium   --primary fabric --solo
...（iris、embeddium、lithium、carpet、malilib-tools、flashback、worldgen、mobs，每个两个方向，外加 solo）
python scripts/compat/matrix.py
```

每次失败都按需求 C4 归类：ClassLoader/Mixin/ModuleLayer、JVM 全局状态、原生库与图形、渲染合成、世界状态同步、交互路由、Mod 自身问题、依赖缺失、未知。

## 五、文件

- 新增：
  - `scripts/compat/resolve_mods.py`、`fetch_mods.py`、`run_compat.py`、`matrix.py`
  - `mmlf/compat/profiles.json`、`mods.json`、`fetched.json`
  - `launcher/src/test/.../StageModsTest.java`
  - 本目录 `matrix.md`、`matrix.json`
- 修改：
  - `SessionHost.java`（`modsSource`、暂存记录）
  - `DevRunSpecs.java`
  - 两个 bridge 的 `build.gradle`（`MMLF_MODS_DIR`）
  - `SessionBootstrap.java`、`DemoScene.java`（`MMLF_SOLO`）
  - `.gitignore`

## 补充：2026-11-09 baseline 实机运行（进行中）

- `python scripts/compat/run_compat.py --profile baseline --primary fabric`：首次在受限环境中因 Gradle 下载网络被拒绝而退出码 1，未启动游戏，记录在 `mmlf/compat/results/baseline-fabric-20261109-003053/`；使用本机缓存重新运行后退出码 0，脚本判定 L1=true，两个 bridge 初始化、两个 world ready，且输出 composite ready，无崩溃报告。结果为 `mmlf/compat/results/baseline-fabric-20261109-003129/result.json`，会话 `runtime/session-17569077456306659444`。
- L1 并未检查自测是否全部通过：21 条自测有 4 条失败，分别为 `BOUNDARY phase=unloaded`（`primary_never_unloaded`）、`BOUNDARY phase=reloaded`（`objects=15 expected=14`）、`REDSTONE phase=reloaded`（`comparator=0`）和 `REDSTONE phase=reloaded_off`（`comparator=-1`）。仅凭此轮尚不能判断后 3 项是回归还是波动，需复测和定位。
- 日志有未安装 Sodium 时 `@Pseudo` Mixin 的 `ClassNotFoundException` 标记，未产生 crash report；不能把脚本 L1 当成全部功能通过。真实画面、人工交互和 Mod 功能尚未验证。

### 范围澄清

用户确认：原版共有的红石、箱子等逻辑由近端处理；涉及 Mod 方块的跨端红石与容器联动保留。原版电路在近端原生运行，现有跨端桥主要支持 Mod 联动，不能据此删除。上述红石重载失败涉及 Mod 联动，仍需复测定位。

2026-11-09 首次 Sodium Fabric 运行在自测完成前因范围澄清被主动中断，不能计为通过或失败；后续重新运行。

### Sodium：Fabric 近端 / NeoForge 远端

- `python scripts/compat/run_compat.py --profile sodium --primary fabric`：退出码 0、L1=true，两个 bridge 与 world ready，composite ready，无崩溃报告。Sodium Fabric 和 NeoForge jar 均已暂存。结果：`mmlf/compat/results/sodium-fabric-20261109-004112/result.json`，会话：`runtime/session-1493778015679387097`。
- 已输出的 15 条自测全部 passed，包括 Mod 相关红石 on/off、邻居 on/off 和容器传输。边界仅到 away，未输出 unloaded/reloaded，不能视为通过；截图、人工画面及 Sodium 功能尚未核对。

### Sodium：NeoForge 近端 / Fabric 远端

- `python scripts/compat/run_compat.py --profile sodium --primary neoforge`：退出码 0、L1=true，两个 bridge 与 world ready，composite ready，无 crash report；15 条已输出自测全部通过，但没有到边界 unloaded/reloaded。日志包含 `ClassNotFoundException: cpw.mods.modlauncher.log.MLClassLoaderContextSelector` 标记；其影响尚未确认。结果：`mmlf/compat/results/sodium-neoforge-20261109-004530/result.json`，会话：`runtime/session-10017751881983650384`。截图和人工交互未验证。
- Fabric 单端对照在运行期间按用户要求中止，不能计为通过或失败。后续兼容性与性能运行均停止。
