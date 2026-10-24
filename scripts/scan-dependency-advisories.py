#!/usr/bin/env python3
"""Independent snapshot of reviewed GitHub Maven advisories, using existing gh auth.

Sends only public Maven package names/versions. No source, tokens or coordinates.
Reports high/critical matches as failure; it does not replace PR dependency review.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def advisories(name, version):
    result = subprocess.run([
        "gh", "api", "--method", "GET", "/advisories", "--paginate",
        "-H", "Accept: application/vnd.github+json", "-H", "X-GitHub-Api-Version: 2022-11-28",
        "-f", "type=reviewed", "-f", "ecosystem=maven", "-f", f"affects={name}@{version}",
        "-f", "per_page=100"], check=True, capture_output=True, text=True)
    # Older gh versions concatenate complete JSON page arrays when paginating.
    remaining, entries = result.stdout.lstrip(), []
    while remaining:
        page, end = json.JSONDecoder().raw_decode(remaining)
        if not isinstance(page, list):
            raise ValueError("Unexpected advisory response; refusing incomplete coverage")
        entries.extend(page)
        remaining = remaining[end:].lstrip()
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    inventory_bytes = args.inventory.read_bytes()
    inventory = json.loads(inventory_bytes)
    if not inventory.get("modules"):
        raise ValueError("Refusing an empty inventory")
    # Check that exact version filtering detects a known affected coordinate. This
    # package is a scanner control, not a dependency of this application.
    control = advisories("org.apache.logging.log4j:log4j-core", "2.14.1")
    if not any(a["ghsa_id"] == "GHSA-jfh8-c2jp-5v3q" for a in control):
        raise ValueError("Advisory API positive control failed; no clearance can be inferred")
    results = []
    for module in inventory["modules"]:
        results.append({**module, "advisories": advisories(module["name"], module["version"])})
    report = {
        "schema": 1,
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "queried_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "source": "GitHub Advisory Database public REST API; type=reviewed, ecosystem=maven, affects=package@version",
        "inventory_sha256": hashlib.sha256(inventory_bytes).hexdigest(),
        "modules_scanned": len(results),
        "scopes": inventory["scopes"],
        "limitations": inventory["limitations"] + [
            "Snapshot of published reviewed Maven advisories only; unknown/unreviewed vulnerabilities are not excluded",
            "No native/shaded library, Android platform, SDK or firmware security clearance is implied",
            "Does not replace GitHub PR dependency-diff review"],
        "positive_control": {"package": "org.apache.logging.log4j:log4j-core@2.14.1",
                             "expected_ghsa": "GHSA-jfh8-c2jp-5v3q", "passed": True},
        "results": results,
    }
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    matches = [(r, a) for r in results for a in r["advisories"] if not a.get("withdrawn_at")]
    print(f"Scanned {len(results)} Maven coordinates; {len(matches)} published advisory matches")
    for module, advisory in matches:
        print(f"{advisory['severity']} {module['name']}@{module['version']} {advisory['ghsa_id']}")
    return 1 if any(a["severity"] in ("high", "critical") for _, a in matches) else 0


if __name__ == "__main__":
    sys.exit(main())
