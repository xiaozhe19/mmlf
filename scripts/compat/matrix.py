"""Build the compatibility matrix from what was resolved, fetched and actually run.

  python scripts/compat/matrix.py

Inputs: mmlf/compat/fetched.json (versions, licenses, environment checks) and every
mmlf/compat/results/*/result.json written by run_compat.py. Output: docs/ai/result/v2-compat/matrix.md and matrix.json.
A mod is only ever marked with a level some run showed; with no run it is "未测". L2-L4 are taken from
mmlf/compat/manual.json (entries added by hand after the checks in docs/ai/result/v2-compat/README.md), never inferred.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPAT = ROOT / "mmlf" / "compat"
OUT = ROOT / "docs" / "ai" / "result" / "v2-compat"
PROFILE_OF = {}

def main():
    fetched = json.loads((COMPAT / "fetched.json").read_text(encoding="utf-8"))
    profiles = json.loads((COMPAT / "profiles.json").read_text(encoding="utf-8"))
    for name, p in profiles.items():
        if name.startswith("_"): continue
        for loader in ("fabric", "neoforge"):
            for project in p.get(loader, []):
                PROFILE_OF.setdefault((project, loader), name)
    manual = json.loads((COMPAT / "manual.json").read_text(encoding="utf-8")) if (COMPAT / "manual.json").exists() else []
    results = [json.loads(p.read_text(encoding="utf-8")) for p in sorted((COMPAT / "results").glob("*/result.json"))] if (COMPAT / "results").exists() else []
    rows = []
    for r in fetched:
        if r.get("dependencyOf") or r.get("status") == "provided_by_dev_environment":
            continue
        project, loader = r["project"], r.get("loader")
        profile = PROFILE_OF.get((project, loader))
        runs = []
        for res in results:
            if res["profile"] != profile:
                continue
            # The mod sits on its own loader's side: near when that loader is the primary, else far.
            role = "solo" if res["solo"] else ("near" if res["primary"] == loader else "far")
            if res["solo"] and res["primary"] != loader:
                continue
            runs.append({"role": role, "primary": res["primary"], "L1": res["L1"], "exitCode": res["exitCode"],
                         "selfTestsFailed": [t["line"] for t in res.get("selfTests", []) if not t["passed"]],
                         "crashMarkers": res.get("crashMarkers", [])[:5], "session": res.get("session")})
        levels = [m for m in manual if m["project"] == project and m["loader"] == loader]
        status = r.get("status")
        if status == "fetched" and not runs:
            verdict = "未测"
        elif status != "fetched":
            verdict = {"skipped": "不测", "incompatible_with_dev_environment": "版本不匹配（未测）", "no_1.21.1_version": "无 1.21.1 版本"}.get(status, status)
        else:
            reached = [run for run in runs if run["L1"]]
            verdict = "L1（" + "、".join(sorted({x["role"] for x in reached})) + "）" if reached else "L1 失败"
            if levels:
                verdict = max(levels, key=lambda m: m["level"])["level"] + "（人工）"
        rows.append({"project": project, "title": r.get("title", project), "loader": loader, "version": r.get("version"), "license": r.get("license"),
                     "profile": profile, "status": status, "reason": r.get("reason"), "declares": r.get("declares"), "verdict": verdict,
                     "runs": runs, "manual": levels, "adapterNeeded": None})
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "matrix.json").write_text(json.dumps({"minecraft": "1.21.1", "rows": rows}, indent=2, ensure_ascii=False), encoding="utf-8")
    lines = ["# 兼容性矩阵（由 `scripts/compat/matrix.py` 生成，勿手改）", "",
             "环境：Minecraft 1.21.1，Fabric Loader 0.16.10 + Fabric API 0.115.4，NeoForge 21.1.265，Temurin JDK 21，开发路径（Gradle runClient），单 JVM（injvm）。", "",
             "| Mod | Loader | 版本 | 许可 | 组合 | 结论 | 运行记录 | 原因 / 证据 |", "|---|---|---|---|---|---|---|---|"]
    for row in rows:
        runs = "；".join(f"{x['role']}（{x['primary']} 近端）L1={'通过' if x['L1'] else '失败'}" for x in row["runs"]) or "—"
        evidence = row["reason"] or ("；".join(sorted({m for x in row["runs"] for m in x["crashMarkers"]}))[:200] if row["runs"] else "")
        lines.append(f"| {row['title']} | {row['loader']} | {row['version'] or '—'} | {row['license'] or '—'} | {row['profile'] or '—'} | {row['verdict']} | {runs} | {evidence or '—'} |")
    lines += ["", "说明：L1 由 `run_compat.py` 依据日志自动判定（两端 bridge 初始化、进入世界、无崩溃报告、退出码 0）；L2–L4 只来自 `mmlf/compat/manual.json` 的人工记录。"]
    (OUT / "matrix.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUT / 'matrix.md'} ({len(rows)} rows)")

if __name__ == "__main__":
    main()
