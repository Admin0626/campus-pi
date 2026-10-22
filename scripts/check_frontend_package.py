"""Compare the complete packaged static tree, including unexpected stale files."""
from pathlib import Path
from zipfile import ZipFile
import json

root = Path(__file__).resolve().parents[1]
dist = root / "frontend/dist"
expected = {p.relative_to(dist).as_posix(): p.read_bytes() for p in dist.rglob("*") if p.is_file()}
assert "index.html" in expected, "Build frontend before validating the package"
with ZipFile(root / "backend/target/campus-pi-server-0.0.1-SNAPSHOT.jar") as jar:
    prefix = "BOOT-INF/classes/static/"
    actual = {n[len(prefix):]: jar.read(n) for n in jar.namelist() if n.startswith(prefix) and not n.endswith("/")}
    assert actual.keys() == expected.keys(), "Packaged static file set differs from frontend/dist (missing or stale files)"
    assert actual == expected, "Packaged static contents differ from frontend/dist"
    private = [n for n in jar.namelist() if "/.local/" in n or n.endswith("/private.json") or "project.private.config" in n]
    assert not private, "Private configuration must not be packaged"
print(json.dumps({"status": "PASS", "exact_static_file_set": True, "static_files": len(actual), "private_config_entries": len(private)}))
