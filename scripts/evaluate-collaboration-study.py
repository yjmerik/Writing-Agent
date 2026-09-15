#!/usr/bin/env python3
"""p40-04: Collaboration study evaluation protocol."""
import argparse, json, sys, os

def main():
    parser = argparse.ArgumentParser(description="Collaboration study evaluation")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--ratings", required=True)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()

    if not os.path.exists(args.manifest):
        print(json.dumps({"status": "NOT_RUN", "reason": "manifest not found"}))
        if args.require_complete:
            sys.exit(1)
        sys.exit(0)

    with open(args.manifest) as f:
        manifest = json.load(f)

    if not os.path.exists(args.ratings):
        print(json.dumps({"status": "NOT_RUN", "reason": "ratings not found"}))
        if args.require_complete:
            sys.exit(1)
        sys.exit(0)

    with open(args.ratings) as f:
        ratings = json.load(f)

    result = {"status": "COMPLETE" if ratings.get("complete") else "INCOMPLETE",
              "entries": len(ratings.get("scores", []))}
    print(json.dumps(result))
    if args.require_complete and result["status"] != "COMPLETE":
        sys.exit(1)

if __name__ == "__main__":
    main()
