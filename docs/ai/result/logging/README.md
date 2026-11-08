# 分级日志实现报告

默认 DEBUG，可通过 `scripts/run-demo.ps1 -LogLevel INFO` 切换。两端和管理器分别写入会话目录的 `logs/fabric.jsonl`、`logs/neoforge.jsonl`、`logs/manager.jsonl`。每条记录包含 UTC 时间、级别、Runtime、模块、事件、线程与结构化字段；actionId/messageId 可关联跨端路径。

INFO：日志配置、连接注册、Runtime 注册、交互执行结果、物品选取和放置、进程启动/退出。DEBUG：协议收发、输入请求、相机参数、碰撞修订、音效事件、帧采集/读取/复用/CPU 合成耗时。高频帧/姿态/心跳明细按秒采样，避免输出每个像素或每个帧的完整内容。WARN/ERROR 记录拒绝和故障。会话令牌不进入协议跟踪字段，敏感键被替换为 [REDACTED]。

实现采用独立 JSONL 级别过滤，避免依赖两个游戏加载器各自不同的日志过滤配置。控制台仍会被 Minecraft 自己的日志包装；以 JSONL 的 level 字段为准。每条文件记录即时刷新，增强异常退出前最后事件的保留能力；无法保证进程被强制终止时一定产生退出记录。

验证：MmlfLogTest 验证 INFO 过滤 DEBUG、两端独立文件和敏感字段脱敏；完整 Gradle 构建通过。独立启动的真实 Fabric/NeoForge 会话中已读取到 INFO native_picked/proxy_selected/native_placement 和 DEBUG camera/protocol/render/collision 记录。旧有 Minecraft/第三方库自身输出不由本模块重新分类。

新增 render.hand、render.icon、particles 模块的 INFO 就绪/结果日志和 DEBUG 帧细节；新增 metrics/runtime 的 CPU、RSS、堆内存、世界 FPS 与 RTT，metrics/coordinator 的消息吞吐，camera/capture_error 的姿态误差和经过双方时钟偏移校准的 captureToCompositeEstimateMs（CPU 合成时延估计，不是屏幕显示时延）。最新指标已编译，当前正在运行的先前会话未包含所有新增指标。
