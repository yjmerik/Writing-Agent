package com.writing.novel.service;

import com.writing.novel.domain.VolumePacingDirective;
import com.writing.novel.persistence.sqlite.VolumePacingDirectiveRepository;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NCS-200: 卷级节奏闭环——卷末聚合本卷 TEXTURE_METRICS 均值 + 检测趋势告警 +
 * 读者子分均值 + 回读 pacing 观察文本，持久化为 VolumePacingDirective；
 * 注入下一卷 ①planVolume 卷梗概（tryExtendLayeredOutline）②逐章 writingConstraints。
 *
 * <p>迟滞原则：设定点只在卷边界（closeVolume）更新；卷内消费同一指令，
 * 事件持续流入但不改变已持久化的设定点。
 */
public class VolumePacingController {

    private final VolumePacingDirectiveRepository directives;
    private final NovelRepository repository;
    private final VolumeReadbackService readbackService; // 可空

    /** TEXTURE_METRICS 滚动缓冲（每项目最多 60 条，closeVolume 后清空——迟滞窗口） */
    private final Map<String, Deque<Map<String, Object>>> textureBuffer = new ConcurrentHashMap<>();

    private final EventLog.Listener collector = record -> {
        if (!"TEXTURE_METRICS".equals(record.get("event"))) return;
        Object pid = record.get("projectId");
        if (pid == null) return;
        Deque<Map<String, Object>> deque = textureBuffer
                .computeIfAbsent(pid.toString(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new LinkedHashMap<>(record));
            while (deque.size() > 60) deque.removeFirst();
        }
    };

    public VolumePacingController(VolumePacingDirectiveRepository directives,
                                  NovelRepository repository,
                                  VolumeReadbackService readbackService) {
        this.directives = directives;
        this.repository = repository;
        this.readbackService = readbackService;
        EventLog.subscribe(collector);
    }

    /** 卷末闭合：聚合→指令落库→清空纹理缓冲（迟滞窗口推进到下一卷） */
    public VolumePacingDirective closeVolume(String projectId, int volumeIndex,
                                             int fromChapter, int toChapter, String trendWarning) {
        Map<String, Object> metrics = drainTextureAverage(projectId);
        double readerAvg = -1;
        int reviewCount = 0;
        try {
            var reviews = repository.findChapterReviewsByProject(projectId).stream()
                    .filter(r -> r.getChapterIndex() >= fromChapter && r.getChapterIndex() <= toChapter)
                    .filter(r -> r.getOverallScore() > 0)
                    .toList();
            reviewCount = reviews.size();
            readerAvg = reviews.stream().mapToInt(com.writing.novel.domain.ChapterReview::getOverallScore)
                    .average().orElse(-1);
        } catch (Exception ignored) {
        }
        String pacingText = pacingOf(projectId, volumeIndex);

        String directiveText = buildDirectiveText(volumeIndex, metrics, readerAvg, reviewCount,
                trendWarning, pacingText);

        VolumePacingDirective d = new VolumePacingDirective();
        d.setProjectId(projectId);
        d.setVolumeIndex(volumeIndex);
        d.setFromChapter(fromChapter);
        d.setToChapter(toChapter);
        d.setMetricsJson(com.writing.novel.llm.LlmJson.mapper().valueToTree(metrics).toString());
        d.setReaderAvg(readerAvg);
        d.setReviewCount(reviewCount);
        d.setTrendWarning(trendWarning);
        d.setPacingText(pacingText);
        d.setDirectiveText(directiveText);
        d.setCreatedAt(java.time.Instant.now());
        directives.save(d);
        EventLog.emit("VOLUME_PACING_CLOSED", Map.of(
                "projectId", projectId, "volumeIndex", volumeIndex,
                "reviewAvg", readerAvg, "reviewCount", reviewCount));
        return d;
    }

    /** 下一卷 planVolume 卷梗概注入：volumeIndex 之前最近一条指令；无则空串 */
    public String directiveFor(String projectId, int volumeIndex) {
        try {
            return directives.findLatestBefore(projectId, volumeIndex)
                    .map(VolumePacingDirective::getDirectiveText).orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 逐章写作约束注入：章节所在卷之前最近一条指令；无则空串 */
    public String constraintsFor(String projectId, int chapterIndex) {
        try {
            int volumeIndex = repository.findOutlineByProject(projectId)
                    .flatMap(outline -> outline.getVolumes().stream()
                            .filter(v -> v.getSynopses().stream()
                                    .anyMatch(s -> s.getChapterIndex() == chapterIndex))
                            .map(com.writing.novel.domain.Volume::getVolumeIndex)
                            .findFirst())
                    .orElse(0);
            if (volumeIndex <= 1) return ""; // 第一卷无前卷指令
            return directiveFor(projectId, volumeIndex);
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> drainTextureAverage(String projectId) {
        Deque<Map<String, Object>> deque = textureBuffer.get(projectId);
        Map<String, Object> avg = new LinkedHashMap<>();
        List<Map<String, Object>> drained;
        if (deque == null) {
            drained = List.of();
        } else {
            synchronized (deque) {
                drained = List.copyOf(deque);
                deque.clear();
            }
        }
        if (drained.isEmpty()) return avg;
        for (String key : List.of("dialogueParagraphRatio", "sceneBreaks",
                "dialoguePingPong", "avgParagraphLength")) {
            double mean = drained.stream()
                    .mapToDouble(r -> ((Number) r.getOrDefault(key, 0)).doubleValue())
                    .average().orElse(0);
            avg.put(key, Math.round(mean * 1000.0) / 1000.0);
        }
        avg.put("samples", drained.size());
        return avg;
    }

    private String pacingOf(String projectId, int volumeIndex) {
        if (readbackService == null) return "";
        try {
            return readbackService.latest(projectId, volumeIndex)
                    .map(r -> {
                        if (r.getReportJson() == null) return "";
                        try {
                            var node = com.writing.novel.llm.LlmJson.parse(r.getReportJson()).node();
                            return node.path("pacing").asText("");
                        } catch (Exception e) {
                            return "";
                        }
                    })
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private String buildDirectiveText(int volumeIndex, Map<String, Object> metrics,
                                      double readerAvg, int reviewCount,
                                      String trendWarning, String pacingText) {
        StringBuilder sb = new StringBuilder();
        sb.append("【卷级节奏指令（卷").append(volumeIndex).append(" 末回读，迟滞设定点——卷内不变）】\n");
        sb.append("本卷纹理：");
        if (metrics.isEmpty()) {
            sb.append("无纹理样本；");
        } else {
            sb.append("对白段占比均值 ").append(Math.round(((Number) metrics.getOrDefault(
                            "dialogueParagraphRatio", 0)).doubleValue() * 100)).append("%")
                    .append("、场景数均值 ").append(metrics.getOrDefault("sceneBreaks", 0))
                    .append("、对白乒乓 ").append(metrics.getOrDefault("dialoguePingPong", 0))
                    .append(" 处、段落均长 ").append(metrics.getOrDefault("avgParagraphLength", 0))
                    .append(" 字；");
        }
        if (reviewCount > 0) {
            sb.append("读者均分 ").append(Math.round(readerAvg * 10) / 10.0).append("（").append(reviewCount).append(" 章）；");
        }
        sb.append("趋势：").append(trendWarning == null || trendWarning.isBlank() ? "无告警" : trendWarning).append("；");
        if (pacingText != null && !pacingText.isBlank()) {
            sb.append("回读节奏：").append(pacingText).append("；");
        }
        sb.append("\n下一卷设定点：");
        if (!metrics.isEmpty()) {
            double ratio = ((Number) metrics.getOrDefault("dialogueParagraphRatio", 0)).doubleValue();
            if (ratio > 0.55) sb.append("压低对白密度（对白段占比回落至 55% 以下，连续对白补叙述锚点）；");
            else if (ratio < 0.30) sb.append("提升对白与人物互动（打破单方面陈述）；");
            int pingPong = ((Number) metrics.getOrDefault("dialoguePingPong", 0)).intValue();
            if (pingPong > 0) sb.append("连续对白不超过 3 段；");
        }
        if (reviewCount > 0 && readerAvg < 7) {
            sb.append("每章强化一个核心张力点并前置钩子；");
        }
        sb.append("保持每章 2-4 个场景的节拍骨架。");
        return sb.toString();
    }
}
