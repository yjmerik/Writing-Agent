#!/usr/bin/env bash
# 后台启动打包产物（双 fork 脱离会话，供 agent/工具调用不被连带杀死）
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/load-env.sh
mkdir -p data/logs

JAR=target/writing-agent-0.1.0-SNAPSHOT.jar
[ -f "$JAR" ] || { echo "❌ 未找到 $JAR，请先 mvn package"; exit 1; }

python3 - "$JAR" <<'PYEOF'
import os, sys, subprocess
jar = sys.argv[1]
pid = os.fork()
if pid == 0:
    os.setsid()
    if os.fork() == 0:
        with open("data/logs/server.out", "ab") as out:
            subprocess.Popen(
                ["java", "-jar", jar],
                stdout=out, stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                start_new_session=True,
            )
    os._exit(0)
os.waitpid(pid, 0)
PYEOF

sleep 1
echo "✅ 已后台启动，日志: data/logs/server.out"
