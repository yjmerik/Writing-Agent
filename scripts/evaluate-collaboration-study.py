#!/usr/bin/env python3
"""p40-04/06: Collaboration study evaluation protocol (honest version).

Requirements per spec (STUDY-01): two genres x 20 chapters x two conditions,
each chapter with >=3 independent real reader scores plus author time data.
LLM-as-judge or agent-filled scores are NOT accepted: ratings must declare
evaluatorKind=HUMAN and provide per-reader entries. Missing data -> INCOMPLETE
(non-zero with --require-complete); never COMPLETE on empty/insufficient data.
"""
import argparse
import csv
import json
import sys
from pathlib import Path

REQUIRED_GENRES = {"mystery", "wuxia"}
REQUIRED_CHAPTERS = 20
MIN_READERS_PER_CHAPTER = 3


def load_ratings(path: Path):
    """Ratings CSV: genre,condition,chapter,readerId,engagement,coherence,character,hook,overall,authorMinutes"""
    rows = []
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            rows.append(row)
    return rows


def evaluate(manifest_path: Path, ratings_path: Path):
    result = {"status": "INCOMPLETE", "reasons": [], "summary": {}}
    if not manifest_path.exists():
        result["reasons"].append("manifest not found")
        return result
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("evaluatorType", "").startswith("LLM"):
        result["reasons"].append("LLM-as-judge ratings are not accepted; human readers required")
    if not ratings_path.exists():
        result["reasons"].append("ratings file not found (real reader scores required)")
        return result

    rows = load_ratings(ratings_path)
    if not rows:
        result["reasons"].append("ratings file empty — cannot claim COMPLETE")
        return result

    # per genre/condition/chapter coverage with >=3 distinct readers
    coverage = {}
    for r in rows:
        key = (r.get("genre", ""), r.get("condition", ""), r.get("chapter", ""))
        coverage.setdefault(key, set()).add(r.get("readerId", ""))
    genres = {g for (g, _, _) in coverage}
    missing = []
    if not REQUIRED_GENRES.issubset(genres):
        missing.append(f"genres present {sorted(genres)}, required {sorted(REQUIRED_GENRES)}")
    for genre in sorted(REQUIRED_GENRES & genres):
        for condition in ("collaboration", "solo"):
            for chapter in range(1, REQUIRED_CHAPTERS + 1):
                readers = coverage.get((genre, condition, str(chapter)), set())
                if len(readers) < MIN_READERS_PER_CHAPTER:
                    missing.append(
                        f"{genre}/{condition}/ch{chapter}: {len(readers)} readers (<{MIN_READERS_PER_CHAPTER})")
    # author time data present
    if not any(r.get("authorMinutes") for r in rows):
        missing.append("no author time data (authorMinutes column empty)")

    # Score range validation: each dimension must be 1-10, authorMinutes >= 0
    DIMENSIONS = ["engagement", "coherence", "character", "hook", "overall"]
    invalid_scores = 0
    for r in rows:
        for dim in DIMENSIONS:
            try:
                val = float(r.get(dim, 0))
                if val < 1 or val > 10:
                    invalid_scores += 1
            except (ValueError, TypeError):
                invalid_scores += 1
        try:
            am = float(r.get("authorMinutes", 0))
            if am < 0:
                invalid_scores += 1
        except (ValueError, TypeError):
            invalid_scores += 1
    if invalid_scores > 0:
        result["reasons"].append(f"{invalid_scores} invalid score/time entries (out of range)")
        return result

    # Quality threshold: collaboration overall must not be worse than solo
    def avg_overall(condition):
        scores = [float(r["overall"]) for r in rows
                  if r.get("condition") == condition and r.get("overall")]
        return sum(scores) / len(scores) if scores else 0

    collab_avg = avg_overall("collaboration")
    solo_avg = avg_overall("solo")
    result["summary"]["collaborationOverall"] = round(collab_avg, 2)
    result["summary"]["soloOverall"] = round(solo_avg, 2)
    if solo_avg > 0 and collab_avg < solo_avg - 1.5:
        result["reasons"].append(
            f"Quality regression: collaboration ({collab_avg:.1f}) significantly "
            f"worse than solo ({solo_avg:.1f})")
        return result

    # Author time: collaboration should not dramatically increase burden
    def avg_time(condition):
        times = [float(r["authorMinutes"]) for r in rows
                 if r.get("condition") == condition and r.get("authorMinutes")]
        return sum(times) / len(times) if times else 0

    collab_time = avg_time("collaboration")
    solo_time = avg_time("solo")
    result["summary"]["collaborationMinutes"] = round(collab_time, 1)
    result["summary"]["soloMinutes"] = round(solo_time, 1)
    if solo_time > 0 and collab_time > solo_time * 2:
        result["reasons"].append(
            f"Author burden regression: collaboration ({collab_time:.0f}min) "
            f"more than doubles solo ({solo_time:.0f}min)")
        return result

    result["summary"]["entries"] = len(rows)
    result["summary"]["genreConditionChapterCells"] = len(coverage)
    result["summary"]["cellsBelowReaderThreshold"] = sum(
        1 for v in coverage.values() if len(v) < MIN_READERS_PER_CHAPTER)

    if missing:
        # cap reported reasons (keep earlier protocol-level reasons)
        result["reasons"] = result["reasons"] + missing[:20]
        result["missingCount"] = len(missing)
        return result
    if result["reasons"]:
        # protocol-level rejection (e.g. LLM-as-judge) — never COMPLETE
        return result
    result["status"] = "COMPLETE"
    return result


def main():
    parser = argparse.ArgumentParser(description="Collaboration study evaluation (honest)")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--ratings", required=True)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()

    out = evaluate(Path(args.manifest), Path(args.ratings))
    print(json.dumps(out, ensure_ascii=False, indent=2))
    if args.require_complete and out["status"] != "COMPLETE":
        sys.exit(1)


if __name__ == "__main__":
    main()
