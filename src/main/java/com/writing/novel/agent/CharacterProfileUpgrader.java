package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CharacterProfileUpgrader.class);

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private final RelevanceGateway relevanceGateway; // p11-q0-01: 检索收窄门面
    private final StoryFactRepository factRepo;      // p11-q0-03: 真典写入（可变状态权威源）

    public CharacterProfileUpgrader(LlmGateway gateway, NovelRepository repository,
                                     PromptTemplateService promptService) {
        this(gateway, repository, promptService, null, null);
    }

    /** p11-q0-01: 注入 RelevanceGateway，collectExcerpts 按最近窗口收窄 */
    public CharacterProfileUpgrader(LlmGateway gateway, NovelRepository repository,
                                     PromptTemplateService promptService, RelevanceGateway relevanceGateway) {
        this(gateway, repository, promptService, relevanceGateway, null);
    }

    /** p11-q0-03: 注入真典仓库，建档时播种初始 CHARACTER_STATE（真典为可变状态权威） */
    public CharacterProfileUpgrader(LlmGateway gateway, NovelRepository repository,
                                     PromptTemplateService promptService, RelevanceGateway relevanceGateway,
                                     StoryFactRepository factRepo) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
        this.relevanceGateway = relevanceGateway;
        this.factRepo = factRepo;
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
        // p11-H2: 默认从 2 降低到 1（出现 2 章即可升级，加快角色建档速度）
        int spanThreshold = Math.max(1, repository.findWritingPreferences(projectId)
                .map(p -> p.getCharacterUpgradeSpan())
                .orElse(1));

        // 1. 找出所有 CHARACTER 类型的 Entity
        // p11-fix: 放宽条件——只要有出场记录（span>=0）就考虑升级，不再要求多章跨度
        // spanThreshold 仅作为优先级排序参考（多章出场的优先升级）
        var entities = repository.findEntitiesByProject(projectId).stream()
                .filter(e -> e.getType() == EntityType.CHARACTER)
                .filter(e -> e.getLastSeenChapter() - e.getFirstSeenChapter() >= 0)
                .filter(e -> currentChapter - e.getLastSeenChapter() <= 8) // p11-H2: 5→8（更宽松）
                .sorted((a, b) -> Integer.compare(
                        b.getLastSeenChapter() - b.getFirstSeenChapter(),
                        a.getLastSeenChapter() - a.getFirstSeenChapter())) // 跨度大的优先
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

        // p11-q0-02: 读取真典 CHARACTER_STATE（角色当前状态），建档时遵循真相避免与已确立事实矛盾
        var canonStates = relevanceGateway != null
                ? relevanceGateway.relevantFacts(projectId, null, 20).stream()
                        .filter(f -> f.getFactType() == StoryFact.FactType.CHARACTER_STATE).toList()
                : java.util.Collections.<StoryFact>emptyList();

        // p11-fix: debug 日志
        log.info("角色升级 candidates: {}", candidates.stream()
                .map(e -> e.getName() + "(ch" + e.getFirstSeenChapter() + "-" + e.getLastSeenChapter() + ")").toList());

        // 3. 每个候选：采集出场段落 → 生成 Character 档案
        for (Entity candidate : candidates) {
            String excerpts = collectExcerpts(projectId, candidate.getName(), currentChapter);
            // p11-q0-02: 附加该候选的真典已确立状态（避免新档案与真相矛盾）
            String canonFor = canonStates.stream()
                    .filter(f -> f.getSubject() != null && f.getSubject().contains(candidate.getName()))
                    .map(f -> "【真典已确立】" + f.getAssertion())
                    .collect(Collectors.joining("\n"));
            if (!canonFor.isBlank()) excerpts = excerpts + "\n" + canonFor;
            log.info("角色升级 {} excerpts: {} 字", candidate.getName(), excerpts.length());
            if (excerpts.length() < 30) continue; // p11-H2: 50→30（降低门槛）

            // 调用 LLM 生成角色档案
            Character profile = generateProfile(projectId, candidate.getName(), excerpts);
            if (profile != null) {
                repository.saveCharacter(profile);
                // p11-q0-03: 建档即播种初始 CHARACTER_STATE 到真典（可变状态由真典承载，避免后续 Character 卡漂移）
                seedCanonState(projectId, profile, currentChapter);
                upgraded.add(candidate.getName());
            }
        }

        return upgraded;
    }

    /**
     * p11-q0-03: 建档即把角色的初始可变状态（能力/动机）播种为真典 CHARACTER_STATE。
     * 真典成为可变状态权威源（与 ArcUpdater 成长弧同步、FactHarvester 每章收割互补），
     * Character 卡的 ability/motivation 作为初始档案兼容保留。
     */
    private void seedCanonState(String projectId, Character profile, int currentChapter) {
        if (factRepo == null) return;
        boolean exists = factRepo.findActiveBySubject(projectId, profile.getName()).stream()
                .anyMatch(f -> f.getFactType() == StoryFact.FactType.CHARACTER_STATE);
        if (exists) return; // 已有真典状态则不覆盖（避免重建档覆盖演进）
        StringBuilder assertion = new StringBuilder();
        if (profile.getAbility() != null && !profile.getAbility().isBlank()) {
            assertion.append("初始能力：").append(profile.getAbility());
        }
        if (profile.getMotivation() != null && !profile.getMotivation().isBlank()) {
            if (assertion.length() > 0) assertion.append("；");
            assertion.append("初始动机：").append(profile.getMotivation());
        }
        if (assertion.length() == 0) return;
        factRepo.save(new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                profile.getName(), assertion.toString(), currentChapter));
    }

    /** 从角色出场章节中采集出场段落 */
    private String collectExcerpts(String projectId, String name, int currentChapter) {
        // p11-q0-01: 经 recent 窗口收窄（候选已过滤 lastSeenChapter<=currentChapter-8，最近窗口足够），
        // 避免 findChaptersByProject 全量 O(N) 扫描（百万字下性能与 token 双赢）
        int window = Math.min(50, Math.max(currentChapter, 1));
        var chapters = repository.findRecentChapters(projectId, window);
        StringBuilder sb = new StringBuilder();
        for (var ch : chapters) {
            String content = ch.getContent();
            if (content == null || !content.contains(name)) continue;
            // 找包含角色名的段落（以名字为中心的 200 字窗口）
            int idx = content.indexOf(name);
            while (idx >= 0 && sb.length() < 2000) {
                int start = Math.max(0, idx - 100);
                int end = Math.min(content.length(), idx + name.length() + 100);
                sb.append(content, start, end).append("\n");
                idx = content.indexOf(name, idx + name.length());
            }
            if (sb.length() >= 2000) break;
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

        // p11-H2: 补全智谋建模字段（与 CharacterDesignerAgent 保持一致）
        String strategyStr = extractFieldOrNull(text, "智谋层级");
        if (strategyStr != null) {
            try {
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
