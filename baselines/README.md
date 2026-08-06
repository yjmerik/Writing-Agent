# E2E 质量基线

`golden.json` 不是手写配置。首次在 `record` 模式成功跑完 E2E 后，人工审阅输出质量，确认可接受后执行：

```bash
python3 scripts/compare-baseline.py --bless
```

该命令从 `e2e-report.json` 生成基线。随后每次修改 prompt、上下文组装或 Agent 逻辑，都必须运行：

```bash
./scripts/e2e.sh
python3 scripts/compare-baseline.py
```

只有人为确认新的产出质量应成为标准时，才允许再次使用 `--bless`。
