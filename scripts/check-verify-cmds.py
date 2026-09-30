#!/usr/bin/env python3
"""校验 completed feature 的 verify_cmd 引用的工件真实存在（不执行任何命令）。

对每个 completed 且完成记录 kind != historical 的 feature：
- verify_cmd 中的 XxxTest 类名必须在 src/test/java/** 下存在同名 .java
- verify_cmd 引用的 scripts/*.py|sh 与 ./init.sh 必须存在
- 引用 frontend 构建时 frontend/package.json 必须存在
historical 记录的 feature 证据限制已在 completions.json 文档化，不在本校验范围。
退出码 0=全部通过，1=存在失效引用。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLASS_RE = re.compile(r"\b[A-Z][A-Za-z0-9]*Test\b")
SCRIPT_RE = re.compile(r"(?:\./)?((?:scripts/)?[\w-]+\.(?:py|sh))")


def load_ledger_kinds():
    ledger = ROOT / "docs/harness/completions.json"
    if not ledger.is_file():
        return {}
    kinds = {}
    for rec in json.loads(ledger.read_text()).get("records", []):
        kinds[rec.get("id")] = rec.get("kind")
    return kinds


def main():
    data = json.loads((ROOT / "feature_list.json").read_text())
    kinds = load_ledger_kinds()
    problems, checked = [], 0
    for f in data.get("features", []):
        if f.get("status") != "completed":
            continue
        if kinds.get(f.get("completion_record")) == "historical":
            continue
        fid = f["id"]
        cmd = f.get("verify_cmd", "")
        if not cmd.strip():
            problems.append(f"{fid}: completed 但 verify_cmd 为空")
            continue
        for name in sorted(set(CLASS_RE.findall(cmd))):
            checked += 1
            if not list((ROOT / "src/test").rglob(name + ".java")):
                problems.append(f"{fid}: 测试类不存在: {name}")
        for path in sorted(set(SCRIPT_RE.findall(cmd))):
            checked += 1
            if not (ROOT / path).is_file():
                problems.append(f"{fid}: 脚本不存在: {path}")
        if "frontend" in cmd and not (ROOT / "frontend/package.json").is_file():
            problems.append(f"{fid}: frontend/package.json 不存在")

    for p in problems:
        print("FAIL:", p)
    if not problems:
        print(f"PASS: {checked} 个 verify_cmd 引用全部存在")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
