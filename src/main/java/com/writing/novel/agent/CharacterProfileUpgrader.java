package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
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
 * 角色档案升级 Agent——将重复出现的 Entity(CHARACTER) 升级为完整 Character 档案。
 *
 * 检测逻辑：出现跨度 >= 3 章的 Entity(type=CHARACTER)，
 * 且尚未在 Character 表中有档案的，采集其出场段落后调用 CharacterDesigner 生成完整档案。
 */
public class CharacterProfileUpgrader {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;

    public CharacterProfileUpgrader(LlmGateway gateway, NovelRepository repository,
                                     PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /**
     * 检测并升级重复出现的侧配角。
     * 每 5 章或卷末由 Orchestrator 调用。
     *
     * @return 升级的角色名列表
     */
    public List<String> upgrade(String projectId, int currentChapter) {
        List<String> upgraded = new ArrayList<>();

        // p7-4: 从 WritingPreferences 读取跨度阈值（可调）
        int spanThreshold = repository.findWritingPreferences(projectId)
                .map(p -> p.getCharacterUpgradeSpan())
                .orElse(2);

        // 1. 找出所有 CHARACTER 类型的 Entity，出现跨度 >= spanThreshold
        var entities = repository.findEntitiesByProject(projectId).stream()
                .filter(e -> e.getType() == EntityType.CHARACTER)
                .filter(e -> e.getLastSeenChapter() - e.getFirstSeenChapter() >= spanThreshold)
                .filter(e -> currentChapter - e.getLastSeenChapter() <= 5) // 近5章还活跃
                .toList();

        // 2. 排除已有 Character 档案的
        var existingChars = repository.findCharactersByProject(projectId);
        var existingNames = existingChars.stream()
                .map(Character::getName)
                .collect(Collectors.toSet());

        var candidates = entities.stream()
                .filter(e -> !existingNames.contains(e.getName()))
                .toList();

        if (candidates.isEmpty()) return upgraded;

        // 3. 每个候选：采集出场段落 → 生成 Character 档案
        for (Entity candidate : candidates) {
            String excerpts = collectExcerpts(projectId, candidate.getName(), currentChapter);
            if (excerpts.length() < 50) continue; // 出场太少，跳过

            // 调用 LLM 生成角色档案
            Character profile = generateProfile(projectId, candidate.getName(), excerpts);
            if (profile != null) {
                repository.saveCharacter(profile);
                upgraded.add(candidate.getName());
            }
        }

        return upgraded;
    }

    /** 从最近章节中采集角色出场段落 */
    private String collectExcerpts(String projectId, String name, int currentChapter) {
        var chapters = repository.findRecentChapters(projectId, 10);
        StringBuilder sb = new StringBuilder();
        for (var ch : chapters) {
            String content = ch.getContent();
            // 找包含角色名的段落（以名字为中心的 200 字窗口）
            int idx = content.indexOf(name);
            while (idx >= 0 && sb.length() < 2000) {
                int start = Math.max(0, idx - 100);
                int end = Math.min(content.length(), idx + name.length() + 100);
                sb.append(content, start, end).append("\n");
                idx = content.indexOf(name, idx + name.length());
            }
        }
        return sb.toString();
    }

    /** 调用 CharacterDesignerAgent 生成完整档案 */
    private Character generateProfile(String projectId, String name, String excerpts) {
        try {
            String systemPrompt = promptService.render("character-upgrade", Map.of(
                    "characterName", name,
                    "excerpts", excerpts.length() > 1500 ? excerpts.substring(0, 1500) : excerpts));

            String userMessage = "请根据以上章节正文，为角色「" + name + "」生成完整档案。";

            var req = LlmRequest.builder()
                    .taskType(LlmTaskType.GENERAL)
                    .systemPrompt(systemPrompt)
                    .userMessage(userMessage)
                    .temperature(0.7)
                    .maxTokens(3000)
                    .build();

            var resp = gateway.complete(req);
            return parseProfile(resp.getContent(), projectId, name);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 LLM 输出解析角色档案 */
    private Character parseProfile(String text, String projectId, String name) {
        String cleanName = com.writing.novel.util.NameSanitizer.sanitize(name);
        String role = extractField(text, "角色定位", "配角");
        Character c = new Character(projectId, cleanName, role);
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
        var matcher = java.util.regex.Pattern
                .compile("【" + field + "】\\s*([^\\n【]+)")
                .matcher(text);
        if (matcher.find()) {
            String v = matcher.group(1).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }
}
