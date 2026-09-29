package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * p10.1-e1: 伏笔重要性回溯审计——用 LLM 对已有伏笔做批量重要性重评估。
 *
 * 解决问题：旧伏笔（Phase 10 之前提取的）priority 全是默认 MEDIUM，
 * 导致主线关键伏笔（如"水波纹铜钱信物"）和氛围伏笔享有相同优先级，
 * 关键伏笔被 top10 裁剪挤出 + 不触发强制回收。
 *
 * 本 Agent 在项目完成前或手动触发时运行，回溯更新 priority/mustResolve/expectedResolveChapter。
 */
public class ForeshadowingImportanceAuditor {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次审计的伏笔上限（避免 token 超限） */
    private static final int BATCH_SIZE = 30;

    public ForeshadowingImportanceAuditor(LlmGateway gateway, NovelRepository repository,
                                           PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /**
     * 对项目下所有 priority=MEDIUM 的未回收伏笔进行重要性回溯审计。
     * @return 被升级为 HIGH 的伏笔数量
     */
    public int audit(String projectId) {
        Project project = repository.findProject(projectId).orElse(null);
        if (project == null) return 0;

        // 只审计 priority=MEDIUM 的未回收伏笔（LOW 和 HIGH 已有明确定级）
        var toAudit = repository.findUnresolvedForeshadowings(projectId).stream()
                .filter(f -> f.getPriority() == Foreshadowing.Priority.MEDIUM)
                .toList();
        if (toAudit.isEmpty()) return 0;

        // 计算全书总章数（从大纲或已写章节推断）
        int totalChapters = estimateTotalChapters(projectId);
        int currentChapter = project.getCurrentChapterIndex();
        String concept = project.getConcept() != null ? project.getConcept() : "";

        int upgraded = 0;
        // 分批处理（避免单次 LLM token 超限）
        for (int start = 0; start < toAudit.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, toAudit.size());
            var batch = toAudit.subList(start, end);
            upgraded += auditBatch(batch, concept, totalChapters, currentChapter);
        }
        return upgraded;
    }

    private int auditBatch(List<Foreshadowing> batch, String concept,
                            int totalChapters, int currentChapter) {
        // 构建伏笔列表文本
        StringBuilder listBuilder = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            Foreshadowing f = batch.get(i);
            listBuilder.append(i + 1).append(" | ")
                    .append(f.getDescription()).append(" | 埋于第")
                    .append(f.getPlantedAtChapter()).append("章\n");
        }

        String systemPrompt = promptService.render("foreshadowing-audit", Map.of(
                "concept", concept,
                "totalChapters", String.valueOf(totalChapters),
                "currentChapter", String.valueOf(currentChapter),
                "foreshadowingList", listBuilder.toString()));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请评估上述伏笔的重要性。")
                .temperature(0.2)
                .maxTokens(4096)
                .build();

        var resp = gateway.complete(req);
        return applyAudit(resp.getContent(), batch);
    }

    private int applyAudit(String json, List<Foreshadowing> batch) {
        int upgraded = 0;
        try {
            JsonNode root = com.writing.novel.llm.LlmJson.parseOrThrow(json, null);
            JsonNode audits = root.path("audits");
            if (!audits.isArray()) return 0;

            for (JsonNode node : audits) {
                int index = node.path("index").asInt(0);
                if (index < 1 || index > batch.size()) continue;
                Foreshadowing f = batch.get(index - 1);

                String priorityStr = node.path("priority").asText("MEDIUM").toUpperCase();
                Foreshadowing.Priority newPriority;
                try {
                    newPriority = Foreshadowing.Priority.valueOf(priorityStr);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                // 只升级不降级（避免把 MEDIUM 误判为 LOW 后丢失注入）
                if (newPriority.ordinal() < f.getPriority().ordinal()) {
                    f.setPriority(newPriority);
                    if (newPriority == Foreshadowing.Priority.HIGH) {
                        upgraded++;
                        // NCS-082（听雪刀 P1-4）：mustResolve 缺省不再默认 true——
                        // LLM 漏标即全员"必须回收"是 62 条通胀的来源之一，必须显式标注
                        boolean mustResolve = node.path("mustResolve").asBoolean(false);
                        f.setMustResolve(mustResolve);
                        int expected = node.path("expectedResolveChapter").asInt(0);
                        if (expected > 0) f.setExpectedResolveChapter(expected);
                    }
                    repository.saveForeshadowing(f);
                }
            }
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            throw e;
        } catch (Exception e) {
            // NCS-118: 重要性审计失败可见（mustResolve 标注不全会让成书门禁失真）
            throw new com.writing.novel.agent.util.PostProcessStageFailure(
                    "foreshadow-audit", "重要性审计失败: " + e.getMessage(), e);
        }
        return upgraded;
    }

    private int estimateTotalChapters(String projectId) {
        // NCS-082: 总数口径优先作者设定的目标章数（压缩后的有效目标）——
        // 大纲最大章号会被追加的回收卷越推越高，审计不应反过来被自己的产物放大
        int target = repository.findWritingPreferences(projectId)
                .map(p -> p.getTargetChapters()).orElse(0);
        if (target > 0) return target;
        var outline = repository.findOutlineByProject(projectId);
        if (outline.isPresent()) {
            int max = 0;
            for (var v : outline.get().getVolumes()) {
                for (var s : v.getSynopses()) {
                    if (s.getChapterIndex() > max) max = s.getChapterIndex();
                }
            }
            if (max > 0) return max;
        }
        // B2: 用 COUNT 查询代替全表加载
        return repository.countChaptersByProject(projectId);
    }
}
