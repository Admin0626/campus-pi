"""Check deployment files, SQL sources and test contracts without executing SQL or HTTP."""
from pathlib import Path
from collections import Counter
import argparse
import hashlib
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def check():
    deployment_files = ["README.md", "config/后端配置说明.md", "scripts/start.ps1",
                        "config/local/application-local.example.yml", "database/init.sql"]
    for name in deployment_files:
        require((ROOT / name).is_file(), "Missing deployment file: " + name)

    # These fixtures support test-data generation and consistency checks. They do not
    # prove runtime implementation status or replace application acceptance tests.
    spec = load("tests/contracts/campus-pi-openapi.json")
    require(spec["openapi"] == "3.0.3", "Unexpected OpenAPI version")
    require([server["url"] for server in spec["servers"]] == ["/api/v1"], "Review the API server contract")
    methods = {"get", "post", "put", "delete", "patch", "head", "options", "trace"}
    operations = {}
    phases = Counter()
    for path, entry in spec["paths"].items():
        for method, operation in entry.items():
            if method not in methods:
                continue
            oid = operation["operationId"]
            require(oid not in operations, "Duplicate API ID: " + oid)
            operations[oid] = (method.upper(), path)
            phases[operation["x-phase"]] += 1
    require(set(operations) == {f"API{i:03d}" for i in range(1, 113)} - {"API052"}, "Expected 111 active API IDs, API052 retired")
    require(spec.get("x-retired-operation-ids") == ["API052"], "Payment API retirement must remain explicit")
    require(phases == {"P0": 106, "P1": 5}, "P0/P1 allocation changed")

    def references(value):
        if isinstance(value, dict):
            if "$ref" in value:
                ref = value["$ref"]
                require(ref.startswith("#/"), "External schema ref needs review: " + ref)
                target = spec
                for token in ref[2:].split("/"):
                    target = target[token.replace("~1", "/").replace("~0", "~")]
            for item in value.values():
                references(item)
        elif isinstance(value, list):
            for item in value:
                references(item)
    references(spec)

    allocation = load("tests/contracts/module-allocation.json")
    packages = allocation["packages"]
    require({p["code"] for p in packages} == set("ABCDEFGHI") and len(packages) == 9, "Expected 9 options")
    assigned = [oid for package in packages for oid in package["ids"]]
    require(len(assigned) == len(set(assigned)) == 111 and set(assigned) == set(operations), "Missing or duplicate ownership")
    for package in packages:
        require(package["delivery_mode"] == "FULL_STACK", "Each option must be full stack")
        require(package["count"] == len(package["ids"]), "Option count mismatch")
        require(package["p0"] + package["p1"] == package["count"], "Option phase count mismatch")
        require(bool(package["pages"]) and bool(package["frontend_handoffs"]), "Missing frontend responsibility")
    for item in allocation["interfaces"]:
        require(operations[item["id"]] == (item["method"], item["path"]), "Ownership contract mismatch: " + item["id"])

    schema = (ROOT / "database/schema.sql").read_text(encoding="utf-8")
    transaction = schema
    counts = dict(tables=len(re.findall(r"^CREATE TABLE\b", schema, re.M | re.I)),
                  procedures=len(re.findall(r"^CREATE PROCEDURE\b", transaction, re.M | re.I)),
                  triggers=len(re.findall(r"^CREATE TRIGGER\b", transaction, re.M | re.I)))
    require(counts == {"tables": 25, "procedures": 12, "triggers": 4}, "SQL object baseline changed")

    migrations = sorted((ROOT / "database/migrations").glob("V*.sql"))
    require([int(p.name[1:4]) for p in migrations] == list(range(1, 10)), "Expected V001-V009 migrations")
    installer = (ROOT / "database/init.sql").read_text(encoding="utf-8")
    require("CREATE DATABASE `campus_pi_local`" in installer, "Fresh installer must refuse an existing database")
    for source in [ROOT / "database/schema.sql", *migrations]:
        payload = source.read_text(encoding="utf-8").replace("\r\n", "\n")
        stamp = f"-- Source: {source.relative_to(ROOT).as_posix()}\n-- SHA256: {hashlib.sha256(payload.encode('utf-8')).hexdigest()}"
        require(stamp in installer and payload.rstrip() in installer, "Stale SQL source in installer: " + source.name)

    # Only actual Markdown links are checked; code examples and plain paths are not treated as links.
    broken = []
    for file in [ROOT / "README.md", ROOT / "config/后端配置说明.md"]:
        text = file.read_text(encoding="utf-8")
        for href in re.findall(r"\]\(([^)]+)\)", text):
            if "://" in href or href.startswith(("#", "mailto:")):
                continue
            target = href.split("#", 1)[0].strip("<>")
            if target and not (file.parent / target).exists():
                broken.append(str(file.relative_to(ROOT)) + " -> " + target)
    require(not broken, "Broken documentation links: " + "; ".join(broken))
    return dict(status="PASS",deployment_files=len(deployment_files),api_operations=len(operations),
                api_paths=len(spec["paths"]),options=len(packages),P0=phases["P0"],P1=phases["P1"],
                ownership="UNIQUE_AND_COMPLETE",schema_references="RESOLVED",markdown_links="PASS",
                sql_objects=counts,migrations=len(migrations),installer_sources="MATCH",
                sql_executed=False,business_http_tests_run=False,
                note="Static consistency checks only; not full OpenAPI schema validation or application acceptance.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", help="Optional local JSON report path")
    args = parser.parse_args()
    try:
        report = check()
        content = json.dumps(report, ensure_ascii=False, indent=2)
        if args.report:
            target = ROOT / args.report
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content + "\n", encoding="utf-8")
        print(content)
    except (ValueError, KeyError, OSError, json.JSONDecodeError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
