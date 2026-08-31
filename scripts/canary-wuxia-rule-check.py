#!/usr/bin/env python3
"""p23-06: WUXIA 金丝雀规则断言——扫描实录 fixture 中的 prompt：
- 必须含武侠题材规则（题材规则（武侠））
- 不得含修仙题材规则/境界节奏/标准四段式爽点钩子（p21-01 后 WUXIA 不再套用）
用法: python3 scripts/canary-wuxia-rule-check.py <fixture目录>"""
import json, sys, glob, os

d = sys.argv[1] if len(sys.argv) > 1 else "data/e2e-fixtures/canary-wuxia-20260831"
files = sorted(glob.glob(os.path.join(d, "*.json")))
if not files:
    print(f"FAIL fixture 目录为空: {d}")
    sys.exit(1)

wuxia_rules = 0
cultivation_hits = []
scan_scope = ["题材规则（玄幻修仙）", "境界/阶段的提升",
              "标准四段式（开场钩子15%→冲突升级40%→高潮爽点30%→悬念结尾15%）"]
for f in files:
    try:
        d2 = json.load(open(f, encoding="utf-8"))
    except Exception:
        continue
    text = (d2.get("systemPrompt") or "") + "\n" + (d2.get("userMessage") or "")
    wuxia_rules += text.count("题材规则（武侠）")
    for marker in scan_scope:
        if marker in text:
            cultivation_hits.append((os.path.basename(f), marker))

print(f"扫描 fixture 数: {len(files)}")
print(f"『题材规则（武侠）』出现次数: {wuxia_rules}")
print(f"修仙规则命中: {cultivation_hits or '无'}")

ok = wuxia_rules > 0 and not cultivation_hits
print("PASS — WUXIA 金丝雀无修仙规则" if ok else "FAIL — WUXIA 金丝雀规则断言未过")
sys.exit(0 if ok else 1)
