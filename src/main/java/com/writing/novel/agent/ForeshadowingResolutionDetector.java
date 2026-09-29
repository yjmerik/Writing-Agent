package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
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
     * p10-c1: 不再截断到前 2000 字——回收常发生在章末。
     * 对超长章节分段检测，确保全文覆盖。
     * @return 本章回收的伏笔数量
     */
    public int detectAndResolve(String projectId, int chapterIndex, String chapterContent) {
        var unresolved = repository.findUnresolvedForeshadowings(projectId);
        if (unresolved.isEmpty()) return 0;

        // 构建伏笔列表文本（标注重要性和过期状态）
        String foreshadowingList = unresolved.stream()
                .map(f -> {
                    StringBuilder line = new StringBuilder("- ").append(f.getDescription());
                    line.append("（埋于第").append(f.getPlantedAtChapter()).append("章");
                    if (f.getPriority() == Foreshadowing.Priority.HIGH) line.append("，★重要");
                    if (f.isMustResolve()) line.append("，必须回收");
                    if (f.isOverdue(chapterIndex)) line.append("，已过期");
                    line.append("）");
                    return line.toString();
                })
                .collect(Collectors.joining("\n"));

        // p10-c1: 不截断——传完整正文。超长时分段检测。
        String content = chapterContent != null ? chapterContent : "";
        int totalResolved = 0;

        if (content.length() <= 6000) {
            // 单次检测
            totalResolved = detectInSegment(foreshadowingList, content, projectId, chapterIndex, unresolved);
        } else {
            // 分段检测（每段 6000 字，重叠 500 字防止割裂回收段）
            int segmentLen = 6000;
            int overlap = 500;
            for (int start = 0; start < content.length(); start += segmentLen - overlap) {
                int end = Math.min(start + segmentLen, content.length());
                String segment = content.substring(start, end);
                totalResolved += detectInSegment(foreshadowingList, segment, projectId, chapterIndex, unresolved);
                if (end >= content.length()) break;
                // 刷新未回收列表（前段可能已回收部分）
                unresolved = repository.findUnresolvedForeshadowings(projectId);
                if (unresolved.isEmpty()) break;
                foreshadowingList = unresolved.stream()
                        .map(f -> "- " + f.getDescription() + "（埋于第" + f.getPlantedAtChapter() + "章）")
                        .collect(Collectors.joining("\n"));
            }
        }
        return totalResolved;
    }

    private int detectInSegment(String foreshadowingList, String contentSegment,
                                 String projectId, int chapterIndex,
                                 List<Foreshadowing> unresolved) {
        String systemPrompt = promptService.render("foreshadowing-resolve-check", Map.of(
                "foreshadowings", foreshadowingList,
                "chapterContent", contentSegment));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请检查本章回收了哪些伏笔。")
                .temperature(0.2)
                .maxTokens(1500)
                .build();

        var resp = gateway.complete(req);
        return parseAndMarkResolved(resp.getContent(), projectId, chapterIndex, unresolved);
    }

    private int parseAndMarkResolved(String json, String projectId, int chapterIndex,
                                      List<Foreshadowing> unresolved) {
        try {
            JsonNode root = com.writing.novel.llm.LlmJson.parseOrThrow(json, null);
            JsonNode resolvedArray = root.path("resolved");
            if (!resolvedArray.isArray()) return 0;

            int count = 0;
            for (JsonNode node : resolvedArray) {
                String desc = node.path("description").asText("");
                if (desc.isBlank()) continue;
                // p32-08（体验报告 #4/P1）: 回收必须给出证据——reason 缺失/空白
                // 视为"再次提及"而非回收，不得置 resolved（报告实测：仅再出现日期被误判回收）
                String evidence = node.path("reason").asText("").trim();
                if (evidence.isEmpty()) continue;

                // p10-c1: 改进匹配——从前 6 字模糊匹配升级为语义匹配
                // 策略：取 LLM 返回描述的关键词（≥2字），与库存伏笔描述做关键词交集
                for (var f : unresolved) {
                    if (f.isResolved()) continue;
                    if (descriptionsMatch(f.getDescription(), desc)) {
                        f.resolve(chapterIndex, evidence);
                        repository.saveForeshadowing(f);
                        count++;
                        break;
                    }
                }
            }
            return count;
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            throw e;
        } catch (Exception e) {
            // NCS-118: 回收检测失败不得伪装成"本章无回收"（伏笔状态会错误悬置）
            throw new com.writing.novel.agent.util.PostProcessStageFailure(
                    "foreshadow-resolution", "回收检测失败: " + e.getMessage(), e);
        }
    }

    /**
     * p10-c1: 语义匹配——替代旧的 6 字前缀 contains 匹配。
     * 匹配条件（满足任一）：
     * 1. 双向包含（一个描述包含另一个的 ≥8 字子串）
     * 2. 关键词交集 ≥2 个（关键词 = ≥2 字的非停用词片段）
     */
    private boolean descriptionsMatch(String libraryDesc, String llmDesc) {
        if (libraryDesc == null || llmDesc == null) return false;
        // 双向长子串包含
        int prefixLen = Math.min(8, Math.min(libraryDesc.length(), llmDesc.length()));
        if (libraryDesc.contains(llmDesc.substring(0, prefixLen))) return true;
        if (llmDesc.contains(libraryDesc.substring(0, prefixLen))) return true;
        // 关键词交集（提取 2-4 字的中文词组）
        long commonKeywords = extractKeywords(libraryDesc).stream()
                .filter(k -> llmDesc.contains(k))
                .count();
        return commonKeywords >= 2;
    }

    /** 粗略提取中文关键词（2-4 字连续中文片段） */
    private List<String> extractKeywords(String text) {
        List<String> keywords = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,4}").matcher(text);
        while (m.find()) {
            keywords.add(m.group());
        }
        return keywords;
    }
}
