#!/usr/bin/env python3
"""p40-04 contract tests: evaluation script must be honest about data sufficiency."""
import csv
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "evaluate-collaboration-study.py")


def run(manifest, ratings, require_complete=True):
    cmd = [sys.executable, SCRIPT, "--manifest", manifest, "--ratings", ratings]
    if require_complete:
        cmd.append("--require-complete")
    proc = subprocess.run(cmd, capture_output=True, text=True)
    out = json.loads(proc.stdout) if proc.stdout.strip().startswith("{") else {}
    return proc.returncode, out


def write_manifest(path, evaluator="HUMAN blind readers"):
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"study": "collaboration-quality", "evaluatorType": evaluator}, f)


def write_ratings(path, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=[
            "genre", "condition", "chapter", "readerId",
            "engagement", "coherence", "character", "hook", "overall", "authorMinutes"])
        w.writeheader()
        for r in rows:
            w.writerow(r)


def full_rows():
    rows = []
    for genre in ("mystery", "wuxia"):
        for condition in ("collaboration", "solo"):
            for chapter in range(1, 21):
                for reader in ("r1", "r2", "r3"):
                    rows.append({"genre": genre, "condition": condition, "chapter": chapter,
                                 "readerId": reader, "engagement": 7, "coherence": 7,
                                 "character": 7, "hook": 7, "overall": 7,
                                 "authorMinutes": 30 if condition == "collaboration" else 60})
    return rows


def main():
    tmp = tempfile.mkdtemp()
    manifest = os.path.join(tmp, "manifest.json")
    ratings = os.path.join(tmp, "ratings.csv")

    # 1. missing ratings -> INCOMPLETE, exit 1
    write_manifest(manifest)
    code, out = run(manifest, ratings)
    assert code == 1 and out.get("status") == "INCOMPLETE", f"missing ratings: {out}"

    # 2. empty ratings file -> INCOMPLETE (never COMPLETE on empty)
    write_ratings(ratings, [])
    code, out = run(manifest, ratings)
    assert code == 1 and out.get("status") == "INCOMPLETE", f"empty ratings: {out}"

    # 3. LLM-as-judge manifest rejected
    write_manifest(manifest, evaluator="LLM-as-judge (proxy)")
    write_ratings(ratings, full_rows())
    code, out = run(manifest, ratings)
    assert code == 1 and out.get("status") == "INCOMPLETE", f"LLM judge: {out}"
    assert any("LLM" in r for r in out.get("reasons", [])), "LLM rejection reason"

    # 4. insufficient readers -> INCOMPLETE with missing cells reported
    write_manifest(manifest)
    write_ratings(ratings, [r for r in full_rows() if r["readerId"] != "r3"])
    code, out = run(manifest, ratings)
    assert code == 1 and out.get("status") == "INCOMPLETE", f"2 readers: {out}"
    assert out.get("missingCount", 0) > 0

    # 5. full protocol -> COMPLETE
    write_ratings(ratings, full_rows())
    code, out = run(manifest, ratings)
    assert code == 0 and out.get("status") == "COMPLETE", f"full: {out}"

    print("test_evaluate_collaboration_study: PASS (5 scenarios)")


if __name__ == "__main__":
    main()
