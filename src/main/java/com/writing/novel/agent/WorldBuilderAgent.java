package com.writing.novel.agent;

import com.writing.novel.agent.util.ResponseParser;
import com.writing.novel.agent.util.ResponseParser.ParsedEntry;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Worldbuilding;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.KnowledgeBaseService;

import java.util.List;
import java.util.Map;

/**
 * 世界观构建 Agent。输入构思简介+类型标签 → 调 LLM → 解析为世界观条目 → 写入知识库。
 */
public class WorldBuilderAgent {

    /** NCS-034: 创作约定消费（late-set，null=空契约处理） */
    private volatile com.writing.novel.service.AuthorIntentContextService authorIntentContextService;

    public void setAuthorIntentContextService(com.writing.novel.service.AuthorIntentContextService service) {
        this.authorIntentContextService = service;
    }


    private final LlmGateway gateway;
    private final KnowledgeBaseService knowledgeBase;
    private final PromptTemplateService promptService;

    public WorldBuilderAgent(LlmGateway gateway, KnowledgeBaseService knowledgeBase,
                             PromptTemplateService promptService) {
        this.gateway = gateway;
        this.knowledgeBase = knowledgeBase;
        this.promptService = promptService;
    }

    public Worldbuilding build(String projectId, String concept, String tags) {
        return build(projectId, concept, tags, "");
    }

    public Worldbuilding build(String projectId, String concept, String tags, String diversityGuide) {
        Worldbuilding wb = propose(projectId, concept, tags, diversityGuide, "");

        // 普通构思保留占位兜底；显式审核修订调用 propose，由编排器拒绝空候选。
        if (wb.getEntries().isEmpty()) {
            wb.addEntry(new KnowledgeEntry(projectId, "CUSTOM",
                    "基础设定", concept + "（系统自动生成的占位设定，请重新运行构思以获得完整世界观）"));
        }
        for (KnowledgeEntry entry : wb.getEntries()) {
            knowledgeBase.saveEntry(entry);
        }
        return wb;
    }

    /**
     * 生成 detached 世界观候选；解析和重试期间不写知识库。
     * 显式审核修订会把用户意见附在请求中，并在候选校验通过后由编排器一次性替换。
     */
    public Worldbuilding propose(String projectId, String concept, String tags, String diversityGuide,
                                 String instructions) {
        // p34-01（UI 验收 A1）：tags 可空（新建表单允许留空）——Map.of 禁止 null 值，
        // 空白归一化为空串，模板中渲染为无标签而非 NPE
        String safeTags = tags == null ? "" : tags.trim();
        String safeConcept = concept == null ? "" : concept;
        String systemPrompt = promptService.render("worldbuilding", Map.of(
                "concept", safeConcept, "tags", safeTags,
                "styleGuide", "",
                "diversityGuide", diversityGuide != null ? diversityGuide : ""));
        // NCS-034: 世界规划请求携带精确契约版本及内容（空契约零注入）
        String intentSection = com.writing.novel.service.AuthorIntentConsumeSupport.sectionFor(
                authorIntentContextService, projectId, 2000, "WORLD_PLANNING");
        String fullSystemPrompt = intentSection.isBlank() ? systemPrompt
                : systemPrompt + "\n\n" + intentSection;

        String userMessage = "构思：" + concept + "\n类型标签：" + tags
                + "\n请生成 5-8 条世界观设定。"
                + (instructions == null || instructions.isBlank()
                ? "" : "\n【用户修改意见】" + instructions.trim());

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.WORLDBUILDING)
                .systemPrompt(fullSystemPrompt)
                .userMessage(userMessage)
                .temperature(0.9)
                .maxTokens(2000)
                .build();

        var resp = gateway.complete(req);

        Worldbuilding wb = parseWorldbuilding(resp.getContent(), projectId);

        // 输出校验：0 条目时重试 1 次
        if (wb.getEntries().isEmpty()) {
            resp = gateway.complete(req); // 重试
            wb = parseWorldbuilding(resp.getContent(), projectId);
        }

        return wb;
    }

    /** Commit an already validated setup candidate through the knowledge-memory consistency boundary. */
    public void replaceEntries(String projectId, List<KnowledgeEntry> entries) {
        knowledgeBase.replaceEntries(projectId, entries);
    }

    private Worldbuilding parseWorldbuilding(String content, String projectId) {
        Worldbuilding wb = new Worldbuilding(projectId);
        for (ParsedEntry pe : ResponseParser.parseEntries(content)) {
            KnowledgeEntry entry = new KnowledgeEntry(projectId,
                    normalizeType(pe.type()), pe.name(), pe.content());
            wb.addEntry(entry);
        }
        return wb;
    }

    private String normalizeType(String chineseType) {
        return switch (chineseType) {
            case "地理" -> "GEOGRAPHY";
            case "力量体系" -> "POWER_SYSTEM";
            case "势力" -> "FACTION";
            case "历史" -> "HISTORY";
            case "规则", "法则" -> "RULE";
            case "文化", "风俗", "文化风俗" -> "CULTURE";
            case "经济", "资源", "经济资源" -> "ECONOMY";
            case "种族" -> "RACE";
            default -> "CUSTOM";
        };
    }
}
