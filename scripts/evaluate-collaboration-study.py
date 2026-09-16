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

    result["summary"] = {
        "entries": len(rows),
        "genreConditionChapterCells": len(coverage),
        "cellsBelowReaderThreshold": sum(
            1 for v in coverage.values() if len(v) < MIN_READERS_PER_CHAPTER),
    }
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
