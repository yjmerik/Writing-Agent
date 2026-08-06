#!/usr/bin/env bash
set -euo pipefail

echo "=== WritingAgent 环境验证 ==="

echo "▶ 编译中..."
mvn compile -q
echo "✅ 编译通过"

echo ""
echo "▶ 运行测试..."
mvn test
echo "✅ 测试全部通过"

echo ""
mkdir -p data/logs data/fixtures/llm
echo "✅ data/、data/logs/、data/fixtures/llm/ 目录就绪"

TEMPLATE_COUNT=$(find src/main/resources/prompts -name "*.st" | wc -l | xargs)
if [ "$TEMPLATE_COUNT" -lt 20 ]; then
    echo "❌ Prompt 模板不完整 ($TEMPLATE_COUNT/20)"
    exit 1
fi
echo "✅ Prompt 模板完整 ($TEMPLATE_COUNT 个)"

echo ""
echo "▶ 构建前端..."
(cd frontend && npm run build)
echo "✅ 前端构建通过"

echo ""
echo "=== 验证完成 ==="
echo "启动服务: mvn spring-boot:run"
echo "浏览器访问: http://localhost:8080"
