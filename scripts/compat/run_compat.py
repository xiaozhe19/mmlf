"""Run one compatibility profile in a real game and record what the logs show.

  python scripts/compat/run_compat.py --profile sodium --primary fabric [--solo] [--seconds 300] [--backend injvm]

Stages mmlf/mods plus the profile's fetched mods (mmlf/compat/fetched.json; dependencies included) into
mmlf/compat/stage/<profile>/, then runs `gradlew :<primary>-bridge:runClient` with MMLF_MODS_DIR pointing there,
MMLF_SELF_TEST=true and MMLF_TEST_SECONDS. --solo runs the primary alone (MMLF_SOLO=true) as the single-loader
baseline. Results go to mmlf/compat/results/<profile>-<primary>[-solo]-<time>/result.json with the console log.

Only what the logs prove is recorded automatically:
  L1  every runtime initialized its bridge and reached its world, no crash report, exit code 0;
  mod-loaded  each profile mod appears in its loader's mod list;
  self-tests  each `MMLF ... SELFTEST ... passed=` line.
L2 (the mod's own functions), L3 (fused display and interaction) and L4 (long runs, restart, saves) need the checks
listed in docs/ai/result/v2-compat and are never set here.
"""
import argparse, json, os, re, shutil, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPAT = ROOT / "mmlf" / "compat"

def profile_jars(profile, fetched):
    """Every file the profile's mods need (dependencies included), per loader; a mod that cannot run here is a problem."""
    jars, problems = set(), []
    for loader in ("fabric", "neoforge"):
        for project in profile.get(loader, []):
            top = next((r for r in fetched if r["project"] == project and r["loader"] == loader and r.get("dependencyOf") is False), None)
            if top is None:
                problems.append(f"{project} [{loader}] not fetched"); continue
            if top.get("blockedBy"):
                problems.append(f"{project} [{loader}] " + "; ".join(top["blockedBy"])); continue
            jars.update(ROOT / f for f in top["closure"])
    return sorted(jars), problems

def stage(name, jars):
    target = COMPAT / "stage" / name
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    for source in list((ROOT / "mmlf" / "mods").glob("*.jar")) + jars:
        shutil.copy2(source, target / source.name)
    return target

def parse(console, session):
    text = console.read_text(encoding="utf-8", errors="replace")
    result = {
        "bridgesInitialized": sorted(set(re.findall(r"MMLF bridge initialized: (\w+)", text))),
        "sceneReady": sorted(set(re.findall(r"MMLF SCENE READY runtime=(\w+)", text))),
        "selfTests": [{"line": m.group(0).strip(), "passed": m.group(1) == "true"} for m in re.finditer(r"MMLF [A-Z ]+SELFTEST.*?passed=(true|false).*", text)],
        "compositeReady": "MMLF COMPOSITE READY" in text,
        "crashMarkers": sorted(set(re.findall(r"(Mixin apply failed|Mod resolution failed|Incompatible mods found|LoadingErrorScreen|---- Minecraft Crash Report ----|java\.lang\.\w*(?:Error|Exception)[^\n]{0,160})", text)))[:40],
        "fabricModList": re.findall(r"^\s*- ([a-z0-9_\-]+) ([^\s]+)", text, re.M),
    }
    if session and session.is_dir():
        logs = {}
        for jsonl in (session / "logs").glob("*.jsonl"):
            events = []
            for line in jsonl.read_text(encoding="utf-8", errors="replace").splitlines():
                try: events.append(json.loads(line))
                except json.JSONDecodeError: pass
            logs[jsonl.stem] = events
        result["worldReady"] = sorted({e["fields"].get("runtime") for evs in logs.values() for e in evs if e.get("event") == "runtime_world_ready"} - {None})
        result["phases"] = [(e["fields"]["runtimeId"], e["fields"]["phase"]) for evs in logs.values() for e in evs if e.get("event") == "injvm_phase"]
        result["meshHookEvidence"] = [{"runtime": e.get("runtime"), **e.get("fields", {})}
                                      for evs in logs.values() for e in evs
                                      if e.get("event") == "mesh_hook_observed"]
        result["metrics"] = {name: [e["fields"] for e in evs if e.get("module") == "metrics"][-3:] for name, evs in logs.items()}
        result["crashReports"] = [str(p.relative_to(session)) for p in session.rglob("crash-reports/*.txt")]
        result["remoteError"] = (session / "remote-error.txt").read_text(encoding="utf-8") if (session / "remote-error.txt").exists() else None
    return result

def launch_compatible(code, expected, parsed, solo=False):
    """L1 requires evidence that every expected runtime initialized and entered a world."""
    ready = parsed.get("sceneReady", []) if solo else parsed.get("worldReady", [])
    return (code == 0 and set(expected) <= set(parsed["bridgesInitialized"])
            and not parsed.get("crashReports") and set(expected) <= set(ready))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", required=True)
    ap.add_argument("--primary", choices=["fabric", "neoforge"], required=True)
    ap.add_argument("--solo", action="store_true")
    ap.add_argument("--seconds", type=int, default=300)
    ap.add_argument("--backend", default="injvm", choices=["injvm", "process"])
    ap.add_argument("--extra-env", action="append", default=[], help="KEY=VALUE passed to the game (e.g. JAVA options via MMLF_* variables)")
    args = ap.parse_args()

    profiles = json.loads((COMPAT / "profiles.json").read_text(encoding="utf-8"))
    fetched = json.loads((COMPAT / "fetched.json").read_text(encoding="utf-8"))
    profile = profiles[args.profile]
    if args.solo:
        profile = {args.primary: profile.get(args.primary, [])}
    jars, problems = profile_jars(profile, fetched)
    if problems:
        print("profile cannot run here:\n  " + "\n  ".join(problems), file=sys.stderr)
        sys.exit(2)
    name = f"{args.profile}-{args.primary}{'-solo' if args.solo else ''}"
    mods = stage(name, jars)
    out = COMPAT / "results" / f"{name}-{time.strftime('%Y%m%d-%H%M%S')}"
    out.mkdir(parents=True)
    env = dict(os.environ, MMLF_MODS_DIR=str(mods), MMLF_SELF_TEST="true", MMLF_TEST_SECONDS=str(args.seconds), MMLF_BACKEND=args.backend)
    if args.solo:
        env["MMLF_SOLO"] = "true"
    for kv in args.extra_env:
        k, v = kv.split("=", 1); env[k] = v
    gradlew = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    started = time.time()
    console = out / "console.log"
    with console.open("w", encoding="utf-8") as log:
        code = subprocess.call([gradlew, f":{args.primary}-bridge:runClient"], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    latest = ROOT / "runtime" / "latest-session.txt"
    session = Path(latest.read_text(encoding="utf-8").strip()) if latest.exists() and latest.stat().st_mtime >= started else None
    parsed = parse(console, session)
    parsed["selfTestSummary"] = {"observed": len(parsed["selfTests"]),
                                 "failed": sum(not t["passed"] for t in parsed["selfTests"]),
                                 "complete": "MMLF COLLAB SELFTEST complete" in console.read_text(encoding="utf-8", errors="replace")}
    expected = [args.primary] if args.solo else ["fabric", "neoforge"]
    l1 = launch_compatible(code, expected, parsed, args.solo)
    result = {"profile": args.profile, "primary": args.primary, "solo": args.solo, "backend": args.backend, "exitCode": code,
              "durationSeconds": round(time.time() - started), "mods": [p.name for p in jars], "session": str(session) if session else None,
              "L1": l1, "levelNote": "L1 由日志自动判定；L2–L4 需人工或专用探针，未在此设置", **parsed}
    (out / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("profile", "primary", "solo", "exitCode", "L1")}, ensure_ascii=False))
    print(f"result: {out / 'result.json'}")
    if not l1:
        sys.exit(code if code != 0 else 1)

if __name__ == "__main__":
    main()
