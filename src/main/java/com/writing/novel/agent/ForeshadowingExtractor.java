package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.service.ChapterRevisionService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 伏笔提取 Agent——每章写后从正文中识别新埋下的伏笔。
 *
 * 与大纲提取的区别：
 * - 大纲提取：一次性从 outline 文本正则匹配（plantedAtChapter=1，硬上限5）
 * - 本 Agent：每章 LLM 分析正文，plantedAtChapter=实际章节号，无上限
 */
public class ForeshadowingExtractor {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ForeshadowingExtractor(LlmGateway gateway, NovelRepository repository,
                                   PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /**
     * 从章节正文中提取新伏笔。
     * @return 新提取的伏笔数量
     */
    /** p16-04: 既有伏笔清单注入上限——全量注入（含已回收）随章节无界增长 */
    static final int EXISTING_LIST_CAP = 20;

    public int extract(String projectId, Chapter chapter) {
        return extract(projectId, chapter, null);
    }

    public int extract(String projectId, Chapter chapter,
                       ChapterRevisionService.DerivedWriteBoundary boundary) {
        // 已有伏笔清单（防重复）；p16-04: mustResolve 钉住 + 未回收优先 + 最近埋设优先，cap 20
        // （parseAndSave 的去重仍对全量 existing 做，清单收窄只影响 prompt 展示）
        var existing = repository.findForeshadowingsByProject(projectId);
        String existingList = existing.stream()
                .sorted(java.util.Comparator
                        .comparing(Foreshadowing::isMustResolve).reversed()
                        .thenComparing(Foreshadowing::isResolved)
                        .thenComparing(java.util.Comparator.comparingInt(Foreshadowing::getPlantedAtChapter).reversed()))
                .limit(EXISTING_LIST_CAP)
                .map(Foreshadowing::getDescription)
                .collect(Collectors.joining("\n- ", "- ", ""));

        String systemPrompt = promptService.render("foreshadowing-extract", Map.of(
                "chapterContent", chapter.getContent(),
                "existingForeshadowings", existingList));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请提取第" + chapter.getChapterIndex() + "章中新埋下的伏笔。")
                .temperature(0.3)
                .maxTokens(2000)
                .build();

        var resp = gateway.complete(req);
        var write = (java.util.function.Supplier<Integer>) () ->
                parseAndSave(resp.getContent(), projectId, chapter.getChapterIndex(), existing);
        return boundary == null ? write.get() : boundary.apply(write, 0);
    }

    private int parseAndSave(String json, String projectId, int chapterIndex, List<Foreshadowing> existing) {
        int count = 0;
        try {
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);
            JsonNode array = root.path("foreshadowings");
            if (!array.isArray()) return 0;

            var existingDescs = existing.stream()
                    .map(Foreshadowing::getDescription)
                    .collect(Collectors.toSet());

            for (JsonNode node : array) {
                String desc = node.path("description").asText("");
                String context = node.path("context").asText("从正文提取");
                if (desc.isBlank() || desc.length() < 5) continue;

                // 去重：检查是否与已有伏笔描述高度相似
                boolean isDuplicate = existingDescs.stream()
                        .anyMatch(ed -> ed.contains(desc.substring(0, Math.min(6, desc.length())))
                                || desc.contains(ed.substring(0, Math.min(6, ed.length()))));
                if (isDuplicate) continue;

                Foreshadowing f = new Foreshadowing(projectId, desc, context, chapterIndex);

                // p10-c1: 读取 LLM 标注的重要性和必须回收标记
                String priorityStr = node.path("priority").asText("MEDIUM").toUpperCase();
                try {
                    f.setPriority(Foreshadowing.Priority.valueOf(priorityStr));
                } catch (IllegalArgumentException ignored) {
                    f.setPriority(Foreshadowing.Priority.MEDIUM);
                }
                boolean mustResolve = node.path("mustResolve").asBoolean(false);
                f.setMustResolve(mustResolve || f.getPriority() == Foreshadowing.Priority.HIGH);

                repository.saveForeshadowing(f);
                existingDescs.add(desc);
                count++;
            }
        } catch (Exception ignored) {
        }
        return count;
    }
}
