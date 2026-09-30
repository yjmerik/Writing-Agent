package com.writing.novel.agent;

import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大纲规划 Agent。生成全书总纲 + 分卷 + 章节梗概。
 */
public class PlotterAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    // p17-03: 题材规则提供者（可选注入，卷大纲题材约束）
    private com.writing.novel.service.GenreRuleProvider genreRuleProvider;

    /** p17-03: 注入题材规则提供者（@Bean 装配用） */
    public void setGenreRuleProvider(com.writing.novel.service.GenreRuleProvider provider) { this.genreRuleProvider = provider; }

    public PlotterAgent(LlmGateway gateway, NovelRepository repository,
                        PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    public Outline plan(String projectId, String concept, String theme, int totalChapters) {
        return plan(projectId, concept, theme, totalChapters, "", "");
    }

    public Outline plan(String projectId, String concept, String theme, int totalChapters,
                        String styleGuide, String genreRules) {
        return plan(projectId, concept, theme, totalChapters, styleGuide, genreRules, "");
    }

    public Outline plan(String projectId, String concept, String theme, int totalChapters,
                        String styleGuide, String genreRules, String diversityGuide) {
        Outline outline = proposeOutline(projectId, concept, theme, totalChapters,
                styleGuide, genreRules, diversityGuide);
        repository.saveOutline(outline);
        return outline;
    }

    /** Generate and validate a detached whole-book outline before the single persistence step. */
    private Outline proposeOutline(String projectId, String concept, String theme, int totalChapters,
                                   String styleGuide, String genreRules, String diversityGuide) {
        String systemPrompt = promptService.render("plotting", Map.of(
                "concept", concept, "theme", theme,
                "totalChapters", totalChapters,
                "styleGuide", styleGuide != null ? styleGuide : "",
                "genreRules", genreRules != null ? genreRules : "",
                "diversityGuide", diversityGuide != null ? diversityGuide : ""));

        // NCS-150（旅程三 O-1）：预计总章数为软参考——按故事需要自由规划，不强制凑满
        String userMessage = "构思：" + concept + "\n主题：" + theme
                + "\n预计总章数（软参考，可按故事需要增减）：" + totalChapters
                + "\n请按叙事节奏自由规划卷数与每卷章数（每卷 4-8 章），故事自然收尾即可。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.PLOTTING)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.8)
                .maxTokens(12000)
                .build();

        var resp = gateway.complete(req);
        String rawContent = resp.getContent();
        Outline outline = parseOutline(rawContent, projectId);

        // 输出校验：0 卷或 0 章时重试 1 次
        int parsedChapters = outline.getVolumes().stream().mapToInt(v -> v.getSynopses().size()).sum();
        if (outline.getVolumes().isEmpty() || parsedChapters == 0) {
            resp = gateway.complete(req);
            rawContent = resp.getContent();
            outline = parseOutline(rawContent, projectId);
            parsedChapters = outline.getVolumes().stream().mapToInt(v -> v.getSynopses().size()).sum();
        }

        // p5-d4: 章节计数验证——如果解析出的章节数远少于请求（<50%），用更明确的提示重试
        if (parsedChapters > 0 && parsedChapters < totalChapters / 2) {
            var retryReq = LlmRequest.builder()
                    .taskType(LlmTaskType.PLOTTING)
                    .systemPrompt(systemPrompt)
                    .userMessage("注意：上次只生成了 " + parsedChapters + " 章，但需要 " + totalChapters
                            + " 章。请务必生成至少 " + totalChapters + " 个章节（第1章到第"
                            + totalChapters + "章），每卷5-8章，不要遗漏。")
                    .temperature(0.8)
                    .maxTokens(12000)
                    .build();
            var retryResp = gateway.complete(retryReq);
            var retryOutline = parseOutline(retryResp.getContent(), projectId);
            int retryChapters = retryOutline.getVolumes().stream().mapToInt(v -> v.getSynopses().size()).sum();
            if (retryChapters > parsedChapters) {
                outline = retryOutline;
                parsedChapters = retryChapters;
            }
        }

        // 仍为空（0 卷或 0 章）的 fallback：创建 1 卷 3 章的最小大纲
        if (outline.getVolumes().isEmpty() || parsedChapters == 0) {
            outline = new Outline(projectId, concept);
            Volume v = new Volume(projectId, 1, "第一卷", theme);
            v.addSynopsis(new ChapterSynopsis(projectId, 1, "第一章 开端", concept + " 故事开始"));
            v.addSynopsis(new ChapterSynopsis(projectId, 2, "第二章 发展", "情节推进"));
            v.addSynopsis(new ChapterSynopsis(projectId, 3, "第三章 高潮", "第一次冲突"));
            outline.addVolume(v);
        }

        return outline;
    }

    // ==================== p12-K1: 分层大纲 ====================

    /**
     * p12-K1: 生成全书总纲（卷级规划，不含章节细节）。
     * 用于百万字项目——先生成卷结构，后续按需生成分卷大纲。
     * cn-01: 接收主角名 + 人物摘要，避免大纲自创名字与人物卡漂移。
     * @return 总纲文本（包含 N 卷的标题/梗概/章节范围）
     */
    public String planMasterOutline(String projectId, String concept, int totalChapters,
                                     String worldSummary, String genreRules) {
        return planMasterOutline(projectId, concept, totalChapters, worldSummary, genreRules, "", "");
    }

    public String planMasterOutline(String projectId, String concept, int totalChapters,
                                     String worldSummary, String genreRules,
                                     String protagonistName, String characterSummary) {
        int volumeCount = Math.max(2, totalChapters / 8); // C1: 每 8 章 1 卷（与 tryExtendLayeredOutline 对齐）
        String systemPrompt = promptService.render("master-outline", Map.of(
                "concept", concept,
                "worldSummary", worldSummary != null ? worldSummary : "（暂无世界观摘要）",
                "genreRules", genreRules != null ? genreRules : "",
                "totalChapters", String.valueOf(totalChapters),
                "volumeCount", String.valueOf(volumeCount),
                "protagonistName", protagonistName != null ? protagonistName : "",
                "characterSummary", characterSummary != null ? characterSummary : ""));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.PLOTTING)
                .systemPrompt(systemPrompt)
                .userMessage("请生成全书总纲。")
                .temperature(0.7)
                // op-fix: 推理模型 reasoning_content 耗 token，8192 致总纲正文为空
                .maxTokens(16000)
                .build();
        var resp = gateway.complete(req);
        return stripPreamble(resp.getContent()); // p12-02: 剥离 LLM 客套前言
    }

    /**
     * p12-K1: 为指定卷生成详细章节大纲（延迟生成）。
     * 写到该卷第一章前才调用——避免一次性生成 250 章。
     * @param masterOutline 全书总纲文本
     * @param previousSummary 前卷摘要（用于衔接）
     * @param volumeTitle 本卷标题
     * @param volumeSummary 本卷梗概
     * @param startChapter 起始章号
     * @param endChapter 结束章号
     * @param existingCharacters 已有角色列表
     * @return 该卷的章节大纲文本（LLM 输出）
     */
    public String planVolume(String masterOutline, String previousSummary,
                              String volumeTitle, String volumeSummary,
                              int startChapter, int endChapter,
                              String existingCharacters) {
        return planVolume(masterOutline, previousSummary, volumeTitle, volumeSummary,
                startChapter, endChapter, existingCharacters, "");
    }

    /**
     * cn-02: 带主角名的卷大纲生成，避免卷大纲改名漂移。
     */
    public String planVolume(String masterOutline, String previousSummary,
                              String volumeTitle, String volumeSummary,
                              int startChapter, int endChapter,
                              String existingCharacters, String protagonistName) {
        return planVolume(masterOutline, previousSummary, volumeTitle, volumeSummary,
                startChapter, endChapter, existingCharacters, protagonistName, null);
    }

    /**
     * p17-03: 带题材的卷大纲生成——注入 genreRules + 题材化高潮原则，修复长篇题材约束断链。
     */
    public String planVolume(String masterOutline, String previousSummary,
                              String volumeTitle, String volumeSummary,
                              int startChapter, int endChapter,
                              String existingCharacters, String protagonistName,
                              com.writing.novel.domain.GenreProfile genre) {
        return requestVolumeOutline(masterOutline, previousSummary, volumeTitle, volumeSummary,
                startChapter, endChapter, existingCharacters, protagonistName, genre, "");
    }

    /**
     * Generate a detached replacement for the unwritten portion of one volume.
     * The candidate is never saved here; the orchestrator validates its exact chapter set first.
     */
    public Volume proposeVolumeRevision(String projectId, String masterOutline, Volume sourceVolume,
                                        int writtenBoundary, String instructions) {
        var unwritten = sourceVolume.getSynopses().stream()
                .filter(s -> s.getChapterIndex() > writtenBoundary)
                .sorted(java.util.Comparator.comparingInt(ChapterSynopsis::getChapterIndex))
                .toList();
        Volume candidate = new Volume(projectId, sourceVolume.getVolumeIndex(),
                sourceVolume.getTitle(), sourceVolume.getSummary());
        if (unwritten.isEmpty()) return candidate;

        int startChapter = unwritten.get(0).getChapterIndex();
        int endChapter = unwritten.get(unwritten.size() - 1).getChapterIndex();
        var characters = repository.findCharactersByProject(projectId);
        String existingCharacters = characters.stream()
                .map(c -> c.getName() + "(" + c.getRole() + ")")
                .reduce((a, b) -> a + "、" + b).orElse("（暂无）");
        String protagonistName = characters.stream()
                .filter(c -> c.getRole() != null && c.getRole().contains("主角"))
                .map(com.writing.novel.domain.Character::getName)
                .findFirst().orElse("");
        GenreProfile genre = repository.findProject(projectId).map(Project::getGenre).orElse(null);
        String raw = requestVolumeOutline(masterOutline, "已写至第" + writtenBoundary + "章",
                sourceVolume.getTitle(), sourceVolume.getSummary(), startChapter, endChapter,
                existingCharacters, protagonistName, genre, instructions);
        parseChaptersInVolume(candidate, raw, projectId);
        return candidate;
    }

    private String requestVolumeOutline(String masterOutline, String previousSummary,
                                        String volumeTitle, String volumeSummary,
                                        int startChapter, int endChapter,
                                        String existingCharacters, String protagonistName,
                                        com.writing.novel.domain.GenreProfile genre,
                                        String instructions) {
        int chapterCount = endChapter - startChapter + 1;
        boolean fastPaced = genre == null || genre.isFastPaced();
        String climaxPrinciple = fastPaced
                ? "2. 每章至少一个爽点，爽点类型要多样化"
                : "2. 每章至少一个核心张力点（冲突升级/真相揭示/关键抉择），形式要多样化";
        String genreRules = genreRuleProvider != null && genre != null
                ? genreRuleProvider.getPlottingRules(genre) : "";
        String systemPrompt = promptService.render("volume-outline", java.util.Map.ofEntries(
                java.util.Map.entry("masterOutline", masterOutline != null ? masterOutline : "（暂无总纲）"),
                java.util.Map.entry("previousSummary", previousSummary != null ? previousSummary : "（第一卷，无前文）"),
                java.util.Map.entry("volumeTitle", volumeTitle),
                java.util.Map.entry("volumeSummary", volumeSummary != null ? volumeSummary : ""),
                java.util.Map.entry("startChapter", String.valueOf(startChapter)),
                java.util.Map.entry("endChapter", String.valueOf(endChapter)),
                java.util.Map.entry("startChapterPlus1", String.valueOf(startChapter + 1)),
                java.util.Map.entry("chapterCount", String.valueOf(chapterCount)),
                java.util.Map.entry("existingCharacters", existingCharacters != null ? existingCharacters : "（暂无）"),
                java.util.Map.entry("protagonistName", protagonistName != null ? protagonistName : ""),
                java.util.Map.entry("climaxPrinciple", climaxPrinciple),
                java.util.Map.entry("genreRules", genreRules != null ? genreRules : "")));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.PLOTTING)
                .systemPrompt(systemPrompt)
                .userMessage("请生成本卷的详细章节大纲。"
                        + (instructions == null || instructions.isBlank()
                        ? "" : "\n【用户修改意见】" + instructions.trim()))
                .temperature(0.7)
                // mc-p3-01: 12000 输出余量——8 章 × 详细格式 + 总纲注入，避免截断
                .maxTokens(12000)
                .build();
        var resp = gateway.complete(req);
        return stripPreamble(resp.getContent()); // p12-02: 剥离 LLM 客套前言
    }

    private Outline parseOutline(String text, String projectId) {
        // 提取总纲
        String premise = "";
        Matcher premM = Pattern.compile("【总纲】(.+?)(?=【卷|$)", Pattern.DOTALL).matcher(text);
        if (premM.find()) premise = premM.group(1).trim();

        Outline outline = new Outline(projectId, premise);

        // 提取卷：兼容多种格式
        // 格式1: 【卷N】卷名\n卷梗概\n第X章...
        // 格式2: # 第N章 卷名\n... (markdown H1)
        // 格式3: 卷N 卷名（第X-Y章）\n...
        // 先尝试标准【卷N】格式
        Matcher volM = Pattern.compile("#?\\s*【第?卷([0-9一二三四五六七八九十百]+)[】第]*(.+?)\\r?\\n(.+?)(?=#?\\s*【第?卷|#?\\s*【伏笔|$)", Pattern.DOTALL).matcher(text);
        boolean hasVolumes = false;
        while (volM.find()) {
            hasVolumes = true;
            int volIndex = parseChineseOrArabic(volM.group(1));
            String volTitle = volM.group(2).trim();
            String volBody = volM.group(3).trim();
            String volSummary = extractVolumeSummary(volBody);
            Volume volume = new Volume(projectId, volIndex, volTitle, volSummary);
            parseChaptersInVolume(volume, volBody, projectId);
            outline.addVolume(volume);
        }

        // 如果没有匹配到【卷N】格式，尝试 markdown 格式：# 卷名 或 ## 卷名
        if (!hasVolumes) {
            // 格式: # 卷名\n\n内容...（用 --- 分隔卷）
            Matcher mdVolM = Pattern.compile("#+\\s*([^\\n]+?)\\r?\\n(.+?)(?=#+\\s+[^\\n]|---|$)", Pattern.DOTALL).matcher(text);
            int volIdx = 0;
            while (mdVolM.find()) {
                volIdx++;
                String volTitle = sanitizeTitle(mdVolM.group(1));
                String volBody = mdVolM.group(2).trim();
                String volSummary = extractVolumeSummary(volBody);
                Volume volume = new Volume(projectId, volIdx, volTitle, volSummary);
                parseChaptersInVolume(volume, volBody, projectId);
                outline.addVolume(volume);
            }
        }

        // 如果还是没有卷，尝试直接提取所有章节（无卷结构）
        if (outline.getVolumes().isEmpty()) {
            Volume volume = new Volume(projectId, 1, "第一卷", "");
            parseChaptersInVolume(volume, text, projectId);
            outline.addVolume(volume);
        }

        return outline;
    }

    private String extractVolumeSummary(String volBody) {
        String[] lines = volBody.split("\\n");
        for (String line : lines) {
            String lt = line.trim();
            if (!lt.isEmpty() && !lt.startsWith("第") && !lt.startsWith("#") && !lt.startsWith("*")) {
                return lt;
            }
        }
        return "";
    }

    private void parseChaptersInVolume(Volume volume, String volBody, String projectId) {
        // p12-fix: 收集已存在的章号，防止重复
        java.util.Set<Integer> existingIndices = new java.util.HashSet<>();
        for (ChapterSynopsis s : volume.getSynopses()) existingIndices.add(s.getChapterIndex());

        // 多行格式：## 第N章 标题\n\n**场景节拍**：...
        boolean hasChapters = false;
        Matcher chM = Pattern.compile("(?:#+\\s*|\\*{2})?第([0-9一二三四五六七八九十百]+)章\\s*(.+?)\\r?\\n\\r?\\n(\\*{0,2}场景节拍\\*{0,2}[：:]\\s*.+?)(?=\\r?\\n\\r?\\n(?:#+\\s*|\\*{2})?第[0-9一二三四五六七八九十百]+章|$)", Pattern.DOTALL).matcher(volBody);
        while (chM.find()) {
            hasChapters = true;
            int idx = parseChineseOrArabic(chM.group(1));
            if (existingIndices.contains(idx)) continue; // p12-fix: 跳过重复章号
            existingIndices.add(idx);
            String title = sanitizeTitle(chM.group(2));
            String rawContent = chM.group(3).trim();
            ChapterSynopsis cs = new ChapterSynopsis(projectId, idx, title, rawContent);
            parseChapterDetails(cs, rawContent);
            volume.addSynopsis(cs);
        }

        // 单换行格式
        if (!hasChapters) {
            Matcher chM1 = Pattern.compile("(?:#+\\s*|\\*{2})?第([0-9一二三四五六七八九十百]+)章\\s*(.+?)\\r?\\n(\\*{0,2}场景节拍\\*{0,2}[：:]\\s*.+?)(?=\\r?\\n(?:#+\\s*|\\*{2})?第[0-9一二三四五六七八九十百]+章|$)", Pattern.DOTALL).matcher(volBody);
            while (chM1.find()) {
                hasChapters = true;
                int idx = parseChineseOrArabic(chM1.group(1));
                if (existingIndices.contains(idx)) continue; // p12-fix: 去重
                existingIndices.add(idx);
                String title = sanitizeTitle(chM1.group(2));
                String rawContent = chM1.group(3).trim();
                ChapterSynopsis cs = new ChapterSynopsis(projectId, idx, title, rawContent);
                parseChapterDetails(cs, rawContent);
                volume.addSynopsis(cs);
            }
        }

        // 单行格式（标题:内容）
        if (!hasChapters) {
            // p16-fix: 前瞻必须行首锚定（\r?\n）——否则行内伏笔「(HIGH，第N章回收)」的"第N章"
            // 被误判为下一章起点，章节被拦腰截断、脏文本（"回收)；埋设-…"）混入后续章标题
            Matcher chM2 = Pattern.compile("\\*{0,2}第([0-9一二三四五六七八九十百]+)章\\s*(.+?)[：:]\\s*(.+?)(?=\\r?\\n\\*{0,2}第\\d+章|$)", Pattern.DOTALL).matcher(volBody);
            while (chM2.find()) {
                int idx = parseChineseOrArabic(chM2.group(1));
                if (existingIndices.contains(idx)) continue; // p12-fix: 去重
                existingIndices.add(idx);
                String title = sanitizeTitle(chM2.group(2));
                String rawContent = chM2.group(3).trim();
                ChapterSynopsis cs = new ChapterSynopsis(projectId, idx, title, rawContent);
                parseChapterDetails(cs, rawContent);
                volume.addSynopsis(cs);
            }
        }
    }

    /** 从章节原始内容中拆分场景节拍、爽点、出场人物 */
    /** p10-w3-02: 改 public 供 NovelOrchestrator.parseAndAppendVolume 复用（延迟卷大纲补全结构化字段） */
    public void parseChapterDetails(ChapterSynopsis cs, String rawContent) {
        // 处理 markdown 加粗标记：**爽点** → 爽点
        String clean = rawContent.replaceAll("\\*\\*", "");

        String beats = clean;
        String climax = null;
        String cast = null;

        // 提取场景节拍（"场景节拍：" 到 "爽点：" 之间）
        Matcher beatsM = Pattern.compile("场景节拍[：:]\\s*(.+?)(?=爽点[：:]|$)", Pattern.DOTALL).matcher(clean);
        if (beatsM.find()) {
            beats = beatsM.group(1).trim();
        }

        // 提取爽点（支持 "爽点：" 和 "爽点：**" 格式，到 "人物：" 或 "目的：" 为止）
        Matcher climaxM = Pattern.compile("爽点[：:]\\s*(.+?)(?=人物[：:]|目的[：:]|张力[：:]|$)", Pattern.DOTALL).matcher(clean);
        if (climaxM.find()) {
            climax = climaxM.group(1).trim();
        }

        // 提取人物（到 "目的：" 或 "张力：" 为止）
        Matcher castM = Pattern.compile("人物[：:]\\s*(.+?)(?=目的[：:]|张力[：:]|$)", Pattern.DOTALL).matcher(clean);
        if (castM.find()) {
            cast = castM.group(1).trim();
        }

        cs.setBeats(beats);
        cs.setClimax(climax);
        cs.setCast(cast);

        // 提取剧情目的
        Matcher purposeM = Pattern.compile("目的[：:]\\s*(.+?)(?=。?张力[：:]|。?人物[：:]|$)").matcher(clean);
        if (purposeM.find()) {
            cs.setPlotPurpose(purposeM.group(1).trim());
        }

        // 提取张力等级
        Matcher tensionM = Pattern.compile("张力[：:]\\s*(\\d+)").matcher(clean);
        if (tensionM.find()) {
            try { cs.setTensionLevel(Integer.parseInt(tensionM.group(1))); } catch (Exception ignored) {}
        }

        // p10-c1: 提取伏笔规划（"伏笔：" 后的内容，区分"埋设-"和"回收-"）
        Matcher foreshadowM = Pattern.compile("伏笔[：:]\\s*(.+?)(?=场景节拍[：:]|$)", Pattern.DOTALL).matcher(clean);
        if (foreshadowM.find()) {
            String fText = foreshadowM.group(1).trim();
            if (!"无".equals(fText) && !fText.isEmpty()) {
                StringBuilder toPlant = new StringBuilder();
                StringBuilder toResolve = new StringBuilder();
                // 分号或换行分隔多条
                for (String item : fText.split("[;；\\n]")) {
                    item = item.trim();
                    if (item.startsWith("埋设") || item.startsWith("埋下")) {
                        toPlant.append(item).append(";");
                    } else if (item.startsWith("回收")) {
                        toResolve.append(item).append(";");
                    } else {
                        // 无法区分的，默认为埋设
                        if (!item.isEmpty()) toPlant.append(item).append(";");
                    }
                }
                if (toPlant.length() > 0) cs.setForeshadowingsToPlant(toPlant.toString());
                if (toResolve.length() > 0) cs.setForeshadowingsToResolve(toResolve.toString());
            }
        }
    }

    /** 清理标题中的 markdown 标记（**、#、多余空格） */
    private String sanitizeTitle(String title) {
        if (title == null) return "";
        // p16-fix: 标题内嵌换行压成空格（解析异常时的最后防线，脏内容不得跨行污染展示）
        return title.replaceAll("\\*{2}", "").replaceAll("#+\\s*", "")
                .replaceAll("\\r?\\n+", " ").trim();
    }

    /** 解析中文数字或阿拉伯数字（一→1，二→2...十→10，二十→20） */
    private int parseChineseOrArabic(String s) {
        if (s.matches("\\d+")) return Integer.parseInt(s);
        // 简单中文数字转换（支持 0-99）
        String[] digits = {"零","一","二","三","四","五","六","七","八","九"};
        if (s.equals("十")) return 10;
        if (s.startsWith("十")) return 10 + parseChineseOrArabic(s.substring(1));
        if (s.endsWith("十")) return parseChineseOrArabic(s.substring(0, 1)) * 10;
        if (s.contains("十")) {
            String[] parts = s.split("十");
            return parseChineseOrArabic(parts[0]) * 10 + (parts.length > 1 ? parseChineseOrArabic(parts[1]) : 0);
        }
        for (int i = 0; i < digits.length; i++) {
            if (digits[i].equals(s)) return i;
        }
        return 1; // 兜底
    }

    /**
     * p12-02: 剥离 LLM 客套前言（如"好的，作为您的专属长篇小说大纲规划专家…"）。
     * 客套话污染 outline.premise 后会进入后续所有 prompt。以首个【总纲】/【卷N】/# 标题为界截断。
     */
    static String stripPreamble(String content) {
        if (content == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:【总纲】|【卷\\d+】|^#\\s)")
                .matcher(content);
        if (m.find() && m.start() > 0) {
            return content.substring(m.start()).trim();
        }
        return content.trim();
    }
}
