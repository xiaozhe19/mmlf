"""Summarize repeated performance runs (an index written by perf_run.py).

  python scripts/perf/perf_summary.py mmlf/compat/results/perf-<time>.json [--warmup 60]

Per run, from its session's JSONL logs: the 10-second metrics windows after the warm-up (seconds after the first
window, default 60) are the stable phase; the windows before it are the cold phase. Per configuration: the mean over
runs of each run's stable median, with the spread between runs (min-max and standard deviation). A difference
smaller than the spread between runs of the same configuration is not a difference.

Metrics: metrics/runtime (rssMb, heapUsedMb, cpuOneCorePercent, gcCollections, gcMillis, worldFramesPerSecond,
frameTimeP50/P95/P99Ms, messageRttMs) per runtime; metrics/remote_frame (frame age p50/p95/p99) and metrics/frame_sync
(end-to-end p50/p95/p99, repeated, stale) on the primary; metrics/capture_latency on the background.
"""
import argparse, json, statistics, sys
from datetime import datetime
from pathlib import Path

RUNTIME = ["rssMb", "heapUsedMb", "cpuOneCorePercent", "gcCollections", "gcMillis", "worldFramesPerSecond", "frameTimeP50Ms", "frameTimeP95Ms", "frameTimeP99Ms", "messageRttMs"]
REMOTE = ["ageP50Ms", "ageP95Ms", "ageP99Ms"]
SYNC = ["endToEndP50Ms", "endToEndP95Ms", "endToEndP99Ms", "repeatedFrames", "staleRejected"]
CAPTURE = ["p50Ms", "p95Ms", "p99Ms"]

def events(session):
    out = []
    for f in (Path(session) / "logs").glob("*.jsonl"):
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            e["_t"] = datetime.fromisoformat(e["time"].replace("Z", "+00:00")).timestamp()
            out.append(e)
    return out

def run_stats(session, warmup):
    evs = [e for e in events(session) if e.get("module") == "metrics"]
    if not evs:
        return None
    start = min(e["_t"] for e in evs)
    stats = {}
    def collect(event, keys, label):
        for phase, keep in (("stable", lambda t: t - start >= warmup), ("cold", lambda t: t - start < warmup)):
            for e in evs:
                if e["event"] != event or not keep(e["_t"]):
                    continue
                rt = e["fields"].get("runtime") or e.get("runtime") if event not in ("runtime", "capture_latency") else e.get("runtime")
                for k in keys:
                    v = e["fields"].get(k)
                    if isinstance(v, (int, float)):
                        stats.setdefault((phase, f"{label}.{rt}.{k}"), []).append(v)
    collect("runtime", RUNTIME, "runtime")
    collect("remote_frame", REMOTE, "remote")
    collect("frame_sync", SYNC, "sync")
    collect("capture_latency", CAPTURE, "capture")
    return {key: statistics.median(vals) for key, vals in stats.items()}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("index")
    ap.add_argument("--warmup", type=float, default=60)
    args = ap.parse_args()
    index = json.loads(Path(args.index).read_text(encoding="utf-8"))
    per_config = {}
    for run in index:
        if not run.get("result"):
            print(f"{run['config']} #{run['repeat']}: no result (exit {run['exit']})", file=sys.stderr); continue
        result = json.loads(Path(run["result"]).read_text(encoding="utf-8"))
        if not result.get("session"):
            print(f"{run['config']} #{run['repeat']}: no session", file=sys.stderr); continue
        s = run_stats(result["session"], args.warmup)
        if s:
            per_config.setdefault(run["config"], []).append(s)
    rows = []
    for config, runs in per_config.items():
        keys = sorted({k for r in runs for k in r})
        for key in keys:
            values = [r[key] for r in runs if key in r]
            rows.append({"config": config, "phase": key[0], "metric": key[1], "runs": len(values), "mean": statistics.fmean(values),
                         "min": min(values), "max": max(values), "stdev": statistics.stdev(values) if len(values) > 1 else None})
    print("| 配置 | 阶段 | 指标 | 轮数 | 均值 | 最小 | 最大 | 标准差 |")
    print("|---|---|---|---|---|---|---|---|")
    for r in rows:
        sd = "—" if r["stdev"] is None else f"{r['stdev']:.2f}"
        print(f"| {r['config']} | {r['phase']} | {r['metric']} | {r['runs']} | {r['mean']:.2f} | {r['min']:.2f} | {r['max']:.2f} | {sd} |")
    Path(args.index).with_suffix(".summary.json").write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")

if __name__ == "__main__":
    main()
