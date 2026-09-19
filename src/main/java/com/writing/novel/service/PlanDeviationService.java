package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.PlanDeviation;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.PlanDeviationRepository;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.List;
import java.util.Map;

/**
 * NCS-019（方案 §06）：计划偏离对照与双向校正。
 * 按需分析（章正文 vs 计划）→ 偏离记录；三种决定：
 * 按正文更新计划（synopsis 更新为实际发生，已写章不动）/ 修订正文（创建统一修订请求，
 * 进入修订工作区）/ 稍后决定（挂起，可再决定）。
 */
public class PlanDeviationService {

    public record AnalysisResult(int chapterIndex, boolean analyzed) {}

    private final NovelRepository repository;
    private final RevisionRequestService requests;
    private final PlanDeviationRepository deviations;
    private final LlmGateway gateway;
    private final PromptTemplateService promptService;

    public PlanDeviationService(NovelRepository repository, RevisionRequestService requests,
                                LlmGateway gateway, PromptTemplateService promptService,
                                PlanDeviationRepository deviations) {
        this.repository = repository;
        this.requests = requests;
        this.gateway = gateway;
        this.promptService = promptService;
        this.deviations = deviations;
    }

    private PlanDeviationRepository deviations() {
        return deviations;
    }

    public AnalysisResult analyze(String projectId, int chapterIndex) {
        Chapter chapter = repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
        Outline outline = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目尚无大纲"));
        ChapterSynopsis synopsis = outline.getVolumes().stream()
                .flatMap(v -> v.getSynopses().stream())
                .filter(s -> s.getChapterIndex() == chapterIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("章节梗概不存在: " + chapterIndex));

        String systemPrompt = promptService.render("plan-deviation", Map.of(
                "synopsis", synopsis.getSynopsis() == null ? "" : synopsis.getSynopsis(),
                "sceneContract", synopsis.getSceneContract() == null ? "（未定义）" : synopsis.getSceneContract(),
                "chapterContent", chapter.getContent() == null ? "" : chapter.getContent()));
        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请判定该章的计划偏离。")
                .temperature(0.2)
                .maxTokens(1200)
                .build();
        LlmResponse resp = gateway.complete(req);
        String raw = resp.getContent();
        String json = extractJsonObject(raw);
        if (json == null) {
            return new AnalysisResult(chapterIndex, false);
        }
        PlanDeviation d = deviations().findLatest(projectId, chapterIndex).orElseGet(() -> {
            PlanDeviation fresh = new PlanDeviation();
            fresh.setProjectId(projectId);
            fresh.setChapterIndex(chapterIndex);
            return fresh;
        });
        d.setChapterVersion(chapter.getVersion());
        d.setDeviationJson(json);
        deviations().save(d);
        EventLog.emit("PLAN_DEVIATION_RECORDED", Map.of(
                "projectId", projectId, "chapterIndex", chapterIndex));
        return new AnalysisResult(chapterIndex, true);
    }

    /** 决定三选一；按正文更新计划只改 synopsis，已写章正文不动 */
    public PlanDeviation decide(String projectId, int chapterIndex, String action) {
        if (!List.of(PlanDeviation.UPDATE_PLAN_FROM_TEXT, PlanDeviation.REVISE_TEXT, PlanDeviation.DEFER)
                .contains(action)) {
            throw new IllegalArgumentException("未知决定: " + action);
        }
        PlanDeviation d = deviations().findLatest(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("该章尚无偏离记录（先分析）"));
        if (PlanDeviation.UPDATE_PLAN_FROM_TEXT.equals(action)) {
            updateSynopsisFromText(projectId, chapterIndex, d.getDeviationJson());
        } else if (PlanDeviation.REVISE_TEXT.equals(action)) {
            requests.record(projectId, "PLAN_DEVIATION",
                    "第 " + chapterIndex + " 章偏离计划，按原计划修正文",
                    "[{\"chapterIndex\":" + chapterIndex + "}]", "{}",
                    "[]");
        }
        d.setDecision(action);
        d.setDecidedAt(java.time.Instant.now());
        deviations().save(d);
        EventLog.emit("PLAN_DEVIATION_DECIDED", Map.of(
                "projectId", projectId, "chapterIndex", chapterIndex, "decision", action));
        return d;
    }

    public List<PlanDeviation> list(String projectId) {
        return deviations().findByProject(projectId);
    }

    /** 按正文更新计划：实际发生（realized/replaced 摘要）成为新梗概；已写章正文不动 */
    private void updateSynopsisFromText(String projectId, int chapterIndex, String deviationJson) {
        Outline outline = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目尚无大纲"));
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(deviationJson);
            String realized = node.path("realized").asText("");
            String replaced = node.path("replaced").asText("");
            StringBuilder updated = new StringBuilder();
            if (!realized.isBlank()) updated.append("【实际发生】").append(realized);
            if (!replaced.isBlank()) {
                if (updated.length() > 0) updated.append("；");
                updated.append("【自然替代】").append(replaced);
            }
            if (updated.length() == 0) updated.append("（按正文更新：详见偏离记录）");
            for (var volume : outline.getVolumes()) {
                for (ChapterSynopsis s : volume.getSynopses()) {
                    if (s.getChapterIndex() == chapterIndex) {
                        s.setSynopsis(updated.toString());
                    }
                }
            }
            repository.saveOutline(outline);
        } catch (Exception e) {
            throw new IllegalStateException("按正文更新计划失败: " + e.getMessage(), e);
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = raw.substring(start, end + 1);
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (!node.isObject()) return null;
            return node.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
