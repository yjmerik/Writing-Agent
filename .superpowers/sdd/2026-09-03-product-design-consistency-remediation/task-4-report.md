# p26-04 — 独立章节重写接线与反馈

日期：2026-09-03  
任务：无 pending checkpoint 时，章节卡“重写此章”走独立目标与提交路径，并提供真实成功或失败反馈。

## RED

先运行严格浏览器场景：

```text
./scripts/product-audit-e2e.sh --case standalone-chapter-rewrite
PRODUCT-AUDIT-E2E FAIL mode=strict pass=0 xfail=1 error=0
```

断言详情为 `opened=true`、`submitted=false`、`modalClosed=false`、`beforeVersion=3`、`afterVersion=3`、`contentChanged=false`。原因是章节卡只打开弹窗，却没有设置 checkpoint 目标；旧 footer 因而无法选择提交动作。

## GREEN

- `RewriteTarget` 现在是 discriminated union。章节路径为 `{ kind: 'chapter', chapterIndex, sourceVersion }`，不读取 checkpoint。
- 章节提交仅调用既有 `api.rewriteChapter(projectId, target.chapterIndex, instructions)`；成功后更新章节元数据和正文缓存，提示实际 `v旧 → v新`，再关闭/重置弹窗。
- 失败时不关闭弹窗，且在弹窗内显示错误 Alert。
- 浏览器 case 使用可区分的 scenario fixture 正文，并断言请求已提交、弹窗关闭、版本上升、持久化正文变化及新正文仍在页面可见；只从 `KNOWN_FAILURES` 移除该 case。

```text
PRODUCT-AUDIT-E2E PASS mode=strict pass=1 xfail=0 error=0
opened=true, submitted=true, modalClosed=true,
beforeVersion=3, afterVersion=4, contentChanged=true, updatedBodyVisible=true
```

## Review round 1 — E2E proof hardening

本轮不改产品代码。先前的 `updatedBodyVisible` 只匹配正文前 28 个字符；scenario 的新旧正文共享该前缀，因此它不足以单独证明缓存已替换。

- 重写开始前读取 `/checkpoints`，并将 `pendingBefore.length === 0` 作为 strict PASS 条件。
- 保留“重写正文可见”断言，同时新增 `staleSourceMarkerAbsent`：页面不能包含测试夹具写入旧正文的唯一 `【重写前来源标记】`。两项合取可排除仍渲染旧缓存的假阳性。

这属于已修复路径的测试加固，不能在不回退产品修复的前提下产生新的 RED；旧实现本身已由本报告 RED 记录的 `submitted=false` 覆盖。加固后的严格结果：

```text
PRODUCT-AUDIT-E2E PASS mode=strict pass=1 xfail=0 error=0
opened=true, submitted=true, modalClosed=true,
beforeVersion=3, afterVersion=4, contentChanged=true,
pendingBefore=0, rewrittenBodyVisible=true, staleSourceMarkerAbsent=true
```

## 门禁

- `cd frontend && npm run build`：PASS。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn compile -q`：PASS。
- 同一 JDK 的 `mvn test`：504 tests / 0 failures / 0 errors / 1 skipped。
- 同一 JDK 的 `./init.sh`：PASS；包含 504 tests、27 个 prompt 模板检查与生产前端构建。
- Review round 1：`frontend npm run build`、JDK21 `mvn test`、JDK21 `./init.sh` 均再次 PASS（504 tests / 0 failures / 0 errors / 1 skipped）。

## 自审

- 没有修改 checkpoint 的目标选择、审批缓存（p26-05）、Agent preview/history 或自动任务路径。
- API 客户端已存在且类型正确的 `rewriteChapter`，因此未作无效改动；前端仅复用它。
- 成功前不关闭或重置弹窗；失败会保留指令与目标，便于修正后重试。

## Concerns

- 前端生产构建仍报告主 bundle 大于 500 kB 的既有提示；不属于本 feature 范围。

## Commit

`3385891` — `fix(chapter): wire standalone rewrite dialog`
