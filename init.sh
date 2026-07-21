#!/usr/bin/env bash
set -e

echo "=== WritingAgent 环境验证 ==="

# 1. 编译检查
echo "▶ 编译中..."
mvn compile -q 2>&1 | tail -3
if [ $? -eq 0 ]; then
    echo "✅ 编译通过"
else
    echo "❌ 编译失败"
    exit 1
fi

# 2. 测试检查
echo ""
echo "▶ 运行测试..."
mvn test 2>&1 | grep -E "Tests run:|BUILD"
if [ $? -eq 0 ]; then
    echo "✅ 测试全部通过"
else
    echo "❌ 测试失败"
    exit 1
fi

# 3. 数据目录检查
echo ""
if [ -d "data" ]; then
    echo "✅ data/ 目录存在"
else
    echo "⚠️ data/ 不存在（首次运行会自动创建）"
fi

# 4. Prompt 模板检查
TEMPLATE_COUNT=$(find src/main/resources/prompts -name "*.st" 2>/dev/null | wc -l | xargs)
if [ "$TEMPLATE_COUNT" -ge 9 ]; then
    echo "✅ Prompt 模板完整 ($TEMPLATE_COUNT 个)"
else
    echo "⚠️ Prompt 模板不完整 ($TEMPLATE_COUNT/9)"
fi

# 5. 前端构建检查
if [ -f "src/main/resources/static/index.html" ]; then
    echo "✅ 前端已构建"
else
    echo "⚠️ 前端未构建（cd frontend && npm run build）"
fi

echo ""
echo "=== 验证完成 ==="
echo "启动服务: mvn spring-boot:run"
echo "浏览器访问: http://localhost:8080"
