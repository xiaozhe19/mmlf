# 兼容性矩阵（由 `scripts/compat/matrix.py` 生成，勿手改）

环境：Minecraft 1.21.1，Fabric Loader 0.16.10 + Fabric API 0.115.4，NeoForge 21.1.265，Temurin JDK 21，开发路径（Gradle runClient），单 JVM（injvm）。

| Mod | Loader | 版本 | 许可 | 组合 | 结论 | 运行记录 | 原因 / 证据 |
|---|---|---|---|---|---|---|---|
| sodium | fabric | mc1.21.1-0.8.13-fabric | LicenseRef-Polyform-Shield-1.0.0 | sodium | 未测 | — | — |
| sodium | neoforge | mc1.21.1-0.8.13-neoforge | LicenseRef-Polyform-Shield-1.0.0 | sodium | 未测 | — | — |
| embeddium | neoforge | 1.0.15+mc1.21.1 | LGPL-3.0-only | embeddium | 未测 | — | — |
| iris | fabric | 1.8.14-beta.1+1.21.1-fabric | LGPL-3.0-only | iris | 未测 | — | — |
| iris | neoforge | 1.8.14-beta.1+1.21.1-neoforge | LGPL-3.0-only | iris | 未测 | — | — |
| carpet | fabric | 1.4.147 | MIT | carpet | 未测 | — | — |
| litematica | fabric | 0.19.61 | LGPL-3.0-only | malilib-tools | 未测 | — | — |
| tweakeroo | fabric | 0.21.61 | LGPL-3.0-only | malilib-tools | 未测 | — | — |
| minihud | fabric | 0.32.60 | LGPL-3.0-only | malilib-tools | 未测 | — | — |
| flashback | fabric | 0.39.10 | LicenseRef-All-Rights-Reserved | flashback | 未测 | — | — |
| axiom | fabric | — | — | — | 不测 | — | All-Rights-Reserved 许可，且无 NeoForge 1.21.1 版本；本轮只记录不测试 |
| axiom | neoforge | — | — | — | 不测 | — | All-Rights-Reserved 许可，且无 NeoForge 1.21.1 版本；本轮只记录不测试 |
| lithium | fabric | mc1.21.1-0.15.4-fabric | LGPL-3.0-only | lithium | 未测 | — | — |
| lithium | neoforge | mc1.21.1-0.15.4-neoforge | LGPL-3.0-only | lithium | 未测 | — | — |
| terralith | fabric | 2.6.2 | LicenseRef-Stardust-Labs-License | — | 版本不匹配（未测） | — | fabricloader >=0.18，项目为 0.16.10 |
| terralith | neoforge | 2.6.2 | LicenseRef-Stardust-Labs-License | worldgen | 未测 | — | — |
| biomes-o-plenty | fabric | 21.1.0.14 | LicenseRef-All-Rights-Reserved | worldgen | 未测 | — | — |
| biomes-o-plenty | neoforge | 21.1.0.14 | LicenseRef-All-Rights-Reserved | worldgen | 未测 | — | — |
| naturalist | fabric | 2.0.7+1.21.1-fabric | LicenseRef-Custom | — | 版本不匹配（未测） | — | fabricloader >=0.19.3，项目为 0.16.10；fabric-api >=0.116.4+1.21.1，项目为 0.115.4 |
| naturalist | neoforge | 2.0.7+1.21.1-neoforge | LicenseRef-Custom | mobs | 未测 | — | — |

说明：L1 由 `run_compat.py` 依据日志自动判定（两端 bridge 初始化、进入世界、无崩溃报告、退出码 0）；L2–L4 只来自 `mmlf/compat/manual.json` 的人工记录。
