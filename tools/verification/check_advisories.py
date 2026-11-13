#!/usr/bin/env python3
"""Check public resolved Maven coordinates against OSV; never sends source or files."""
import argparse
import json
from pathlib import Path
import re
import urllib.request


def queries_from_inventory(inventory):
    if inventory.get("failures"):
        raise ValueError("Dependency inventory has unresolved configurations")
    components = inventory["components"]
    coordinates = set()
    for component in components:
        name, version = component["name"], component["version"]
        if not re.fullmatch(r"[\w.-]+:[\w.-]+", name) or not re.fullmatch(r"[\w.+-]+", version):
            raise ValueError("Expected published Maven coordinates and fixed versions")
        coordinates.add((name, version))
    if not coordinates:
        raise ValueError("Dependency inventory is empty")
    return [{"package": {"ecosystem": "Maven", "name": name}, "version": version}
            for name, version in sorted(coordinates)]


def query_osv(queries):
    results = [{"vulns": []} for _ in queries]
    pending = list(enumerate(queries))
    for _ in range(10):
        following = []
        for start in range(0, len(pending), 100):
            batch = pending[start:start + 100]
            request = urllib.request.Request(
                "https://api.osv.dev/v1/querybatch",
                data=json.dumps({"queries": [query for _, query in batch]}).encode(),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=30) as response:
                page = json.load(response)["results"]
            if len(page) != len(batch):
                raise ValueError("Incomplete OSV response")
            for (index, query), item in zip(batch, page):
                if "error" in item:
                    raise ValueError("OSV rejected a query")
                results[index]["vulns"].extend(item.get("vulns", []))
                if item.get("next_page_token"):
                    following.append((index, dict(query, page_token=item["next_page_token"])))
        pending = following
        if not pending:
            return results
    raise ValueError("OSV pagination did not finish")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    queries = queries_from_inventory(json.loads(args.inventory.read_text()))
    results = query_osv(queries)
    report = {"queries": queries, "results": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    findings = [(query, result["vulns"]) for query, result in zip(queries, results) if result["vulns"]]
    for query, vulnerabilities in findings:
        print(query["package"]["name"] + ":" + query["version"] + " " +
              ", ".join(sorted({item["id"] for item in vulnerabilities})))
    print(f"Checked {len(queries)} resolved Maven versions; {len(findings)} matched advisories.")
    if findings:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
