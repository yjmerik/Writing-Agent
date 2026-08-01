package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 伏笔回收检测器——用 LLM 判断本章是否回收了某个未回收伏笔。
 * 替代旧的关键词匹配检测（p5-c1），准确率大幅提升。
 */
public class ForeshadowingResolutionDetector {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ForeshadowingResolutionDetector(LlmGateway gateway, NovelRepository repository,
                                            PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /**
     * 检测本章是否回收了未回收伏笔，并标记已回收的。
     * @return 本章回收的伏笔数量
     */
    public int detectAndResolve(String projectId, int chapterIndex, String chapterContent) {
        var unresolved = repository.findUnresolvedForeshadowings(projectId);
        if (unresolved.isEmpty()) return 0;

        // 构建伏笔列表文本
        String foreshadowingList = unresolved.stream()
                .map(f -> "- " + f.getDescription() + "（埋于第" + f.getPlantedAtChapter() + "章）")
                .collect(Collectors.joining("\n"));

        String content = chapterContent != null
                ? (chapterContent.length() > 2000 ? chapterContent.substring(0, 2000) : chapterContent)
                : "";

        String systemPrompt = promptService.render("foreshadowing-resolve-check", Map.of(
                "foreshadowings", foreshadowingList,
                "chapterContent", content));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请检查本章回收了哪些伏笔。")
                .temperature(0.2)
                .maxTokens(1500)
                .build();

        try {
            var resp = gateway.complete(req);
            return parseAndMarkResolved(resp.getContent(), projectId, chapterIndex, unresolved);
        } catch (Exception e) {
            return 0;
        }
    }

    private int parseAndMarkResolved(String json, String projectId, int chapterIndex,
                                      List<Foreshadowing> unresolved) {
        try {
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);
            JsonNode resolvedArray = root.path("resolved");
            if (!resolvedArray.isArray()) return 0;

            int count = 0;
            for (JsonNode node : resolvedArray) {
                String desc = node.path("description").asText("");
                if (desc.isBlank()) continue;

                // 模糊匹配伏笔（描述包含或被包含）
                for (var f : unresolved) {
                    if (f.isResolved()) continue;
                    String fDesc = f.getDescription();
                    if (fDesc.contains(desc.substring(0, Math.min(6, desc.length())))
                            || desc.contains(fDesc.substring(0, Math.min(6, fDesc.length())))) {
                        f.resolve(chapterIndex);
                        repository.saveForeshadowing(f);
                        count++;
                        break;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
}
