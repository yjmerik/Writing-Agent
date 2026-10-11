package com.writing.novel.service;

import com.writing.novel.telemetry.EventLog;

import java.util.Map;

/**
 * NCS-034/035: 创作约定消费帮助器——世界/人物/卷计划（规划类）与生成修订/核查
 * 请求统一携带「精确契约版本及内容」；所用修订号经 AUTHOR_INTENT_USED 事件审计。
 * 空契约零注入（请求不变）；约定段永不截断（超预算由 render 抛错，调用方降级跳过）。
 */
public final class AuthorIntentConsumeSupport {

    private AuthorIntentConsumeSupport() {}

    /**
     * 渲染约定段并审计。空契约返回空串（调用方零注入）；
     * 超预算等渲染失败按无约定降级（返回空串，事件化），不阻断创作。
     */
    public static String sectionFor(AuthorIntentContextService service, String projectId,
                                    int maxTokens, String context) {
        if (service == null) return "";
        try {
            String section = service.render(projectId, maxTokens);
            if (section == null || section.isBlank()) return "";
            EventLog.emit("AUTHOR_INTENT_USED", Map.of(
                    "context", context, "revision", service.currentRevision(projectId)));
            return section;
        } catch (Exception e) {
            EventLog.emit("AUTHOR_INTENT_SKIPPED", Map.of(
                    "context", context, "reason", EventLog.truncate(String.valueOf(e.getMessage()), 120)));
            return "";
        }
    }
}
