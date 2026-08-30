package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.StoryCanon;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Map;

/**
 * 角色弧线更新 Agent——定期从近期章节重新评估角色的成长弧、关系、说话风格。
 *
 * 与 CharacterProfileUpgrader 的区别：
 * - Upgrader: Entity(无档案) → Character(首次建档)
 * - ArcUpdater: Character(已有档案) → Character(更新档案)
 *
 * 每 10 章由 Orchestrator 调用。
 */
public class ArcUpdater {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private final StoryCanon canon; // p11-q0-03/p22-02: 真典写入（可变状态权威源）

    public ArcUpdater(LlmGateway gateway, NovelRepository repository,
                      PromptTemplateService promptService) {
        this(gateway, repository, promptService, null);
    }

    /** p11-q0-03: 注入真典端口，成长弧演进同步写真典 CHARACTER_STATE（Character 卡兼容保留） */
    public ArcUpdater(LlmGateway gateway, NovelRepository repository,
                      PromptTemplateService promptService, StoryCanon canon) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
        this.canon = canon;
    }

    /**
     * 更新所有已建档角色的弧线。
     * @return 更新了的角色名列表
     */
    public List<String> updateArcs(String projectId, int currentChapter) {
        var chars = repository.findCharactersByProject(projectId);
        if (chars.isEmpty()) return List.of();

        // 只更新主角和最近10章出现的角色
        var candidates = chars.stream()
                .filter(c -> isMainCharacter(c) || appearedRecently(c, projectId, currentChapter))
                .toList();

        return candidates.stream()
                .map(c -> updateSingleArc(projectId, c, currentChapter))
                .filter(name -> name != null)
                .toList();
    }

    private boolean isMainCharacter(Character c) {
        String role = c.getRole();
        return role != null && (role.contains("主角") || role.contains("男主") || role.contains("女主"));
    }

    private boolean appearedRecently(Character c, String projectId, int currentChapter) {
        // 检查最近10章是否提到此角色
        var recentChapters = repository.findRecentChapters(projectId, 10);
        return recentChapters.stream()
                .anyMatch(ch -> ch.getContent() != null && ch.getContent().contains(c.getName()));
    }

    private String updateSingleArc(String projectId, Character c, int currentChapter) {
        try {
            String excerpts = collectRecentExcerpts(projectId, c.getName(), currentChapter);
            if (excerpts.length() < 100) return null; // 出场太少

            String systemPrompt = promptService.render("arc-update", Map.of(
                    "characterName", c.getName(),
                    "currentArc", c.getGrowthArc() != null ? c.getGrowthArc() : "（无）",
                    "currentRelationships", c.getRelationships() != null ? c.getRelationships() : "（无）",
                    "excerpts", excerpts.length() > 2000 ? excerpts.substring(0, 2000) : excerpts));

            var req = LlmRequest.builder()
                    .taskType(LlmTaskType.GENERAL)
                    .systemPrompt(systemPrompt)
                    .userMessage("请根据以上近期章节正文，更新角色「" + c.getName() + "」的成长弧和人物关系。")
                    .temperature(0.5)
                    .maxTokens(2000)
                    .build();

            var resp = gateway.complete(req);
            applyUpdates(c, resp.getContent());
            // p11-q0-03: 成长弧演进同步写真典 CHARACTER_STATE（真典为可变状态权威，消除 Character 卡漂移双轨）
            syncCanonState(projectId, c, currentChapter);
            repository.saveCharacter(c);
            return c.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private String collectRecentExcerpts(String projectId, String name, int currentChapter) {
        var chapters = repository.findRecentChapters(projectId, 10);
        StringBuilder sb = new StringBuilder();
        for (var ch : chapters) {
            String content = ch.getContent();
            if (content == null) continue;
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

    private void applyUpdates(Character c, String text) {
        String arc = extractField(text, "成长弧");
        if (arc != null) c.setGrowthArc(arc);
        String rel = extractField(text, "人物关系");
        if (rel != null) c.setRelationships(rel);
        String voice = extractField(text, "说话风格");
        if (voice != null) c.setSpeakingStyle(voice);
    }

    /**
     * p11-q0-03: 成长弧演进同步写真典 CHARACTER_STATE。
     * 角色成长弧是"可变状态"，应由真典承载（与 FactHarvester 每章收割互补）。
     * Character 卡的 growthArc 仍保留（兼容），但真典为权威源——ContextAssembler bibleSection 读真典。
     */
    private void syncCanonState(String projectId, Character c, int currentChapter) {
        if (canon == null || c.getGrowthArc() == null) return;
        String assertion = "成长弧：" + c.getGrowthArc();
        var existing = canon.recallBySubject(projectId, c.getName()).stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.CHARACTER_STATE
                        && f.getAssertion() != null && f.getAssertion().startsWith("成长弧："))
                .toList();
        if (existing.isEmpty()) {
            canon.saveIfAbsent(new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                    c.getName(), assertion, currentChapter));
        } else {
            StoryFact old = existing.get(0);
            if (old.getAssertion().equals(assertion)) {
                canon.validate(old, currentChapter); // 无变化则刷新确认章
            } else {
                // 成长弧演进 → 合法 supercede（审计链由端口保证）
                canon.supersedeFact(old, new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                        c.getName(), assertion, currentChapter));
            }
        }
    }

    private String extractField(String text, String field) {
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
