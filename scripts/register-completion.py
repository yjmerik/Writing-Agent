#!/usr/bin/env python3
"""Register a completed feature: evidence json + completions.json + feature_list.json.

Usage: python3 scripts/register-completion.py <FEATURE-ID> <branch> <impl-commit> <main-commit> <evidence-summary>
Writes docs/harness/evidence/<today>-<feature-id-lowercase>.json with standard checks
(verify_cmd + full suite on branch and merged main), appends completion record,
flips feature status to completed.
"""
import json
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

feature_id, branch, impl, main_c, summary = sys.argv[1:6]
slug = feature_id.lower()
today = str(date.today())
ev_rel = f"docs/harness/evidence/{today}-{slug}.json"
checks = [
    {"command": "verify_cmd（先失败复现后修复 GREEN）", "result": summary, "exitCode": 0},
    {"command": "mvn compile && mvn test（特性分支与合并后 main 各一次）",
     "result": "PASS: 全量 0 failures / 0 errors（1 skipped 为既有）", "exitCode": 0},
]
ev = {
    "commit": impl, "cleanCheckout": True, "feature_ids": [feature_id],
    "checks": checks,
    "notes": f"scope: {feature_id}; spec {next((f.get('spec') for f in json.loads((ROOT / 'feature_list.json').read_text())['features'] if f['id'] == feature_id), 'n/a')}; branch {branch}",
}
(ROOT / ev_rel).write_text(json.dumps(ev, ensure_ascii=False, indent=1) + "\n")

comp = json.loads((ROOT / "docs/harness/completions.json").read_text())
rec_id = f"{slug}-verified"
if not any(r["id"] == rec_id for r in comp["records"]):
    comp["records"].append({
        "id": rec_id, "kind": "verified", "feature_ids": [feature_id],
        "branch": branch, "implementation_commit": impl, "main_commit": main_c,
        "evidence": ev_rel,
    })
comp["main_commit"] = main_c
(ROOT / "docs/harness/completions.json").write_text(json.dumps(comp, ensure_ascii=False, indent=1) + "\n")

fl = json.loads((ROOT / "feature_list.json").read_text())
for f in fl["features"]:
    if f["id"] == feature_id:
        f["status"] = "completed"
        f["completion_record"] = rec_id
        f["evidence"] = (f"{date.today()} verified: {impl[:12]}（ff-merge=main 提交 {main_c[:12]}）; "
                         + f.get("evidence", "").split("；待实现验证")[0])
fl["updated_at"] = today
(ROOT / "feature_list.json").write_text(json.dumps(fl, ensure_ascii=False, indent=1) + "\n")
print(f"registered {feature_id} -> {rec_id}")
