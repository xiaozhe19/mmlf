"""Download the resolved compatibility mods (mmlf/compat/mods.json) and check them before any test uses them.

Each primary file and its required dependencies come from cdn.modrinth.com over https and must match the sha512
Modrinth lists. Fabric API is not downloaded: development runs already have the project's own (gradle.properties).
Skipped on purpose: see SKIP. For every jar the loader requirements it declares are read from its metadata
(fabric.mod.json / META-INF/neoforge.mods.toml) and written with it to mmlf/compat/fetched.json, so a version the
project's loader cannot run is reported instead of being "tested".

Usage: python scripts/compat/fetch_mods.py
"""
import hashlib, json, re, sys, urllib.parse, urllib.request, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPAT = ROOT / "mmlf" / "compat"
JARS = COMPAT / "jars"
PROVIDED = {"fabric-api"}
# Not run in this round, with the reason recorded in the matrix.
SKIP = {
    "axiom": "All-Rights-Reserved 许可，且无 NeoForge 1.21.1 版本；本轮只记录不测试",
}

def download(url, target, sha512):
    host = urllib.parse.urlparse(url)
    if host.scheme != "https" or host.hostname != "cdn.modrinth.com":
        raise RuntimeError(f"unexpected download source {url}")
    if target.exists() and hashlib.sha512(target.read_bytes()).hexdigest() == sha512:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "mmlf-compat-fetch/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if hashlib.sha512(data).hexdigest() != sha512:
        raise RuntimeError(f"checksum mismatch for {url}")
    target.write_bytes(data)

def declared(jar):
    """Loader, Minecraft and Java requirements the jar declares for itself."""
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        if "fabric.mod.json" in names:
            meta = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
            depends = meta.get("depends", {})
            return {"modId": meta.get("id"), "fabricloader": depends.get("fabricloader"), "minecraft": depends.get("minecraft"),
                    "java": depends.get("java"), "fabric-api": depends.get("fabric-api") or depends.get("fabric")}
        if "META-INF/neoforge.mods.toml" in names:
            text = z.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
            out = {"modId": (re.search(r'modId\s*=\s*"([^"]+)"', text) or [None, None])[1]}
            for dep in ("neoforge", "minecraft"):
                m = re.search(r'modId\s*=\s*"' + dep + r'"[^\[]*?versionRange\s*=\s*"([^"]+)"', text, re.S)
                out[dep] = m.group(1) if m else None
            return out
    return {}

def fetch(record, out, depth=0):
    project, loader = record["project"], record["loader"]
    if project in PROVIDED:
        out.append({"project": project, "loader": loader, "status": "provided_by_dev_environment"})
        return None
    if record.get("missing"):
        entry = {"project": project, "loader": loader, "status": "dependency_missing"}
        out.append(entry)
        return entry
    target = JARS / loader / record["file"]
    download(record["url"], target, record["sha512"])
    entry = {"project": project, "loader": loader, "version": record["versionNumber"], "file": str(target.relative_to(ROOT)).replace("\\", "/"),
             "sha512": record["sha512"], "license": record.get("license"), "dependencyOf": depth > 0, "declares": declared(target), "status": "fetched"}
    out.append(entry)
    print(f"{'  ' * depth}{project} [{loader}] {record['versionNumber']}", file=sys.stderr)
    deps = []
    for dep in record.get("dependencies", []):
        child = fetch(dep, out, depth + 1)
        if child is not None:
            deps.append(child)
    entry["requires"] = deps
    return entry

def project_versions():
    props = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1); props[k.strip()] = v.strip()
    return props

def numbers(version):
    return [int(x) for x in re.findall(r"\d+", version.split("+")[0])[:4]]

def satisfied(requirement, have):
    """Check supported Fabric predicates and Maven intervals; None means the requirement is unknown."""
    if requirement is None or requirement == "*":
        return True
    if isinstance(requirement, list):
        results = [satisfied(r, have) for r in requirement]
        return True if True in results else (None if None in results else False)
    exact = re.fullmatch(r"\s*\[\s*([0-9.]+)\s*\]\s*", requirement)
    if exact:
        return (numbers(have) + [0] * 4)[:4] == (numbers(exact.group(1)) + [0] * 4)[:4]
    m = re.fullmatch(r"\s*([\[(])\s*([0-9.]+)?\s*,\s*([0-9.]+)?\s*([\])])\s*", requirement)
    if m:
        value = tuple((numbers(have) + [0] * 4)[:4])
        lower = tuple((numbers(m.group(2)) + [0] * 4)[:4]) if m.group(2) else None
        upper = tuple((numbers(m.group(3)) + [0] * 4)[:4]) if m.group(3) else None
        return ((lower is None or (value >= lower if m.group(1) == "[" else value > lower)) and
                (upper is None or (value <= upper if m.group(4) == "]" else value < upper)))
    m = re.fullmatch(r"\s*>=\s*([0-9][0-9A-Za-z.+-]*)\s*", requirement)
    if m:
        return (numbers(have) + [0] * 4)[:4] >= (numbers(m.group(1)) + [0] * 4)[:4]
    if re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", requirement):
        return (numbers(have) + [0] * 4)[:4] == (numbers(requirement) + [0] * 4)[:4]
    return None

def check(record, props):
    d = record.get("declares", {})
    problems = []
    if record["loader"] == "fabric":
        for key, have in (("fabricloader", props.get("fabric_loader_version")), ("fabric-api", props.get("fabric_api_version", "").split("+")[0])):
            ok = satisfied(d.get(key), have or "0")
            if ok is False:
                problems.append(f"{key} {d.get(key)}，项目为 {have}")
    for key, have in (("minecraft", props.get("minecraft_version")),
                      ("neoforge", props.get("neoforge_version") if record["loader"] == "neoforge" else None)):
        if have and satisfied(d.get(key), have) is False:
            problems.append(f"{key} {d.get(key)}，项目为 {have}")
    if problems:
        record["status"] = "incompatible_with_dev_environment"
        record["reason"] = "；".join(problems)

def main():
    resolved = json.loads((COMPAT / "mods.json").read_text(encoding="utf-8"))
    fetched = []
    for record in resolved["mods"]:
        if record["project"] in SKIP:
            fetched.append({"project": record["project"], "loader": record.get("loader"), "status": "skipped", "reason": SKIP[record["project"]]})
            continue
        if record.get("status") != "resolved":
            fetched.append({"project": record["project"], "loader": record.get("loader"), "status": record.get("status")})
            continue
        fetch(record, fetched)
    props = project_versions()
    for record in fetched:
        if record.get("status") == "fetched":
            check(record, props)
    # Each tested mod's whole set of files; it cannot run here when any of them cannot.
    def closure(entry, files, blockers):
        files.append(entry.get("file"))
        if entry["status"] != "fetched":
            blockers.append(f"{entry['project']}: {entry['status']} {entry.get('reason', '')}".strip())
        for child in entry.get("requires", []):
            closure(child, files, blockers)
    for record in fetched:
        if record.get("dependencyOf") is False:
            files, blockers = [], []
            closure(record, files, blockers)
            record["closure"] = sorted({f for f in files if f})
            record["blockedBy"] = blockers
    for record in fetched:
        record.pop("requires", None)
    (COMPAT / "fetched.json").write_text(json.dumps(fetched, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {COMPAT / 'fetched.json'}", file=sys.stderr)

if __name__ == "__main__":
    main()
