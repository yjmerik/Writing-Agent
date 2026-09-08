package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.StoryCanon;
import com.writing.novel.service.ChapterRevisionService;

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
    private final StoryCanon canon; // p22-02: 只依赖真典端口（保护窗口/唯一性在端口实现内）
    private final PromptTemplateService promptService;
    private com.writing.novel.service.EntityGraphService entityGraphService; // p19-02: 可选注入，subject 别名规范化

    /** p19-01: 可选注入——死亡事实自动置位 Character.status（null 安全降级） */
    private com.writing.novel.spi.NovelRepository novelRepository;

    public void setNovelRepository(com.writing.novel.spi.NovelRepository novelRepository) {
        this.novelRepository = novelRepository;
    }

    /** p19-01: 死亡关键词——CHARACTER_STATE 断言命中时自动置位 DECEASED */
    private static final java.util.List<String> DEATH_KEYWORDS = java.util.List.of(
            "身亡", "死亡", "战死", "殒命", "毙命", "气绝", "断气", "咽气", "殉职", "殉国", "殉情", "已死", "丧命", "遇害", "被杀", "离世", "病逝");

    /** p19-01: 死亡事实 → 角色置位 DECEASED + deathChapter */
    private void detectDeath(String projectId, String subject, String assertion, int chapterIndex) {
        if (novelRepository == null || subject == null || assertion == null) return;
        boolean death = DEATH_KEYWORDS.stream().anyMatch(assertion::contains);
        if (!death) return;
        try {
            novelRepository.findCharactersByProject(projectId).stream()
                    .filter(c -> subject.equals(c.getName()) || subject.contains(c.getName()))
                    .filter(c -> !c.isDeceased())
                    .findFirst()
                    .ifPresent(c -> {
                        c.setStatus(com.writing.novel.domain.Character.Status.DECEASED);
                        c.setDeathChapter(chapterIndex);
                        novelRepository.saveCharacter(c);
                        log.warn("角色死亡检测: {} 于第{}章身亡（真典: {}）", c.getName(), chapterIndex,
                                assertion.length() > 40 ? assertion.substring(0, 40) : assertion);
                    });
        } catch (Exception e) {
            log.debug("死亡状态置位失败: {}", e.getMessage());
        }
    }

    public FactHarvesterAgent(LlmGateway gateway, StoryCanon canon, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.canon = canon;
        this.promptService = promptService;
    }

    /**
     * p19-02: 注入 EntityGraphService，merge 前把 subject 别名（如「林公子」）解析为规范名（「林凡」），
     * 防止真典按别名分裂成平行 ACTIVE 事实。null 时安全降级为原精确字符串匹配行为。
     */
    public void setEntityGraphService(com.writing.novel.service.EntityGraphService entityGraphService) {
        this.entityGraphService = entityGraphService;
    }

    public record HarvestResult(int added, int validated, int superseded) {
        public int total() { return added + validated + superseded; }
    }

    /** p16-03: 收割 prompt 中既有事实清单的硬上限——千条事实全量注入 ≈33k token 会爆窗 */
    static final int FACT_LIST_CAP = 80;

    public HarvestResult harvest(String projectId, Chapter chapter) {
        return harvest(projectId, chapter, null);
    }

    public HarvestResult harvest(String projectId, Chapter chapter,
                                 ChapterRevisionService.DerivedWriteBoundary boundary) {
        // 现有 ACTIVE 真典（供 LLM 去重 + Java 层比对）
        // p16-03: 按相关性收窄——正文 cast 相关（subject 出现在正文中）优先，其余按重要性/新鲜度补足，
        // 硬上限 FACT_LIST_CAP；未列入清单的事实仍参与 Java 层 merge 比对（findActiveBySubject 按需查）
        List<StoryFact> existing = canon.recall(projectId);
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
            var write = (java.util.function.Supplier<HarvestResult>) () ->
                    merge(resp.getContent(), projectId, chapter.getChapterIndex());
            return boundary == null ? write.get()
                    : boundary.apply(write, new HarvestResult(0, 0, 0));
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
                // p19-02: 别名先解析为规范名再比对/落库，防止「林公子/林凡」分裂成平行 ACTIVE 事实
                subject = canonicalSubject(projectId, subject);

                // p22-02: 冲突裁决 / HUMAN_PATCH 保护窗口 / 审计链全部在 StoryCanon 端口内
                StoryFact machine = new StoryFact(projectId, type, subject, assertion, chapterIndex);
                machine.setImportance(3);
                // p32-06（体验报告 #4/P1）: 机器收割的世界铁律一律待核——
                // LLM 可能把角色台词/推测分类为 WORLD_RULE，未确认前不得以铁律身份约束创作
                if (type == StoryFact.FactType.WORLD_RULE) {
                    machine.setEvidenceGrade(StoryFact.EvidenceGrade.UNVERIFIED);
                    machine.setImportance(2);
                }
                var result = canon.harvest(machine);
                switch (result.outcome()) {
                    case ADDED -> {
                        added++;
                        // p19-01: CHARACTER_STATE 新事实含死亡信号 → 角色置位
                        if (type == StoryFact.FactType.CHARACTER_STATE) {
                            detectDeath(projectId, subject, assertion, chapterIndex);
                        }
                        // p32-06: 待核铁律可观测——作者/诊断链路能看到"哪句话成了待核规则"
                        if (type == StoryFact.FactType.WORLD_RULE) {
                            com.writing.novel.telemetry.EventLog.emit("FACT_UNVERIFIED_RULE", java.util.Map.of(
                                    "projectId", projectId,
                                    "subject", subject,
                                    "chapterIndex", chapterIndex,
                                    "assertion", com.writing.novel.telemetry.EventLog.truncate(assertion, 150)));
                        }
                    }
                    case SKIPPED_UNVERIFIED ->
                            // p32-06: 待核断言不取代已确认铁律——机器断言丢弃，确认级事实优先
                            log.warn("真典收割跳过：待核断言不得取代已确认铁律(项目{}, 章{}): subject={}",
                                    projectId, chapterIndex, subject);
                    case VALIDATED -> validated++;
                    case SUPERSEDED -> superseded++;
                    case SKIPPED_PROTECTED ->
                        // 5 章保护窗口内的 HUMAN_PATCH 不被自动冲突取代——
                        // 机器断言丢弃（不落库），人工意图最高优先
                        log.warn("真典收割跳过受保护人工补丁(项目{}, 章{}): subject={}", projectId,
                                chapterIndex, subject);
                }
            }
        } catch (Exception ignored) { }
        return new HarvestResult(added, validated, superseded);
    }

    /**
     * p19-02: subject 规范化——经实体图的别名表把「林公子」解析为规范名「林凡」。
     * 未注入 EntityGraphService（null）或图服务异常时降级为原始 subject（保持旧行为），不阻塞收割。
     */
    private String canonicalSubject(String projectId, String subject) {
        if (entityGraphService == null) return subject;
        try {
            String resolved = entityGraphService.resolveCanonical(projectId, subject);
            return (resolved == null || resolved.isBlank()) ? subject : resolved;
        } catch (Exception e) {
            log.debug("subject 规范化失败(项目{}, subject={}): {}", projectId, subject, e.getMessage());
            return subject;
        }
    }
}
