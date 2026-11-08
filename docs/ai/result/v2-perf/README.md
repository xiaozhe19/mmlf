# v2 工作流 D：性能分析（进行中：测量工具已就绪；本轮没有任何新的性能数据）

日期：2026-11-05。需求：`docs/manual/Single JVM Two Runtime v2.md` 第五节；实施计划 M4。

**状态**：GPU 被训练任务占用，本轮没有运行任何性能测试。下文"已有数据"一节只引用 v1 报告中的单轮数据，**不能**作为 v2 的结论。

## 一、实现了什么

### 新增与补齐的指标（全部写入会话 JSONL）

| 指标 | 位置 | 说明 |
|---|---|---|
| 帧时间 p50/p95/p99 | `metrics/runtime.frameTimeP50/P95/P99Ms` | 每个 Runtime 世界帧之间的间隔 |
| GC 次数、耗时 | `metrics/runtime.gcCollections/gcMillis` | 10 s 窗口内的增量。单 JVM 中为 JVM 级，两端共享 |
| 端到端延迟 p50/p95/p99 | `metrics/frame_sync.endToEndP*Ms` | 近端姿态发布 → 合成，见 `v2-unified-render` |
| 重复帧、过期拒绝、落后姿态数 | `metrics/frame_sync` | 同上 |
| 已有指标 | `rssMb`、`heapUsedMb`、`cpuOneCorePercent`、`worldFramesPerSecond`、`messageRttMs`；`metrics/remote_frame` 帧龄；`metrics/capture_latency` | 保持不变 |

未做：用 `GL_TIME_ELAPSED` 计时 GPU 传输和合成。这需要 GPU，并且必须先确认查询本身不会引入同步等待。

### 测量流程（`scripts/perf/`）

- `perf_run.py`：
  - 5 种配置：Fabric Solo、NeoForge Solo、双进程（`--backend process`）、单 JVM `file` 通道、单 JVM `direct` 通道 + `renderOwnership=safe`。
  - 每种 N 轮（默认 3），**交替**执行（A B C A B C…），避免温度或后台负载的漂移集中落在某一种配置上。
  - 所有运行使用同一个演示世界、自测脚本和 `probe` 实验（静止阶段 + 转头阶段）、`baseline` 组合以及相同时长。
- `perf_summary.py`：
  - 每轮第一个指标窗口之后 60 s 内为冷启动阶段，之后为稳定阶段；先取每轮稳定阶段的中位数，再计算各轮的均值、最小、最大和标准差。
  - 原则：差异小于同一配置的轮间波动，就不算差异。

工具验证：用一个已有的 v1 会话（`runtime/session-7848560090822461834`）构造索引并运行 `perf_summary.py`，能正确区分冷启动/稳定阶段并输出各指标。`capture_latency` 的字段名 `p50Ms` 是在这次验证中发现并修正的。

## 二、已有数据（v1，仅作参照）

见 `../single-jvm/README.md` 第三节：

- 单 JVM 与双进程 RSS 合计 4142 MB / 4237 MB，每种只有 1 轮；
- 消息 RTT 0.5–0.7 ms / 0.74–0.75 ms。

v1 报告已经说明这些只能当作量级参考。

## 三、下一步（GPU 空闲后）

```
python scripts/perf/perf_run.py --repeats 3 --seconds 300 --primary fabric
python scripts/perf/perf_summary.py mmlf/compat/results/perf-<时间>.json
```

先跑 `--configs injvm-file,injvm-optimized`，为 B3 的默认值决策提供依据；再跑完整的 5 种配置。全部 5 种 × 3 轮 × 约 6 分钟，约需 1.5 小时。

## 四、文件

- 新增：`scripts/perf/perf_run.py`、`scripts/perf/perf_summary.py`
- 修改：`BridgeCore.java`（指标）
