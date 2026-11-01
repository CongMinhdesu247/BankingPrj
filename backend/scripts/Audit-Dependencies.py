"""Query public OSV advisories for resolved Maven coordinates, never local secrets.

First run Maven dependency:tree -DoutputType=json -DoutputFile=target/security-dependencies.json.
Network failures fail the scan; they are never interpreted as zero vulnerabilities.
"""
import datetime
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1]
tree = json.loads((root / "target/security-dependencies.json").read_text(encoding="utf-8"))
packages = {}

def visit(node):
    for child in node.get("children", []):
        name = child["groupId"] + ":" + child["artifactId"]
        packages[(name, child["version"])] = {"package": {"ecosystem": "Maven", "name": name}, "version": child["version"]}
        visit(child)

visit(tree)
queries = list(packages.values())
results = []
for offset in range(0, len(queries), 100):
    batch = queries[offset:offset + 100]
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch",
        data=json.dumps({"queries": batch}).encode(), headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=60) as response:
        body = json.load(response)
    if len(body.get("results", [])) != len(batch):
        raise RuntimeError("Incomplete OSV response")
    for package, result in zip(batch, body["results"], strict=True):
        results.append({**package, **result})
report = {"checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "source": "https://api.osv.dev/v1/querybatch", "results": results}
output = root / "target/security-osv.json"
output.write_text(json.dumps(report, indent=2), encoding="utf-8")
affected = [result for result in results if result.get("vulns")]
print(f"Queried {len(results)} resolved Maven packages; {len(affected)} have advisory matches. Report: {output}")
for result in affected:
    print(result["package"]["name"], result["version"], ", ".join(v["id"] for v in result["vulns"]))
