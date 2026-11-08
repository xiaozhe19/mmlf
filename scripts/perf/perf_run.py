"""Repeated performance runs of fixed configurations, for comparisons that are more than one sample.

  python scripts/perf/perf_run.py [--repeats 3] [--seconds 300] [--configs fabric-solo,injvm-file,...] [--primary fabric]

Every run is the same scene (the demo world, its self-test script and the `probe` experiment: a static phase and a
turning phase), the same mods (the `baseline` compatibility profile) and the same run length; only the configuration
changes. Runs go through scripts/compat/run_compat.py, so each leaves result.json and its session logs; summarize them
with scripts/perf/perf_summary.py. Runs alternate between configurations (A B C A B C ...) so drift over the session
(thermals, background load) does not land on one configuration.
"""
import argparse, json, os, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "scripts" / "compat" / "run_compat.py"

def configs(primary):
    other = "neoforge" if primary == "fabric" else "fabric"
    return {
        f"{primary}-solo":  {"args": ["--primary", primary, "--solo"], "env": {}},
        f"{other}-solo":    {"args": ["--primary", other, "--solo"], "env": {}},
        "process":          {"args": ["--primary", primary, "--backend", "process"], "env": {}},
        "injvm-file":       {"args": ["--primary", primary], "env": {"MMLF_FRAME_CHANNEL": "file", "MMLF_RENDER_OWNERSHIP": "off"}},
        "injvm-optimized":  {"args": ["--primary", primary], "env": {"MMLF_FRAME_CHANNEL": "direct", "MMLF_RENDER_OWNERSHIP": "safe"}},
    }

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--seconds", type=int, default=300)
    ap.add_argument("--primary", default="fabric", choices=["fabric", "neoforge"])
    ap.add_argument("--configs", help="comma-separated subset")
    args = ap.parse_args()
    available = configs(args.primary)
    chosen = args.configs.split(",") if args.configs else list(available)
    index = []
    for repeat in range(1, args.repeats + 1):
        for name in chosen:
            c = available[name]
            cmd = [sys.executable, str(RUN), "--profile", "baseline", "--seconds", str(args.seconds), *c["args"],
                   "--extra-env", "MMLF_EXPERIMENTS=probe", *[x for k, v in c["env"].items() for x in ("--extra-env", f"{k}={v}")]]
            print(f"[{repeat}/{args.repeats}] {name}: {' '.join(cmd[2:])}", file=sys.stderr)
            out = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
            result = next((line.split("result: ", 1)[1] for line in out.stdout.splitlines() if line.startswith("result: ")), None)
            index.append({"config": name, "repeat": repeat, "exit": out.returncode, "result": result, "stderr": out.stderr[-2000:]})
            time.sleep(10)  # let the previous JVM's files and GPU memory go
    target = ROOT / "mmlf" / "compat" / "results" / f"perf-{time.strftime('%Y%m%d-%H%M%S')}.json"
    target.write_text(json.dumps(index, indent=2), encoding="utf-8")
    print(f"index: {target}")

if __name__ == "__main__":
    main()
