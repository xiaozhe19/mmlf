# 原生破坏粒子实现报告

此前跨端破坏直接调用原生服务器逻辑，缺少本地玩家通常会做的客户端效果生成。现在在成功破坏后，在所属端用被破坏方块的原生状态 ID 调用 levelEvent(2001)，由原生 ParticleEngine 生成、模拟并渲染粒子，再随真实世界颜色/深度帧合成进主窗口。

没有在 Fabric 重新实现 NeoForge 粒子纹理或绘制算法。INFO 日志记录 particles/native_break 的所属端、方块及位置；协议和帧细节在 DEBUG 中关联。

验证：完整构建通过。实机日志确认 NeoForge 原生 mcwtrpdoors:oak_four_panel_trapdoor 破坏事件；原生截图 ../render-layers/mmlf-particle-demo.png 显示主窗口中的木质碎片以及原生手持活板门。此结果只覆盖基本方块破坏效果，不代表所有实体/网络粒子、透明粒子排序或任意 Mod 的特殊客户端行为均已验收。
