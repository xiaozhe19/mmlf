# Runtime Manager 实现报告（进行中）

实现统一 Java 启动入口，创建隔离会话目录、启动 Coordinator 和两个 Gradle 开发客户端，生成会话令牌，并记录本次启动的进程树用于退出清理。统一目录 `mmlf/mods/` 的 JAR 根据 Fabric、NeoForge 和 Forge 标准元数据分类；歧义和未知文件输出提示，不自动分类，Forge 暂不启动。版本与依赖目前由各加载器校验。

问题与解决：Windows 上 Gradle 经由 cmd 启动子进程，仅结束 cmd 可能遗留 Minecraft 窗口；已增加对所启动进程的后代进程清理。两端启动和清理流程尚未完成实机端到端测试。当前没有自动创建世界、自动安装运行时、后台隐藏窗口、完整性能采样或第三方 Mod 验收。

后台 `:launcher:build` 与 Mod 元数据分类测试通过。不要把这视为普通用户一键可用的融合 Demo。

为避免两个独立 Gradle 进程争用 Loom 缓存锁，改为单次并行调度；主端断开或启动十分钟未注册时触发会话清理。`--dry-run` 成功列出双端任务，但没有执行客户端或验证进程清理。

最新实机验证、修复结果和限制见 [集成报告](../integration/README.md)。此前的失败记录保留为排错历史，不代表最新状态。

后续改进：默认脚本构建独立发行运行库后通过 Start-Process Hidden 启动 Java 管理器，客户端 stdout/stderr 写入会话 clients.log，不依赖工具终端输出管道；已观察到脚本返回后 PID 仍独立运行。runtime/latest-session.txt 指向最新会话；正常退出会写 exit-status.json，异常强制终止可能没有该文件。独立启动是否解决之前的未知退出尚需长期观察，不能写为根因已证实。
