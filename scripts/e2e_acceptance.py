"""Shared E2E acceptance. A smoke result is never full quality acceptance."""
def assess(report, mode='full'):
    reasons = []
    if mode not in ('full', 'smoke'):
        return False, ['非法验收模式']
    if report.get('totalChapters', 0) < 1:
        reasons.append('没有生成章节')
    for field in ('llmErrors', 'chapterFailures'):
        if report.get(field) != 0:
            reasons.append(f'{field} 非零或缺失')
    if report.get('taskStatus') not in ('COMPLETED', 'PAUSED'):
        reasons.append('任务未正常结束')
    if mode == 'full':
        requested = report.get('requestedChapters', 0)
        if not isinstance(requested, int) or requested < 1:
            reasons.append('请求章数无效')
        elif report.get('totalChapters') != requested or report.get('chaptersWritten') != requested:
            reasons.append('未完成全部请求章节')
        if report.get('taskStatus') != 'COMPLETED':
            reasons.append('写作任务尚未完成')
        if report.get('finalState') not in ('FINAL', 'WRITING', 'VOLUME_DONE'):
            reasons.append('项目处于待处理状态')
        for field in ('activeReviewBarriers', 'pendingReviewChapters', 'unresolvedHighIssues'):
            if report.get(field) != 0:
                reasons.append(f'{field} 非零或未取得质量证据')
    return not reasons, reasons


def quality_counts(workspace, qualities):
    """Count current draft issues only; missing evidence must fail closed."""
    barriers = len(workspace['todoList'])
    pending = sum(q['needsManualReview'] or q['qualityRisk'] for q in qualities)
    high = sum(1 for q in qualities for i in q['issues']
               if i['chapterVersion'] == q['chapterVersion']
               and i['severity'] == 'HIGH' and not i['resolved'])
    return dict(activeReviewBarriers=barriers, pendingReviewChapters=pending,
                unresolvedHighIssues=high)
