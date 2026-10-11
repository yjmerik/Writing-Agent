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

    /** NCS-034: 创作约定消费（late-set，null=空契约处理） */
    private volatile com.writing.novel.service.AuthorIntentContextService authorIntentContextService;

    public void setAuthorIntentContextService(com.writing.novel.service.AuthorIntentContextService service) {
        this.authorIntentContextService = service;
    }


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
        return design(projectId, requirement, existing, "");
    }

    public Character design(String projectId, String requirement, List<Character> existing, String diversityGuide) {
        String existingDesc = existing.isEmpty() ? "（暂无已有人物）" :
                existing.stream().map(c -> c.getName() + "（" + c.getRole() + "）")
                        .reduce((a, b) -> a + "、" + b).orElse("");

        // NCS-152（O-2）：既有卡性格母题摘要 + 去重硬约束——抑制"8卡6母题"式系统性同质化。
        // 在 Agent 内统一构建，保证初始构思/重规划/定向修订全部 design 路径都带上。
        String motifGuide = buildMotifGuide(existing);

        String systemPrompt = promptService.render("character-design", Map.of(
                "styleGuide", "",
                "diversityGuide", diversityGuide != null ? diversityGuide : "",
                "motifGuide", motifGuide));
        // NCS-034: 人物设计请求携带精确契约版本及内容
        String intentSection = com.writing.novel.service.AuthorIntentConsumeSupport.sectionFor(
                authorIntentContextService, projectId, 2000, "CHARACTER_PLANNING");
        String fullSystemPrompt = intentSection.isBlank() ? systemPrompt
                : systemPrompt + "\n\n" + intentSection;

        String userMessage = "剧情需求：" + requirement
                + "\n已有人物：" + existingDesc
                + "\n请设计一个新人物，与已有人物不重复。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CHARACTER_DESIGN)
                .systemPrompt(fullSystemPrompt)
                .userMessage(userMessage)
                .temperature(0.85)
                .maxTokens(1500)
                .build();

        var resp = gateway.complete(req);
        Character c = parseCharacter(resp.getContent(), projectId);
        // p12-04: 解析失败得到"未命名"空卡时丢弃，不污染人物库
        if (c.getName() == null || c.getName().isBlank() || "未命名".equals(c.getName())) {
            return null;
        }
        knowledgeBase.saveCharacter(c);
        return c;
    }

    /** NCS-152: 既有卡母题摘要（性格/动机截断）+ 硬约束文案；无既有卡返回空（模板块跳过） */
    static String buildMotifGuide(List<Character> existing) {
        if (existing == null || existing.isEmpty()) return "";
        String summary = existing.stream()
                .map(c -> c.getName() + "（" + c.getRole() + "）："
                        + "性格=" + truncate(c.getPersonality(), 40)
                        + "；动机=" + truncate(c.getMotivation(), 40))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        return "既有人物性格母题摘要（去重依据）：\n" + summary
                + "\n去重硬约束：新卡的核心特质（性格母题、核心动机、说话风格）不得与上述 ≥2 张既有卡"
                + "的核心母题重叠；若某特质组合已被既有卡覆盖，必须给出差异化反转（立场/处境/表达方式相反），"
                + "不得再产出相似定位的重复人物。";
    }

    private static String truncate(String value, int limit) {
        if (value == null) return "待定";
        String v = value.strip();
        return v.length() <= limit ? v : v.substring(0, limit) + "…";
    }

    private Character parseCharacter(String text, String projectId) {
        String name = com.writing.novel.util.NameSanitizer.sanitize(extractField(text, "姓名", "未命名"));
        String role = extractField(text, "角色定位", "配角");
        Character c = new Character(projectId, name, role);
        c.setPersonality(extractFieldOrNull(text, "性格"));
        c.setAbility(extractFieldOrNull(text, "能力"));
        c.setMotivation(extractFieldOrNull(text, "动机"));
        c.setAppearance(extractFieldOrNull(text, "外貌"));
        // p9-3: 兼容新旧模板——优先取"与主角的关系"，回退到"人物关系"
        String relation = extractFieldOrNull(text, "与主角的关系");
        c.setRelationships(relation != null ? relation : extractFieldOrNull(text, "人物关系"));
        c.setGrowthArc(extractFieldOrNull(text, "成长弧"));
        c.setSpeakingStyle(extractFieldOrNull(text, "说话风格"));
        c.setKnowledgeDomains(extractFieldOrNull(text, "知识领域"));

        // p10-a1: 智谋建模字段
        String strategyStr = extractFieldOrNull(text, "智谋层级");
        if (strategyStr != null) {
            try {
                // 提取数字
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(strategyStr);
                if (m.find()) c.setStrategyLevel(Integer.parseInt(m.group(1)));
            } catch (Exception ignored) {}
        }
        c.setCountermeasures(extractFieldOrNull(text, "反制能力"));
        c.setIndependentWill(extractFieldOrNull(text, "独立判断原则"));
        c.setBlindSpot(extractFieldOrNull(text, "盲区"));
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
