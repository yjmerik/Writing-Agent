package com.writing.novel.service;

import com.writing.novel.domain.Outline;
import com.writing.novel.domain.VolumeReadback;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.PlanDeviationRepository;
import com.writing.novel.persistence.sqlite.VolumeReadbackRepository;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NCS-020（方案 §06/J08）：卷末回读——按需生成报告（人物变化/节奏/承诺/偏离/建议），
 * PENDING_REVIEW 阻断下一卷自动续写；作者确认后放行（显式继续）。
 */
public class VolumeReadbackService {

    public record GenerateResult(int volumeIndex, boolean generated) {}

    private final NovelRepository repository;
    private final LlmGateway gateway;
    private final PromptTemplateService promptService;
    private final PlanDeviationRepository deviations;
    private final VolumeReadbackRepository readbacks;

    public VolumeReadbackService(NovelRepository repository, LlmGateway gateway,
                                 PromptTemplateService promptService,
                                 PlanDeviationRepository deviations) {
        this(repository, gateway, promptService, deviations, null);
    }

    public VolumeReadbackService(NovelRepository repository, LlmGateway gateway,
                                 PromptTemplateService promptService,
                                 PlanDeviationRepository deviations,
                                 VolumeReadbackRepository readbacks) {
        this.repository = repository;
        this.gateway = gateway;
        this.promptService = promptService;
        this.deviations = deviations;
        this.readbacks = readbacks;
    }

    private VolumeReadbackRepository readbacks() {
        return readbacks;
    }

    /** 为指定卷生成回读报告（真实调用按需触发；坏输出不落库） */
    public GenerateResult generate(String projectId, int volumeIndex) {
        Outline outline = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目尚无大纲"));
        var volume = outline.getVolumes().stream()
                .filter(v -> v.getVolumeIndex() == volumeIndex).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("卷不存在: " + volumeIndex));
        StringBuilder synopses = new StringBuilder();
        volume.getSynopses().forEach(s -> synopses.append("第").append(s.getChapterIndex())
                .append("章 ").append(s.getTitle()).append("：").append(s.getSynopsis()).append("\n"));
        StringBuilder promises = new StringBuilder();
        repository.findUnresolvedForeshadowings(projectId).forEach(f ->
                promises.append(f.getDescription()).append(f.isMustResolve() ? "（必须回收）" : "").append("\n"));
        StringBuilder deviationText = new StringBuilder();
        deviations.findByProject(projectId).forEach(d -> deviationText.append("第")
                .append(d.getChapterIndex()).append("章: ").append(d.getDeviationJson()).append("\n"));

        String systemPrompt = promptService.render("volume-readback", Map.of(
                "volumeSummary", volume.getSummary() == null ? "" : volume.getSummary(),
                "chapterSynopses", synopses.toString(),
                "promiseLedger", promises.length() == 0 ? "（无未回收承诺）" : promises.toString(),
                "deviations", deviationText.length() == 0 ? "（无偏离记录）" : deviationText.toString()));
        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请给出本卷的回读判断。")
                .temperature(0.3)
                .maxTokens(2000)
                .build();
        LlmResponse resp = gateway.complete(req);
        String raw = resp.getContent();
        String json = extractJsonObject(raw);
        if (json == null) {
            return new GenerateResult(volumeIndex, false);
        }
        VolumeReadback r = readbacks().findLatest(projectId, volumeIndex).orElseGet(() -> {
            VolumeReadback fresh = new VolumeReadback();
            fresh.setProjectId(projectId);
            fresh.setVolumeIndex(volumeIndex);
            return fresh;
        });
        r.setReportJson(json);
        r.setStatus(VolumeReadback.PENDING_REVIEW);
        r.setAcknowledgedAt(null);
        readbacks().save(r);
        return new GenerateResult(volumeIndex, true);
    }

    /** 作者确认回读（显式继续下一卷） */
    public VolumeReadback acknowledge(String projectId, int volumeIndex) {
        VolumeReadback r = readbacks().findLatest(projectId, volumeIndex)
                .orElseThrow(() -> new IllegalArgumentException("该卷尚无回读报告"));
        r.setStatus(VolumeReadback.ACKNOWLEDGED);
        r.setAcknowledgedAt(java.time.Instant.now());
        readbacks().save(r);
        EventLog.emit("VOLUME_READBACK_ACKNOWLEDGED", Map.of(
                "projectId", projectId, "volumeIndex", volumeIndex));
        return r;
    }

    public Optional<VolumeReadback> latest(String projectId, int volumeIndex) {
        return readbacks().findLatest(projectId, volumeIndex);
    }

    public List<VolumeReadback> list(String projectId) {
        return readbacks().findByProject(projectId);
    }

    /**
     * NCS-021: 已确认回读的结论文本（人物变化+建议+承诺状态）——
     * 供再规划注入；无确认回读返回空串（既有再规划输入不变）。
     */
    public String buildReadbackContext(String projectId) {
        StringBuilder sb = new StringBuilder();
        for (VolumeReadback r : readbacks().findByProject(projectId)) {
            if (!VolumeReadback.ACKNOWLEDGED.equals(r.getStatus()) || r.getReportJson() == null) continue;
            sb.append("【卷 ").append(r.getVolumeIndex()).append(" 回读结论（按实际正文）】\n");
            try {
                var node = com.writing.novel.llm.LlmJson.parse(r.getReportJson()).node();
                appendIfPresent(sb, node, "characterChanges", "人物变化");
                appendIfPresent(sb, node, "pacing", "节奏观察");
                appendIfPresent(sb, node, "promiseStatus", "承诺状态");
                appendIfPresent(sb, node, "deviationSummary", "偏离汇总");
                appendIfPresent(sb, node, "suggestions", "对后续的建议");
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, com.fasterxml.jackson.databind.JsonNode node,
                                 String field, String label) {
        String value = node.path(field).asText("");
        if (!value.isBlank()) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    /** 是否存在未确认回读（供卷过渡阻断检查） */
    public boolean hasPending(String projectId) {
        return readbacks().findByProject(projectId).stream()
                .anyMatch(r -> VolumeReadback.PENDING_REVIEW.equals(r.getStatus()));
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = raw.substring(start, end + 1);
        var node = com.writing.novel.llm.LlmJson.parse(json);
        if (!node.ok() || !node.node().isObject() || node.node().size() == 0) return null;
        return node.node().toString();
    }
}
