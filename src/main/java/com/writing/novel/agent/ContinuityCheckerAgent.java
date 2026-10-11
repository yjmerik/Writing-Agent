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
    /** NCS-007: 检查结果信封持久化；null 时不落库（旧装配/测试） */
    private final com.writing.novel.persistence.sqlite.CheckResultRepository checkResultRepository;
    /** NCS-097: 注入清单仓库——封闭式核对段的数据源；null 时跳过该段（旧装配/测试） */
    private com.writing.novel.persistence.sqlite.InjectionManifestRepository manifestRepository;

    public ContinuityCheckerAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService) {
        this(gateway, repository, promptService, null);
    }

    /** p11-q0-01: 注入 RelevanceGateway，按相关性收窄查找（如主角定位） */
    public ContinuityCheckerAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService, RelevanceGateway relevanceGateway) {
        this(gateway, repository, promptService, relevanceGateway, null);
    }

    /** NCS-007: 带检查结果信封持久化的生产装配 */
    public ContinuityCheckerAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService, RelevanceGateway relevanceGateway,
                                  com.writing.novel.persistence.sqlite.CheckResultRepository checkResultRepository) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
        this.relevanceGateway = relevanceGateway;
        this.checkResultRepository = checkResultRepository;
    }

    /** NCS-097: 注入封闭式核对段数据源（生产装配用） */
    public void setManifestRepository(com.writing.novel.persistence.sqlite.InjectionManifestRepository repo) {
        this.manifestRepository = repo;
    }

    public List<ContinuityIssue> check(String projectId, int chapterIndex,
                                        String chapterContent, String relevantSettings) {
        return checkWithEnvelope(projectId, chapterIndex, chapterContent, relevantSettings).issues();
    }

    /** 返回本次调用的完整结果，避免空问题列表被误认为检查成功。 */
    public CheckResultEnvelope checkWithEnvelope(String projectId, int chapterIndex,
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

        // p11-q0-02 / NCS-097: 故事真典（已确立事实）——与写作侧同源口径（带章节号 + 本章 cast），
        // 唯一真典注入通道（settings 侧不再重复注入）；recency 随章节生效，与写时强调的近章事实同集
        String canon = buildCanonSection(projectId, chapterIndex);
        if (canon != null && !canon.isBlank()) {
            msg.append("\n\n").append(canon);
        }

        // NCS-097: 封闭式核对段——写作本章时实际注入的约束清单，逐项核对是否被遵守
        String injected = buildInjectedConstraintsSection(projectId, chapterIndex);
        if (injected != null && !injected.isBlank()) {
            msg.append("\n\n").append(injected);
        }

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CONTINUITY_CHECK)
                .systemPrompt(systemPrompt)
                .userMessage(msg.toString())
                .temperature(0.2)
                // NCS-121: 1000→2000——多问题章节输出被截断→INVALID→整章人工（实测高频介入源）
                .maxTokens(2000)
                .build();

        var resp = gateway.complete(req);
        // NCS-007: 结果信封——坏输出/空响应/截断落库为 INVALID/UNKNOWN，绝不伪装成"无问题"
        CheckResultEnvelope envelope = CheckResultEnvelope.of(resp.getContent());
        List<ContinuityIssue> issues = new ArrayList<>();
        if (envelope.status() == CheckResultEnvelope.Status.SUCCESS) {
            for (ContinuityIssue parsed : envelope.issues()) {
                issues.add(new ContinuityIssue(projectId, chapterIndex,
                        parsed.getType(), parsed.getSeverity(), parsed.getDescription()));
            }
        }
        if (checkResultRepository != null) {
            checkResultRepository.save(projectId, chapterIndex, "continuity",
                    envelope.status(), envelope.reason(), chapterContent);
        }

        if (repository != null) {
            for (ContinuityIssue issue : issues) {
                repository.saveContinuityIssue(issue);
            }
        }
        return new CheckResultEnvelope(envelope.status(), issues, envelope.reason());
    }

    /**
     * mc-p2-02: 构建长程连续性检查依据——历史卷摘要（span>3 的卷级摘要，过滤 dirty）
     * + 叙事线索台账（未回收的线索）。检查新章节时与这些历史依据核对。
     */
    /** p16-04: 线索台账注入上限——40 条线索全量注入随章节无界增长 */
    static final int THREAD_LEDGER_CAP = 15;

    String buildHistorySection(String projectId) {
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

        // 叙事线索台账：未回收线索（p16-04: 悬挂最久优先，cap 15）
        var threads = repository.findAllNarrativeThreads(projectId).stream()
                .filter(t -> t.getStatus() != com.writing.novel.domain.ThreadStatus.RESOLVED)
                .sorted(java.util.Comparator.comparingInt(
                        t -> ((com.writing.novel.domain.NarrativeThread) t).getFirstSeenChapter()))
                .limit(THREAD_LEDGER_CAP)
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
     * p11-q0-02 / NCS-097: 故事真典段——消费 ACTIVE 事实作为连续性校验依据。
     * 与写作侧 ContextAssembler 同一口径：带章节号的 relevantFacts（recency 生效）
     * + 本章大纲 cast 收窄（NCS-096 前为 chapterIndex=0 的退化排序，与写作口径不一致）。
     */
    /**
     * NCS-180: manifest 条目与真典现值比对。
     * @return null=未变化（现值不可得或一致）；否则 [标注, 现行文本]
     */
    private String[] currentCanonStateOf(String refId, String manifestLabel) {
        if (storyCanon == null || refId == null || refId.isBlank()) return null;
        try {
            var fact = storyCanon.findById(refId);
            if (fact.isEmpty()) return new String[]{"已废止", "该真典条目已废止"};
            var f = fact.get();
            // NCS-180: 沿 supersededBy 链取现行版本（HUMAN_PATCH/修正=旧目 SUPERSEDED→新目 ACTIVE）
            int guard = 0;
            while (f.getStatus() == com.writing.novel.domain.StoryFact.Status.SUPERSEDED
                    && f.getSupersededBy() != null && guard++ < 5) {
                f = storyCanon.findById(f.getSupersededBy()).orElse(f);
            }
            if (f.getStatus() == com.writing.novel.domain.StoryFact.Status.RETIRED) {
                return new String[]{"已废止", "该真典条目已废止"};
            }
            if (f.getStatus() == com.writing.novel.domain.StoryFact.Status.SUPERSEDED) return null;
            String current = f.getSubject() + "：" + f.getAssertion();
            if (manifestLabel.equals(f.getSubject() + "：" + firstCharsOf(f.getAssertion(), 40))) return null;
            return new String[]{"已修正", current};
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstCharsOf(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n);
    }

    private String buildCanonSection(String projectId, int chapterIndex) {
        if (relevanceGateway == null) return "";
        List<String> cast = com.writing.novel.orchestrator.CheckSettingsBuilder.castOf(repository, projectId, chapterIndex);
        List<StoryFact> facts = relevanceGateway.relevantFacts(projectId, cast, chapterIndex, 15);
        if (facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【故事真典（已确立的事实，新章节必须严格遵守）】\n");
        for (StoryFact f : facts) {
            sb.append("- ").append(f.getSubject()).append("（").append(f.getFactType())
                    .append("）：").append(f.getAssertion()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * NCS-097: 封闭式核对段——从注入清单（NCS-096）取写作时实际注入的真典事实/伏笔，
     * 要求逐项核对本章正文是否遵守。"作者被告知什么"是确定性的，比对开放世界检查可靠。
     */
    /** NCS-180: 真典现值比对（late-set；null=退化为旧行为——只呈现写作时快照） */
    private volatile com.writing.novel.spi.StoryCanon storyCanon;

    public void setStoryCanon(com.writing.novel.spi.StoryCanon storyCanon) {
        this.storyCanon = storyCanon;
    }

    private String buildInjectedConstraintsSection(String projectId, int chapterIndex) {
        if (manifestRepository == null) return "";
        try {
            var manifestOpt = manifestRepository.latest(projectId, chapterIndex);
            if (manifestOpt.isEmpty()) return "";
            var facts = manifestOpt.get().entriesOfKind(com.writing.novel.memory.InjectionManifest.Kind.FACT);
            var foreshadows = manifestOpt.get().entriesOfKind(com.writing.novel.memory.InjectionManifest.Kind.FORESHADOW);
            if (facts.isEmpty() && foreshadows.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("【本章写作时注入的约束清单（封闭式核对——逐项检查正文是否遵守，")
                    .append("违反任何一条必须以对应类型报告）】\n");
            boolean superseded = false;
            for (var e : facts) {
                // NCS-180: 与真典现值比对——典目修正/废止后旧约束标注，检查不得把
                // 「正文符合写作时旧约束」报为矛盾（门禁区分正文错与约束过期）
                String[] state = currentCanonStateOf(e.refId, e.label);
                if (state != null) {
                    superseded = true;
                    sb.append("- [真典·").append(state[0]).append("] ").append(state[1])
                            .append("（写作时旧约束：").append(e.label).append("）\n");
                } else {
                    sb.append("- [真典] ").append(e.label).append("\n");
                }
            }
            if (superseded) {
                sb.append("⚠ 以上标注【已修正/已废止】的条目为写作时旧约束——正文按旧约束写作并非错误，")
                  .append("不得据此报告矛盾；后续剧情以现行真典为准。\n");
            }
            for (var e : foreshadows) {
                sb.append("- [伏笔] ").append(e.label)
                        .append("（不得在未回收前自相矛盾或提前无据揭晓）\n");
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private ContinuityIssue.Type parseType(String s) {
        return switch (s) {
            case "人物" -> ContinuityIssue.Type.CHARACTER;
            case "世界观" -> ContinuityIssue.Type.WORLDBUILDING;
            case "时间线" -> ContinuityIssue.Type.TIMELINE;
            case "伏笔" -> ContinuityIssue.Type.FORESHADOWING;
            case "文风" -> ContinuityIssue.Type.STYLE;
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
