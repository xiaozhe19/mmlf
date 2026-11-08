# 跨端取物与原生放置实现报告

中键接入统一射线，按最近命中与归属路由，不再使用主端背后的石质平台作为目标。所属 Runtime 调用原生 getCloneItemStack，原生 ItemStack 留在所属 JVM，最多保留 256 个会话引用。

主端本地取物正常放入选中槽位；远端物品以明确标注原 Runtime 与名称的纸张代理呈现。代理不伪装为原生图标，物品组件也不翻译到主端。使用时由引用将原生物品放置路由回所属 Runtime，在其原生服务器执行。基础原版平台上的远端放置已验证；跨所有权 Mod 对象上的交互仍会拒绝。

问题与处理：原中键完全没有接入跨端射线，因此可能取得后方 stone；新增 pick 动作并沿现有归属仲裁。主端不存在远端 Mod 的注册项，因此采用带清晰标签的代理及保留原生栈的引用，避免复制注册远端物品。引用失效或所属端离线时记录 WARN/拒绝。

验证：RemoteItemRefTest 验证引用编码、归属和非法引用。真实会话日志记录 NeoForge native_picked=mcwtrpdoors:oak_four_panel_trapdoor、Fabric proxy_selected、NeoForge native_placement（2,100,1）成功。对应碰撞自测 shapes=1，dx≈0.2，passed=true。未宣称完整跨端物品栏、原生图标、组件通用序列化、跨端合成或生存消耗兼容已完成。

最新视觉扩展：纸张只保留为内部代理载体。手持模型和快捷栏显示已改为所属 Runtime 原生渲染的像素层，实机图像验证见 [原生渲染层](../render-layers/README.md)。前文的占位图标状态是早期阶段记录。
