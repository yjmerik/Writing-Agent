package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.KnowledgeBaseService;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 人物设计 Agent。根据剧情需求 + 世界观生成人物卡。
 */
public class CharacterDesignerAgent {

    private final LlmGateway gateway;
    private final KnowledgeBaseService knowledgeBase;
    private final PromptTemplateService promptService;

    public CharacterDesignerAgent(LlmGateway gateway, KnowledgeBaseService knowledgeBase,
                                  PromptTemplateService promptService) {
        this.gateway = gateway;
        this.knowledgeBase = knowledgeBase;
        this.promptService = promptService;
    }

    public Character design(String projectId, String requirement, List<Character> existing) {
        String existingDesc = existing.isEmpty() ? "（暂无已有人物）" :
                existing.stream().map(c -> c.getName() + "（" + c.getRole() + "）")
                        .reduce((a, b) -> a + "、" + b).orElse("");

        String systemPrompt = promptService.render("character-design", Map.of("styleGuide", ""));

        String userMessage = "剧情需求：" + requirement
                + "\n已有人物：" + existingDesc
                + "\n请设计一个新人物，与已有人物不重复。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CHARACTER_DESIGN)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.85)
                .maxTokens(1500)
                .build();

        var resp = gateway.complete(req);
        Character c = parseCharacter(resp.getContent(), projectId);
        knowledgeBase.saveCharacter(c);
        return c;
    }

    private Character parseCharacter(String text, String projectId) {
        String name = extractField(text, "姓名", "未命名");
        String role = extractField(text, "角色定位", "配角");
        Character c = new Character(projectId, name, role);
        c.setPersonality(extractFieldOrNull(text, "性格"));
        c.setAbility(extractFieldOrNull(text, "能力"));
        c.setMotivation(extractFieldOrNull(text, "动机"));
        c.setAppearance(extractFieldOrNull(text, "外貌"));
        c.setRelationships(extractFieldOrNull(text, "人物关系"));
        c.setGrowthArc(extractFieldOrNull(text, "成长弧"));
        c.setSpeakingStyle(extractFieldOrNull(text, "说话风格"));
        c.setKnowledgeDomains(extractFieldOrNull(text, "知识领域"));
        return c;
    }

    private String extractField(String text, String field, String defaultValue) {
        String v = extractFieldOrNull(text, field);
        return v != null ? v : defaultValue;
    }

    private String extractFieldOrNull(String text, String field) {
        Pattern p = Pattern.compile("【" + Pattern.quote(field) + "】(.+?)(?=【|$)", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
