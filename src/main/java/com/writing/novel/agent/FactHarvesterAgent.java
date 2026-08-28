package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.StoryFactRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * p10-w2-03: 故事真典增量收割 Agent。
 * 每章写完后从正文提取事实变更，与现有 ACTIVE 真典比对：
 * - 新事实 → save（importance 3）
 * - 一致（同 subject+type，assertion 相似）→ validateAt 刷新最近确认章
 * - 冲突（assertion 显著不同）→ supercede（视为剧情合法演进，旧事实 SUPERSEDED，审计链保留）
 * 为长程记忆提供随章节增长、不衰减的结构化真相，替代级联压缩的 book_summary。
 */
public class FactHarvesterAgent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FactHarvesterAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LlmGateway gateway;
    private final StoryFactRepository factRepo;
    private final PromptTemplateService promptService;

    public FactHarvesterAgent(LlmGateway gateway, StoryFactRepository factRepo, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.factRepo = factRepo;
        this.promptService = promptService;
    }

    public record HarvestResult(int added, int validated, int superseded) {
        public int total() { return added + validated + superseded; }
    }

    /** p16-03: 收割 prompt 中既有事实清单的硬上限——千条事实全量注入 ≈33k token 会爆窗 */
    static final int FACT_LIST_CAP = 80;

    public HarvestResult harvest(String projectId, Chapter chapter) {
        // 现有 ACTIVE 真典（供 LLM 去重 + Java 层比对）
        // p16-03: 按相关性收窄——正文 cast 相关（subject 出现在正文中）优先，其余按重要性/新鲜度补足，
        // 硬上限 FACT_LIST_CAP；未列入清单的事实仍参与 Java 层 merge 比对（findActiveBySubject 按需查）
        List<StoryFact> existing = factRepo.findActive(projectId);
        String content = chapter.getContent() != null ? chapter.getContent() : "";
        List<StoryFact> scoped = existing.stream()
                .sorted(java.util.Comparator
                        .comparing((StoryFact f) -> content.contains(f.getSubject()) ? 0 : 1)
                        .thenComparing(java.util.Comparator.comparingInt(StoryFact::getImportance).reversed())
                        .thenComparing(java.util.Comparator.comparingInt(StoryFact::getLastValidatedChapter).reversed()))
                .limit(FACT_LIST_CAP)
                .toList();
        String existingFacts = scoped.stream()
                .map(f -> "- [" + f.getFactType() + "] " + f.getSubject() + "：" + f.getAssertion())
                .collect(Collectors.joining("\n"));

        String chapterContent = content;
        if (chapterContent.length() > 4000) chapterContent = chapterContent.substring(0, 4000);

        boolean truncatedList = existing.size() > scoped.size();
        String systemPrompt = promptService.render("fact-harvest", Map.of(
                "existingFacts", existingFacts.isEmpty() ? "（暂无）"
                        : (truncatedList ? "（以下为按相关性检索的既有事实子集，未列出不代表不存在）\n" : "") + existingFacts,
                "chapterContent", chapterContent));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请提取第" + chapter.getChapterIndex() + "章确立的事实。")
                .temperature(0.2)
                .maxTokens(2000)
                .build();

        try {
            var resp = gateway.complete(req);
            return merge(resp.getContent(), projectId, chapter.getChapterIndex());
        } catch (Exception e) {
            // p16-03: fail-loud——真典停止生长必须可观测，不再静默吞掉
            log.warn("真典收割 LLM 调用失败(项目{}, 第{}章): {}", projectId, chapter.getChapterIndex(), e.getMessage());
            com.writing.novel.telemetry.EventLog.emit("LLM_ERROR", Map.of(
                    "taskType", LlmTaskType.GENERAL.name(),
                    "errorClass", e.getClass().getSimpleName(),
                    "message", com.writing.novel.telemetry.EventLog.truncate(e.getMessage(), 200),
                    "phase", "fact-harvest"));
            return new HarvestResult(0, 0, 0);
        }
    }

    /** 解析 LLM 输出的 JSON 数组，与真典比对后落库 */
    private HarvestResult merge(String json, String projectId, int chapterIndex) {
        int added = 0, validated = 0, superseded = 0;
        try {
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end < 0) return new HarvestResult(0, 0, 0);
            JsonNode arr = MAPPER.readTree(json.substring(start, end + 1));
            for (JsonNode item : arr) {
                StoryFact.FactType type;
                try { type = StoryFact.FactType.valueOf(item.path("type").asText()); }
                catch (IllegalArgumentException badType) { continue; }
                String subject = item.path("subject").asText();
                String assertion = item.path("assertion").asText();
                if (subject.isBlank() || assertion.isBlank()) continue;

                List<StoryFact> sameSubject = factRepo.findActiveBySubject(projectId, subject).stream()
                        .filter(f -> f.getFactType() == type).toList();
                if (sameSubject.isEmpty()) {
                    StoryFact f = new StoryFact(projectId, type, subject, assertion, chapterIndex);
                    f.setImportance(3);
                    factRepo.save(f);
                    added++;
                } else {
                    StoryFact old = sameSubject.get(0);
                    if (old.getAssertion().contains(assertion) || assertion.contains(old.getAssertion())) {
                        old.validateAt(chapterIndex);
                        factRepo.save(old);
                        validated++;
                    } else {
                        // 冲突 → supercede（剧情合法演进；正文错误的情形由 ContinuityChecker 另行检测）
                        StoryFact newer = new StoryFact(projectId, type, subject, assertion, chapterIndex);
                        factRepo.supersede(old, newer);
                        superseded++;
                    }
                }
            }
        } catch (Exception ignored) { }
        return new HarvestResult(added, validated, superseded);
    }
}
