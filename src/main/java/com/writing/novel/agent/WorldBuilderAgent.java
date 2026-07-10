package com.writing.novel.agent;

import com.writing.novel.agent.util.ResponseParser;
import com.writing.novel.agent.util.ResponseParser.ParsedEntry;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Worldbuilding;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.memory.KnowledgeBaseService;

/**
 * 世界观构建 Agent。输入构思简介+类型标签 → 调 LLM → 解析为世界观条目 → 写入知识库。
 */
public class WorldBuilderAgent {

    private final LlmGateway gateway;
    private final KnowledgeBaseService knowledgeBase;

    public WorldBuilderAgent(LlmGateway gateway, KnowledgeBaseService knowledgeBase) {
        this.gateway = gateway;
        this.knowledgeBase = knowledgeBase;
    }

    public Worldbuilding build(String projectId, String concept, String tags) {
        String systemPrompt = """
                你是网络小说世界观设定专家。根据用户的构思，生成完整的世界观设定。
                必须覆盖：地理环境、力量体系（修炼/魔法等）、主要势力、历史背景、核心规则。
                严格按以下格式输出，每行一条：
                【类型】名称：内容描述
                类型包括：地理、力量体系、势力、历史、规则。
                """;

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
