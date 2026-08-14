package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连续性校验 Agent。检查新章节是否与已有设定矛盾。
 */
public class ContinuityCheckerAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private final RelevanceGateway relevanceGateway; // p11-q0-01: 检索收窄门面

    public ContinuityCheckerAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService) {
        this(gateway, repository, promptService, null);
    }

    /** p11-q0-01: 注入 RelevanceGateway，按相关性收窄查找（如主角定位） */
    public ContinuityCheckerAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService, RelevanceGateway relevanceGateway) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
        this.relevanceGateway = relevanceGateway;
    }

    public List<ContinuityIssue> check(String projectId, int chapterIndex,
                                        String chapterContent, String relevantSettings) {
        String systemPrompt = promptService.render("continuity-check", Map.of());

        StringBuilder msg = new StringBuilder("新章节内容：\n").append(chapterContent)
                .append("\n\n相关设定：\n").append(relevantSettings);

        // mc-p2-02: 历史卷摘要 + 叙事线索台账——长程连续性检查的依据（复现 M2）
        String history = buildHistorySection(projectId);
        if (history != null && !history.isBlank()) {
            msg.append("\n\n").append(history);
        }

        // ec-03: 时代一致性检测——历史题材下，年号/朝代矛盾判 HIGH
        String eraCheck = buildEraCheckSection(projectId);
        if (eraCheck != null && !eraCheck.isBlank()) {
            msg.append("\n\n").append(eraCheck);
        }

        // cn-03: 主角姓名漂移检测
        String nameCheck = buildNameDriftCheckSection(projectId);
        if (nameCheck != null && !nameCheck.isBlank()) {
            msg.append("\n\n").append(nameCheck);
        }

        // p11-q0-02: 故事真典（已确立事实）——长程一致性校验依据，替代级联压缩的摘要
        String canon = buildCanonSection(projectId);
        if (canon != null && !canon.isBlank()) {
            msg.append("\n\n").append(canon);
        }

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CONTINUITY_CHECK)
                .systemPrompt(systemPrompt)
                .userMessage(msg.toString())
                .temperature(0.2)
                .maxTokens(1000)
                .build();

        var resp = gateway.complete(req);
        List<ContinuityIssue> issues = parseIssues(resp.getContent(), projectId, chapterIndex);

        if (repository != null) {
            for (ContinuityIssue issue : issues) {
                repository.saveContinuityIssue(issue);
            }
        }
        return issues;
    }

    /**
     * mc-p2-02: 构建长程连续性检查依据——历史卷摘要（span>3 的卷级摘要，过滤 dirty）
     * + 叙事线索台账（未回收的线索）。检查新章节时与这些历史依据核对。
     */
    private String buildHistorySection(String projectId) {
        if (repository == null) return "";
        StringBuilder sb = new StringBuilder();

        // 历史卷摘要：卷级（span>3）且非 dirty；最多 5 卷，按章节倒序（最近卷优先）
        var volumeLevel = repository.findSummariesByProject(projectId).stream()
                .filter(s -> s.getToChapter() - s.getFromChapter() + 1 > 3 && !s.isDirty())
                .sorted((a, b) -> Integer.compare(b.getFromChapter(), a.getFromChapter()))
                .limit(5)
                .toList();
        if (!volumeLevel.isEmpty()) {
            sb.append("【历史卷摘要（前情回顾——新章节需与以下已确立情节保持一致）】\n");
            for (var s : volumeLevel) {
                String content = s.getContent();
                if (content == null) continue;
                if (content.length() > 200) content = content.substring(0, 200) + "...";
                sb.append("第").append(s.getFromChapter()).append("-").append(s.getToChapter())
                        .append("章：").append(content).append("\n");
            }
            sb.append("\n");
        }

        // 叙事线索台账：未回收线索
        var threads = repository.findAllNarrativeThreads(projectId).stream()
                .filter(t -> t.getStatus() != com.writing.novel.domain.ThreadStatus.RESOLVED)
                .toList();
        if (!threads.isEmpty()) {
            sb.append("【叙事线索台账（本章不得与以下未回收线索冲突，必要时推进或回收）】\n");
            for (var t : threads) {
                sb.append("■ ").append(t.getName())
                        .append("（第").append(t.getFirstSeenChapter()).append("章起，状态：")
                        .append(t.getStatus()).append("）\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * ec-03: 时代一致性检测段——注入时代锚点 + "年号/朝代矛盾判 HIGH" 指令。
     * 历史题材才注入；架空/修仙/现代无信号则返回空。
     */
    private String buildEraCheckSection(String projectId) {
        if (repository == null) return "";
        try {
            var entries = repository.findKnowledgeEntriesByProject(projectId);
            String concept = repository.findProject(projectId)
                    .map(p -> p.getConcept()).orElse("");
            String anchor = com.writing.novel.service.EraAnchorService.deriveEraAnchor(entries, concept);
            if (anchor == null || anchor.isBlank()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("【时代一致性专项检查】本作品时代设定：").append(anchor).append("。\n");
            sb.append("请专门检查本章正文是否出现与上述时代设定冲突的元素，"
                    + "包括但不限于：其他朝代的年号、钱币、典籍、官制机构、服饰、称谓、典章制度。\n");
            sb.append("发现任何时代矛盾，必须以 【世界观|高】 格式报告（高严重度），"
                    + "并在描述中写明冲突的具体名词。无矛盾则正常输出。\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * cn-03: 主角姓名漂移检测段——注入主角名 + "改名/别名判 HIGH" 指令。
     */
    private String buildNameDriftCheckSection(String projectId) {
        if (repository == null) return "";
        try {
            // p11-q0-01: 经 RelevanceGateway 收窄定位主角（gateway 主角保底），不再全量 findAll*
            var characters = relevanceGateway != null
                    ? relevanceGateway.relevantCharacters(projectId, null, 5)
                    : repository.findCharactersByProject(projectId);
            var mc = characters.stream()
                    .filter(c -> c.getRole() != null && c.getRole().contains("主角"))
                    .map(c -> c.getName()).findFirst().orElse(null);
            if (mc == null || mc.isBlank()) return "";
            return "【主角姓名一致性专项检查】主角名为「" + mc + "」。\n"
                    + "请检查本章正文是否用了其他名字/别号/别名指代主角，或与人物卡名字不一致。\n"
                    + "发现姓名漂移，必须以 【人物|高】 格式报告（高严重度），描述写明正文用了哪个错误名字。\n";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * p11-q0-02: 故事真典段——消费 ACTIVE 事实作为连续性校验依据。
     * 经 RelevanceGateway 取 importance 降序的相关事实（主角保底），作为长程一致性核对依据。
     */
    private String buildCanonSection(String projectId) {
        if (relevanceGateway == null) return "";
        List<StoryFact> facts = relevanceGateway.relevantFacts(projectId, null, 15);
        if (facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【故事真典（已确立的事实，新章节必须严格遵守）】\n");
        for (StoryFact f : facts) {
            sb.append("- ").append(f.getSubject()).append("（").append(f.getFactType())
                    .append("）：").append(f.getAssertion()).append("\n");
        }
        return sb.toString().trim();
    }

    private List<ContinuityIssue> parseIssues(String text, String projectId, int chapterIndex) {
        List<ContinuityIssue> issues = new ArrayList<>();
        if (text == null || text.contains("未发现")) return issues;

        Pattern p = Pattern.compile("【([^|]+)\\|([^】]+)】(.+)");
        Matcher m = p.matcher(text);
        while (m.find()) {
            ContinuityIssue.Type type = parseType(m.group(1).trim());
            ContinuityIssue.Severity severity = parseSeverity(m.group(2).trim());
            String desc = m.group(3).trim();
            issues.add(new ContinuityIssue(projectId, chapterIndex, type, severity, desc));
        }
        return issues;
    }

    private ContinuityIssue.Type parseType(String s) {
        return switch (s) {
            case "人物" -> ContinuityIssue.Type.CHARACTER;
            case "世界观" -> ContinuityIssue.Type.WORLDBUILDING;
            case "时间线" -> ContinuityIssue.Type.TIMELINE;
            case "伏笔" -> ContinuityIssue.Type.FORESHADOWING;
            default -> ContinuityIssue.Type.WORLDBUILDING;
        };
    }

    private ContinuityIssue.Severity parseSeverity(String s) {
        return switch (s) {
            case "高" -> ContinuityIssue.Severity.HIGH;
            case "中" -> ContinuityIssue.Severity.MEDIUM;
            default -> ContinuityIssue.Severity.LOW;
        };
    }
}
