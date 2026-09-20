#!/usr/bin/env python3
"""Run the existing E2E with a saved nonempty contract before the first generation.

No product script is changed. The temporary script lives beside e2e.sh so its
repository-root resolution remains identical. Record/replay use the same input.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[4]
source = (root / "scripts/e2e.sh").read_text()
needle = 'echo "✅ 项目: $PROJECT_ID"'
assert source.count(needle) == 1, "E2E startup changed; review the injection point"
source = source.replace(needle, needle + '''
curl --noproxy '*' -sf -X PUT "$BASE/api/projects/$PROJECT_ID/intent" \\
  -H 'Content-Type: application/json' \\
  --data-binary @docs/harness/evidence/ncs033/author-intent.json > "$E2E_DIR/saved-intent.json"
''')
with tempfile.NamedTemporaryFile(mode="w", prefix=".ncs033-", suffix=".sh", dir=root / "scripts", delete=False) as f:
    f.write(source)
    temporary = Path(f.name)
try:
    result = subprocess.run(["bash", str(temporary), *sys.argv[1:]], cwd=root)
    sys.exit(result.returncode)
finally:
    temporary.unlink(missing_ok=True)
