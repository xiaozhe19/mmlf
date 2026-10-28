# MMLF — Minecraft 1.21.1 双端原生融合 Demo

Fabric 和 NeoForge 分别在独立 JVM 中运行原生 Mod；主窗口通过真实颜色/深度帧合成显示两端内容。已实际验证测试方块状态变化、NeoForge 到 Fabric 的反向事件，以及 NeoForge Macaw's 活板门原生打开。详细证据与限制见 [集成报告](docs/result/integration/README.md)。

## 启动

需要 64 位 JDK 21 和可用的 OpenGL GPU。首次运行会下载官方 Minecraft 开发依赖。

```powershell
# 可选：下载并校验固定版本第三方演示 Mod
./scripts/fetch-demo-mods.ps1
# 手动测试：自动创建独立世界，隐藏后台 NeoForge 窗口
./scripts/run-demo.ps1
# 自动转向与跨端交互测试
./scripts/run-demo.ps1 -SelfTest
```

在主窗口用鼠标瞄准橙色方块或活板门，空手右键；蓝色方块属于 Fabric。右键橙色测试方块也会通过反向事件改变蓝色方块。场景布置见 `config/demo-scene.json`，统一 Mod 目录为 `mmlf/mods/`。游戏目录在 `runtime/session-*/`，每次启动独立生成。

## 构建与状态

`./gradlew.bat build` 编译全部模块并运行自动化测试。各 Bridge 和测试 Mod JAR 在各模块 `build/libs/` 中。

这是开发者实验 Demo，尚不是单 Bootstrap JAR 安装产品。当前显示采用完成帧对应的相机，存在输入到画面的延迟。最后修复了读取忙槽位时直接丢弃远端层造成的闪烁，当前由用户手动复验。透明物体、复杂渲染器、跨端碰撞和物品系统不保证兼容；自动化测试通过不等同于这些能力完成。

## 日志与远端取物

当前默认 DEBUG：`./scripts/run-demo.ps1 -LogLevel DEBUG`，INFO 模式改用 `-LogLevel INFO`。最新会话位置在 `runtime/latest-session.txt`，双端分级日志在该会话 `logs/`，完整客户端输出为 `clients.log`。默认启动器独立后台运行；开发时可加 `-Foreground`。

中键现在走统一命中查询；远端物品以带名称和所属端标签的纸张代理进入主端快捷栏，放置仍在所属 Runtime 用原生 ItemStack 执行。代理不是原生图标，也不等于完整跨端物品栏兼容。已验证第三方活板门的取物和放置路径；详见 `docs/result/items/`。

## 原生物品外观与粒子

远端物品的内部身份仍通过代理引用管理，但主手和快捷栏的显示现已使用所属 Runtime 原生渲染的像素，不再直接显示纸张模型。基本破坏粒子也由所属端原生生成并随画面传输。实机截图与边界见 `docs/result/render-layers/` 和 `docs/result/particles/`；此前提到“纸张代理图标”的文字属于早期阶段，不代表最新显示行为。
