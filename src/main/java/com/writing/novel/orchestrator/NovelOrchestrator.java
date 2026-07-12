package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.spi.NovelRepository;

import java.util.List;

/**
 * 小说编排器——系统大脑。驱动状态机，调度 7 个 Agent。
 */
public class NovelOrchestrator {

    private final NovelRepository repository;
    private final ProjectService projectService;
    private final WorldBuilderAgent worldBuilder;
    private final CharacterDesignerAgent characterDesigner;
    private final PlotterAgent plotter;
    private final ChapterWriterAgent chapterWriter;
    private final ContinuityCheckerAgent continuityChecker;
    private final StyleGuardianAgent styleGuardian;
    private final MemoryManagerAgent memoryManager;
    private final SummaryService summaryService;
    private final ContextAssembler contextAssembler;

    public NovelOrchestrator(NovelRepository repository,
                              ProjectService projectService,
                              WorldBuilderAgent worldBuilder,
                              CharacterDesignerAgent characterDesigner,
                              PlotterAgent plotter,
                              ChapterWriterAgent chapterWriter,
                              ContinuityCheckerAgent continuityChecker,
                              StyleGuardianAgent styleGuardian,
                              MemoryManagerAgent memoryManager,
                              SummaryService summaryService,
                              ContextAssembler contextAssembler) {
        this.repository = repository;
        this.projectService = projectService;
        this.worldBuilder = worldBuilder;
        this.characterDesigner = characterDesigner;
        this.plotter = plotter;
        this.chapterWriter = chapterWriter;
        this.continuityChecker = continuityChecker;
        this.styleGuardian = styleGuardian;
        this.memoryManager = memoryManager;
        this.summaryService = summaryService;
        this.contextAssembler = contextAssembler;
    }

    /** 构思阶段：生成世界观 + 人物 + 大纲 */
    public void runConceiving(String projectId) {
        Project p = loadProject(projectId);
        if (p.getState() != NovelState.CONCEIVING && p.getState() != NovelState.PAUSED_AT_CHECKPOINT) {
            throw new IllegalStateException("项目不在构思阶段: " + p.getState());
        }

        worldBuilder.build(projectId, p.getConcept(), p.getTags());

        // 风格基线前置：在世界观生成后立即建立全书风格基线（幂等：已有则跳过）
        if (repository.findStyleProfileByProject(projectId).isEmpty()) {
            styleGuardian.establishProfile(projectId,
                    "世界观构思：" + p.getConcept() + "\n类型标签：" + p.getTags());
        }

        // 人物设计（幂等：已有主角则跳过，防止重新构思时重复生成）
        if (repository.findCharactersByProject(projectId).isEmpty()) {
            characterDesigner.design(projectId, "主角", List.of());
        }

        // 大纲（幂等：已有大纲则跳过）
        if (repository.findOutlineByProject(projectId).isEmpty()) {
            plotter.plan(projectId, p.getConcept(), p.getConcept(), 10);
        }

        if (p.getMode() == ProjectMode.INTERACTIVE) {
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repository.saveCheckpoint(new Checkpoint(projectId,
                    Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定生成完成，待审核"));
        } else {
            p.setState(NovelState.PLANNING);
        }
        repository.saveProject(p);
    }

    /** 从当前状态推进一步 */
    public void step(String projectId) {
        Project p = loadProject(projectId);
        switch (p.getState()) {
            case CONCEIVING -> runConceiving(projectId);
            case PAUSED_AT_CHECKPOINT -> resumeFromCheckpoint(projectId);
            case PLANNING -> runPlanning(projectId);
            case WRITING -> runWritingOneChapter(projectId);
            case VOLUME_DONE -> startNextVolumeOrFinish(projectId);
            default -> throw new IllegalStateException("无法从状态推进: " + p.getState());
        }
    }

    private void resumeFromCheckpoint(String projectId) {
        Project p = loadProject(projectId);
        p.setState(NovelState.PLANNING);
        repository.saveProject(p);
    }

    private void runPlanning(String projectId) {
        Project p = loadProject(projectId);
        p.setState(NovelState.WRITING);
        repository.saveProject(p);
    }

    /** 写一章 */
    public Chapter runWritingOneChapter(String projectId) {
        Project p = loadProject(projectId);
        Outline outline = repository.findOutlineByProject(projectId).orElseThrow(
                () -> new IllegalStateException("无大纲，无法写作"));

        int chapterIndex = p.getCurrentChapterIndex() + 1;
        ChapterSynopsis synopsis = findSynopsis(outline, chapterIndex);
        if (synopsis == null) {
            p.setState(NovelState.VOLUME_DONE);
            repository.saveProject(p);
            return null;
        }

        Chapter chapter = chapterWriter.write(projectId, chapterIndex,
                synopsis.getTitle(), synopsis.getSynopsis(), synopsis.getWritingNotes());

        p.setCurrentChapterIndex(chapterIndex);
        repository.saveProject(p);

        // 写完后做连续性检查（发现问题记录到 DB，不中断写作）
        checkContinuityAfterWriting(projectId, chapterIndex, chapter);

        if (chapterIndex % 3 == 0) {
            generateSummary(projectId, chapterIndex - 2, chapterIndex);
        }

        return chapter;
    }

    /** 写完一章后检查连续性，发现矛盾记录到 DB（不中断写作流程） */
    private void checkContinuityAfterWriting(String projectId, int chapterIndex, Chapter chapter) {
        try {
            String settings = collectSettingsForCheck(projectId);
            continuityChecker.check(projectId, chapterIndex, chapter.getContent(), settings);
        } catch (Exception e) {
            // 检查失败不中断写作
        }
    }

    /** 收集设定摘要供连续性检查参考 */
    private String collectSettingsForCheck(String projectId) {
        StringBuilder sb = new StringBuilder();
        for (var c : repository.findCharactersByProject(projectId)) {
            sb.append(c.getName()).append("（").append(c.getRole()).append("）");
            if (c.getPersonality() != null) sb.append(" 性格：").append(c.getPersonality());
            if (c.getAbility() != null) sb.append(" 能力：").append(c.getAbility());
            sb.append("\n");
        }
        for (var e : repository.findKnowledgeEntriesByProject(projectId)) {
            sb.append("【").append(e.getName()).append("】").append(e.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 连续写作：从当前章节一直写到大纲结束，或达到 maxChapters 上限。
     * 返回本次新写的章节数。
     */
    public int runWritingLoop(String projectId, int maxChapters) {
        int written = 0;
        while (written < maxChapters) {
            Project p = loadProject(projectId);
            // 检查是否还有未写的章节
            Outline outline = repository.findOutlineByProject(projectId).orElse(null);
            if (outline == null) break;
            if (!hasNextChapter(outline, p.getCurrentChapterIndex())) {
                p.setState(NovelState.FINAL);
                repository.saveProject(p);
                break;
            }
            // 如果在 VOLUME_DONE 状态，先转回 WRITING
            if (p.getState() == NovelState.VOLUME_DONE) {
                p.setState(NovelState.WRITING);
                repository.saveProject(p);
            }
            Chapter ch = runWritingOneChapter(projectId);
            if (ch == null) break;
            written++;
        }
        return written;
    }

    /** 统计项目总字数 */
    public int getTotalWordCount(String projectId) {
        return repository.findChaptersByProject(projectId).stream()
                .mapToInt(Chapter::getWordCount)
                .sum();
    }

    private void generateSummary(String projectId, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            repository.findChapterByIndex(projectId, i).ifPresent(c ->
                    sb.append(c.getContent()).append("\n"));
        }
        if (!sb.isEmpty()) {
            memoryManager.summarize(projectId, from, to, sb.toString());
        }
    }

    private void startNextVolumeOrFinish(String projectId) {
        Project p = loadProject(projectId);
        Outline outline = repository.findOutlineByProject(projectId).orElse(null);
        if (outline != null && hasNextChapter(outline, p.getCurrentChapterIndex())) {
            p.setState(NovelState.WRITING);
        } else {
            p.setState(NovelState.FINAL);
        }
        repository.saveProject(p);
    }

    private ChapterSynopsis findSynopsis(Outline outline, int chapterIndex) {
        for (Volume v : outline.getVolumes()) {
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() == chapterIndex) return s;
            }
        }
        return null;
    }

    private boolean hasNextChapter(Outline outline, int currentIndex) {
        return outline.getVolumes().stream()
                .flatMap(v -> v.getSynopses().stream())
                .anyMatch(s -> s.getChapterIndex() > currentIndex);
    }

    private Project loadProject(String projectId) {
        return repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
    }
}
