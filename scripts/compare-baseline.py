#!/usr/bin/env python3
"""质量基线对比：e2e-report.json vs baselines/golden.json，容差判定 PASS/FAIL。

用法: python3 scripts/compare-baseline.py [report路径] [baseline路径]
退出码: 0=达标, 1=退化（架构/prompt 改动后必须查明原因）
"""
import json
import sys


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    report_path = args[0] if len(args) > 0 else "e2e-report.json"
    baseline_path = args[1] if len(args) > 1 else "baselines/golden.json"

    try:
        report = json.load(open(report_path, encoding="utf-8"))
    except FileNotFoundError:
        print(f"❌ 报告不存在: {report_path}（先跑 ./scripts/e2e.sh）")
        return 1

    if "--bless" in sys.argv:
        return bless(report_path, baseline_path)

    try:
        baseline = json.load(open(baseline_path, encoding="utf-8"))
    except FileNotFoundError:
        print(f"⚠️ 基线不存在: {baseline_path}")
        print("   首次建基线: 人工确认当前 e2e-report.json 质量可接受后，")
        print("   执行: python3 scripts/compare-baseline.py --bless")
        return 1

    checks = [
        ("章节数", report["totalChapters"], ">=", baseline["minChapters"]),
        ("总字数", report["totalWords"], ">=", baseline["minTotalWords"]),
        ("平均章字数", report["wordCount"]["avg"], ">=", baseline["minAvgWordCount"]),
        ("章节失败数", report["chapterFailures"], "<=", baseline["maxChapterFailures"]),
        ("LLM错误数", report["llmErrors"], "<=", baseline["maxLlmErrors"]),
    ]
    if baseline.get("minEvaluationScore") and isinstance(report.get("evaluation"), dict):
        score = report["evaluation"].get("overallScore") or report["evaluation"].get("score")
        if score is not None:
            checks.append(("质量评分", score, ">=", baseline["minEvaluationScore"]))

    failed = []
    print(f"# 基线对比: {report_path} vs {baseline_path}\n")
    for name, actual, op, expected in checks:
        ok = actual >= expected if op == ">=" else actual <= expected
        mark = "✅" if ok else "❌"
        print(f"{mark} {name}: {actual} {op} {expected}")
        if not ok:
            failed.append(name)

    if failed:
        print(f"\n❌ FAIL — 退化项: {', '.join(failed)}")
        print("   处置: 查明退化原因；确属预期改动则人工 --bless 更新基线")
        return 1
    print("\n✅ PASS — 质量未退化")
    return 0


def bless(report_path: str, baseline_path: str) -> int:
    report = json.load(open(report_path, encoding="utf-8"))
    baseline = {
        "blessedAt": report.get("generatedAt"),
        "blessedFrom": report_path,
        "minChapters": report["totalChapters"],
        "minTotalWords": int(report["totalWords"] * 0.8),
        "minAvgWordCount": int(report["wordCount"]["avg"] * 0.7),
        "maxChapterFailures": 0,
        "maxLlmErrors": 0,
        "minEvaluationScore": None,
    }
    import os
    os.makedirs(os.path.dirname(baseline_path) or ".", exist_ok=True)
    json.dump(baseline, open(baseline_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"✅ 基线已更新: {baseline_path}")
    print(json.dumps(baseline, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
