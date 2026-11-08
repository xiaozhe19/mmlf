# 原生手持物与图标渲染实现报告

原生 ItemStack 保留在所属 Runtime；主端代理现在只承担内部身份。所属端把原生栈同步给本地玩家，由 Minecraft 的 ItemInHandRenderer 绘制第一人称手持模型。世界层采集后隔离透明手持层，再通过独立的文件映射和帧通知传给主端，主端不再绘制纸张模型，而是合成原生手持层。

快捷栏图标在所属端 32×32 framebuffer 中调用原生 GuiGraphics.renderItem 生成，传输像素后由主端动态纹理显示。没有复制远端模型/纹理文件，也没有在主端注册远端方块或物品。元数据代理仍是纸张类型；物品栏的视觉已替换为原生像素，因此不代表原生物品注册、物品组件翻译或完整物品栏同步已经实现。

问题与处理：此前身份代理直接进入主端渲染，所以显示纸张；新增 hand/icon 两条原生像素链路。隔离并恢复像素传输状态、投影矩阵与 framebuffer，避免污染原游戏的 GL 状态。主端缓存完整层以避免忙槽位丢图。

验证：完整 Gradle build 成功，PixelCompositorTest 验证预乘 Alpha 覆盖和未覆盖区域保持。真实 session-12376215062664784831 日志出现 NeoForge render.hand/native_export_ready、render.icon/native_export_ready，以及 Fabric native_composite_ready、native_icon_ready。游戏原生截图 mmlf-item-demo.png、mmlf-particle-demo.png 中可见真实活板门手持模型及其快捷栏图标。验证未使用 Computer Use。

当前限制：只有主手及当前持有物的原生渲染；图标以 32×32 和约 250ms 更新。未验收任意自定义 GUI、双手交互、复杂使用动画或所有光影包。仍有显示延迟和 CPU 传输开销。原始 Runtime 的资源包决定该层实际材质。

性能修正：手持层由整张 1280×720 传输改为仅传输非透明包围框（含 2px 边距），主端也只回读和更新该区域；远端世界颜色/深度采集限制为约 30 FPS，主窗口仍以 60 FPS 接收输入和显示，落后的远端帧通过深度重投影对齐当前相机。该改动已编译并通过自动化测试，仍需在最新实机运行中确认长期稳定性和主观流畅度。
