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

import java.util.Map;

/**
 * 世界观构建 Agent。输入构思简介+类型标签 → 调 LLM → 解析为世界观条目 → 写入知识库。
 */
public class WorldBuilderAgent {

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
        String systemPrompt = promptService.render("worldbuilding", Map.of(
                "concept", concept, "tags", tags, "styleGuide", ""));

        String userMessage = "构思：" + concept + "\n类型标签：" + tags
                + "\n请生成 5-8 条世界观设定。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.WORLDBUILDING)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.9)
                .maxTokens(2000)
                .build();

        var resp = gateway.complete(req);

        Worldbuilding wb = new Worldbuilding(projectId);
        for (ParsedEntry pe : ResponseParser.parseEntries(resp.getContent())) {
            KnowledgeEntry entry = new KnowledgeEntry(projectId,
                    normalizeType(pe.type()), pe.name(), pe.content());
            wb.addEntry(entry);
            knowledgeBase.saveEntry(entry);
        }
        return wb;
    }

    private String normalizeType(String chineseType) {
        return switch (chineseType) {
            case "地理" -> "GEOGRAPHY";
            case "力量体系" -> "POWER_SYSTEM";
            case "势力" -> "FACTION";
            case "历史" -> "HISTORY";
            case "规则" -> "RULE";
            default -> "CUSTOM";
        };
    }
}
