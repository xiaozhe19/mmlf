"""Resolve the compatibility candidates on Modrinth for Minecraft 1.21.1.

For every candidate and loader: the newest release (or, failing that, newest any) version for 1.21.1, its primary file,
sha512, required dependencies (resolved to versions for the same loader), and the project's license. Writes
mmlf/compat/mods.json. Nothing is downloaded here; scripts/compat/fetch_mods.py downloads and verifies.

Usage: python scripts/compat/resolve_mods.py
"""
import json, sys, time, urllib.parse, urllib.request
from pathlib import Path

API = "https://api.modrinth.com/v2"
MC = "1.21.1"
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "mmlf" / "compat" / "mods.json"

# (category, slug, loaders to try)
CANDIDATES = [
    ("rendering", "sodium", ["fabric", "neoforge"]),
    ("rendering", "embeddium", ["neoforge"]),
    ("shader", "iris", ["fabric", "neoforge"]),
    ("vanilla-mechanics", "carpet", ["fabric"]),
    ("blueprint", "litematica", ["fabric"]),
    ("blueprint", "tweakeroo", ["fabric"]),
    ("blueprint", "minihud", ["fabric"]),
    ("replay", "flashback", ["fabric"]),
    ("building", "axiom", ["fabric", "neoforge"]),
    ("performance", "lithium", ["fabric", "neoforge"]),
    ("worldgen", "terralith", ["fabric", "neoforge"]),
    ("worldgen", "biomes-o-plenty", ["fabric", "neoforge"]),
    ("mobs", "naturalist", ["fabric", "neoforge"]),
    ("mechanical-animation", "create", ["neoforge"]),
]

# Create 6.0.9 matches the documented Iris/Flywheel compatibility addon range. Keep the test reproducible.
PINNED = {("create", "neoforge"): "n7NADxiG"}

def get(path, **query):
    url = API + path
    if query:
        url += "?" + urllib.parse.urlencode({k: json.dumps(v) if isinstance(v, list) else v for k, v in query.items()})
    req = urllib.request.Request(url, headers={"User-Agent": "mmlf-compat-resolver/1.0"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == 2:
                raise
        time.sleep(1 + attempt)

def newest(project, loader):
    versions = get(f"/project/{project}/version", game_versions=[MC], loaders=[loader]) or []
    # Modrinth tags some multi-loader files with several loaders; the loader must be listed.
    versions = [v for v in versions if loader in v["loaders"] and MC in v["game_versions"]]
    releases = [v for v in versions if v["version_type"] == "release"]
    pick = (releases or versions)
    return pick[0] if pick else None

def entry(version, loader):
    primary = next((f for f in version["files"] if f["primary"]), version["files"][0])
    return {
        "versionId": version["id"], "versionNumber": version["version_number"], "versionType": version["version_type"],
        "loader": loader, "gameVersions": version["game_versions"], "file": primary["filename"], "url": primary["url"],
        "sha512": primary["hashes"]["sha512"], "datePublished": version["date_published"],
    }

def resolve_dependencies(version, loader, seen):
    out = []
    for dep in version.get("dependencies", []):
        if dep["dependency_type"] != "required" or not dep.get("project_id"):
            continue
        pid = dep["project_id"]
        if (pid, loader) in seen:
            continue
        seen.add((pid, loader))
        project = get(f"/project/{pid}")
        dv = get(f"/version/{dep['version_id']}") if dep.get("version_id") else newest(pid, loader)
        if dv is None or loader not in dv["loaders"]:
            out.append({"project": project["slug"] if project else pid, "loader": loader, "missing": True})
            continue
        out.append({"project": project["slug"], "title": project["title"], "license": project["license"]["id"], **entry(dv, loader),
                    "dependencies": resolve_dependencies(dv, loader, seen)})
    return out

def main():
    records = []
    for category, slug, loaders in CANDIDATES:
        project = get(f"/project/{slug}")
        if project is None:
            records.append({"category": category, "project": slug, "status": "not_found"})
            print(f"{slug}: not found", file=sys.stderr)
            continue
        for loader in loaders:
            pin = PINNED.get((slug, loader))
            v = get(f"/version/{pin}") if pin else newest(slug, loader)
            if v is not None and (loader not in v["loaders"] or MC not in v["game_versions"]):
                raise ValueError(f"{slug} [{loader}]: pinned version does not support Minecraft {MC}")
            base = {"category": category, "project": slug, "title": project["title"], "license": project["license"]["id"],
                    "licenseName": project["license"].get("name"), "clientSide": project["client_side"], "serverSide": project["server_side"],
                    "source": f"https://modrinth.com/mod/{slug}"}
            if v is None:
                records.append({**base, "loader": loader, "status": "no_1.21.1_version"})
                print(f"{slug} [{loader}]: no {MC} version", file=sys.stderr)
                continue
            rec = {**base, **entry(v, loader), "status": "resolved", "dependencies": resolve_dependencies(v, loader, set())}
            records.append(rec)
            print(f"{slug} [{loader}]: {rec['versionNumber']} ({rec['license']}) deps={[d['project'] for d in rec['dependencies']]}", file=sys.stderr)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"minecraft": MC, "resolved": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "mods": records}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {OUT}", file=sys.stderr)

if __name__ == "__main__":
    main()
