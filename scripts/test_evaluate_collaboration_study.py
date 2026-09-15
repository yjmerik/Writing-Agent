#!/usr/bin/env python3
import subprocess, sys, os, json, tempfile
def test():
    manifest = os.path.join(tempfile.mkdtemp(), "manifest.json")
    ratings = os.path.join(tempfile.mkdtemp(), "ratings.json")
    result = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "evaluate-collaboration-study.py"),
        "--manifest", manifest, "--ratings", ratings, "--require-complete"], capture_output=True, text=True)
    assert result.returncode != 0, "missing ratings should fail with --require-complete"
    print("test_evaluate_collaboration_study: PASS")
if __name__ == "__main__":
    test()
