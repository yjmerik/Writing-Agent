package com.writing.novel.web;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.service.CanonPatchService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{id}")
public class ContentController {

    private final NovelRepository repository;
    private final NovelOrchestrator orchestrator;
    private final CanonPatchService canonPatch;
    private final WritingTaskExecutor writingTaskExecutor;

    public ContentController(NovelRepository repository, NovelOrchestrator orchestrator,
                             CanonPatchService canonPatch, WritingTaskExecutor writingTaskExecutor) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.canonPatch = canonPatch;
        this.writingTaskExecutor = writingTaskExecutor;
    }

    @GetMapping("/worldbuilding")
    public List<KnowledgeEntry> worldbuilding(@PathVariable String id) {
        return repository.findKnowledgeEntriesByProject(id);
    }

    @PostMapping("/worldbuilding")
    public KnowledgeEntry createWorld(@PathVariable String id, @RequestBody Map<String, String> body) {
        rejectIfWriting(id);
        KnowledgeEntry entry = new KnowledgeEntry(id,
                body.getOrDefault("type", "CUSTOM"),
                body.getOrDefault("name", ""),
                body.getOrDefault("content", ""));
        return (KnowledgeEntry) canonPatch.createWorld(id, entry, chapterOf(id)).entity();
    }

    @GetMapping("/characters")
    public List<Character> characters(@PathVariable String id) {
        return repository.findCharactersByProject(id);
    }

    @PostMapping("/characters")
    public Character createCharacter(@PathVariable String id, @RequestBody Map<String, String> body) {
        rejectIfWriting(id);
        Character created = new Character(id, body.get("name"), body.getOrDefault("role", "配角"));
        if (body.get("personality") != null) created.setPersonality(body.get("personality"));
        if (body.get("ability") != null) created.setAbility(body.get("ability"));
        if (body.get("motivation") != null) created.setMotivation(body.get("motivation"));
        if (body.get("appearance") != null) created.setAppearance(body.get("appearance"));
        if (body.get("relationships") != null) created.setRelationships(body.get("relationships"));
        if (body.get("growthArc") != null) created.setGrowthArc(body.get("growthArc"));
        return (Character) canonPatch.createCharacter(id, created, chapterOf(id)).entity();
    }

    @PutMapping("/characters/{charId}")
    public Character updateCharacter(@PathVariable String id, @PathVariable String charId,
                                     @RequestBody Map<String, String> body) {
        rejectIfWriting(id);
        Character patch = new Character(id, body.get("name"), body.get("role"));
        if (body.get("personality") != null) patch.setPersonality(body.get("personality"));
        if (body.get("ability") != null) patch.setAbility(body.get("ability"));
        if (body.get("motivation") != null) patch.setMotivation(body.get("motivation"));
        if (body.get("appearance") != null) patch.setAppearance(body.get("appearance"));
        if (body.get("relationships") != null) patch.setRelationships(body.get("relationships"));
        if (body.get("growthArc") != null) patch.setGrowthArc(body.get("growthArc"));
        return (Character) canonPatch.patchCharacter(id, charId, patch, chapterOf(id)).entity();
    }

    @PutMapping("/worldbuilding/{entryId}")
    public KnowledgeEntry updateWorld(@PathVariable String id, @PathVariable String entryId,
                                      @RequestBody Map<String, String> body) {
        rejectIfWriting(id);
        KnowledgeEntry patch = new KnowledgeEntry(id,
                body.getOrDefault("type", "CUSTOM"),
                body.getOrDefault("name", ""),
                body.getOrDefault("content", ""));
        return (KnowledgeEntry) canonPatch.patchWorld(id, entryId, patch, chapterOf(id)).entity();
    }

    @PutMapping("/outline")
    public Outline updateOutline(@PathVariable String id, @RequestBody Outline incoming) {
        rejectIfWriting(id);
        return (Outline) canonPatch.patchOutline(id, incoming, chapterOf(id)).entity();
    }

    @GetMapping("/facts")
    public List<com.writing.novel.domain.StoryFact> facts(@PathVariable String id) {
        return canonPatch.listActive(id);
    }

    @PostMapping("/facts")
    public com.writing.novel.domain.StoryFact addFact(@PathVariable String id,
                                                      @RequestBody Map<String, String> body) {
        rejectIfWriting(id);
        var type = com.writing.novel.domain.StoryFact.FactType.valueOf(
                body.getOrDefault("factType", "PLOT_COMMITMENT"));
        return canonPatch.addFact(id, type, body.getOrDefault("subject", "补丁"),
                body.getOrDefault("assertion", ""), chapterOf(id));
    }

    @PutMapping("/facts/{factId}")
    public com.writing.novel.domain.StoryFact updateFact(@PathVariable String id,
                                                         @PathVariable String factId,
                                                         @RequestBody Map<String, String> body) {
        rejectIfWriting(id);
        Integer importance = body.containsKey("importance") ? Integer.parseInt(body.get("importance")) : null;
        return (com.writing.novel.domain.StoryFact) canonPatch.patchFact(
                id, factId, body.get("assertion"), importance, chapterOf(id)).entity();
    }

    @PostMapping("/facts/{factId}/retire")
    public com.writing.novel.domain.StoryFact retireFact(@PathVariable String id, @PathVariable String factId) {
        rejectIfWriting(id);
        return canonPatch.retireFact(id, factId);
    }

    @GetMapping("/foreshadowings")
    public List<Foreshadowing> foreshadowings(@PathVariable String id) {
        return repository.findForeshadowingsByProject(id);
    }

    @PostMapping("/foreshadowings")
    public Foreshadowing createForeshadowing(@PathVariable String id, @RequestBody Map<String, Object> body) {
        rejectIfWriting(id);
        String content = String.valueOf(body.getOrDefault("content", body.getOrDefault("description", "")));
        Foreshadowing planted = new Foreshadowing(id, content, "HUMAN", chapterOf(id));
        planted.setMustResolve(Boolean.parseBoolean(String.valueOf(body.getOrDefault("mustResolve", "false"))));
        repository.saveForeshadowing(planted);
        return planted;
    }

    @PostMapping("/foreshadowings/{fid}/resolve")
    public Foreshadowing resolveForeshadowing(@PathVariable String id, @PathVariable String fid) {
        rejectIfWriting(id);
        Foreshadowing existing = repository.findForeshadowingsByProject(id).stream()
                .filter(f -> fid.equals(f.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("伏笔不存在: " + fid));
        existing.resolve(chapterOf(id));
        repository.saveForeshadowing(existing);
        return existing;
    }

    @PostMapping("/chapters/{chapterIndex}/resolve-review")
    public Chapter resolveManualReview(@PathVariable String id, @PathVariable int chapterIndex) {
        rejectIfWriting(id);
        Chapter chapter = repository.findChapterByIndex(id, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
        if (chapter.getStatus() != Chapter.Status.NEEDS_MANUAL_REVIEW) {
            throw new IllegalStateException("仅 NEEDS_MANUAL_REVIEW 状态的章节可处理人工复核");
        }
        chapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(chapter);
        return chapter;
    }

    @PostMapping("/replan")
    public Outline replan(@PathVariable String id) {
        rejectIfWriting(id);
        return orchestrator.replanRemaining(id);
    }

    @GetMapping("/outline")
    public Outline outline(@PathVariable String id) {
        return repository.findOutlineByProject(id).orElse(null);
    }

    @GetMapping("/chapters")
    public List<Chapter> chapters(@PathVariable String id) {
        return repository.findChaptersByProject(id);
    }

    @GetMapping("/chapters/{chapterIndex}")
    public Chapter chapter(@PathVariable String id, @PathVariable int chapterIndex) {
        return repository.findChapterByIndex(id, chapterIndex).orElse(null);
    }

    @PostMapping("/expand/characters")
    public Character expandCharacters(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        rejectIfWriting(id);
        String instruction = body != null ? body.getOrDefault("instruction", "") : "";
        return orchestrator.expandCharacters(id, instruction);
    }

    @PostMapping("/expand/world")
    public List<KnowledgeEntry> expandWorld(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        rejectIfWriting(id);
        String instruction = body != null ? body.getOrDefault("instruction", "") : "";
        return orchestrator.expandWorld(id, instruction);
    }

    @PostMapping("/characters/promote/{entityId}")
    public Character promoteEntity(@PathVariable String id, @PathVariable String entityId) {
        rejectIfWriting(id);
        return orchestrator.promoteEntity(id, entityId);
    }

    /** p15-05: 单章写作——登记 WRITE_CHAPTER 任务，正文落库后返回 Chapter；失败置 FAILED 并重抛 */
    @PostMapping("/write-chapter")
    public Chapter writeChapter(@PathVariable String id,
                                 @RequestBody(required = false) java.util.Map<String, String> body) {
        // p18-03: 写前指令——作者对下一章的定向要求，透传本章 writingNotes
        String instruction = body != null ? body.get("instruction") : null;
        if (writingTaskExecutor == null) {
            return orchestrator.runWritingOneChapter(id, instruction);
        }
        WritingTask task = writingTaskExecutor.startWriteChapter(id);
        try {
            Chapter chapter = orchestrator.runWritingOneChapter(id, instruction);
            writingTaskExecutor.finishWriteChapter(id, task, WritingTask.Status.COMPLETED, null);
            return chapter;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "未知错误";
            writingTaskExecutor.finishWriteChapter(id, task, WritingTask.Status.FAILED, msg);
            throw e;
        }
    }

    /** p7-1: 重写指定章节（不限项目状态） */
    @PostMapping("/rewrite-chapter/{chapterIndex}")
    public Chapter rewriteChapter(@PathVariable String id, @PathVariable int chapterIndex,
                                   @RequestBody(required = false) java.util.Map<String, String> body) {
        String instructions = body != null ? body.get("instructions") : null;
        return orchestrator.rewriteChapter(id, chapterIndex, instructions);
    }

    /** p10.1-e1: 伏笔重要性回溯审计——手动触发 */
    @PostMapping("/audit-foreshadowings")
    public java.util.Map<String, Object> auditForeshadowings(@PathVariable String id) {
        int upgraded = orchestrator.auditForeshadowingImportance(id);
        return java.util.Map.of("upgraded", upgraded);
    }

    /** p11-I2: 获取章节聚合质量报告 */
    @GetMapping("/chapters/{chapterIndex}/quality")
    public ChapterQualityReport chapterQuality(@PathVariable String id,
                                                 @PathVariable int chapterIndex) {
        return orchestrator.buildChapterQualityReport(id, chapterIndex);
    }

    /** p11-G1: 段落级编辑——局部改写/扩充/插入 */
    @PostMapping("/chapters/{chapterIndex}/passage")
    public Chapter editPassage(@PathVariable String id, @PathVariable int chapterIndex,
                                @RequestBody PassageEditRequest req) {
        return orchestrator.editPassage(id, chapterIndex,
                req.getParagraphIndex(), req.getSelectedText(),
                req.getInstruction(), req.getMode());
    }

    @GetMapping("/memory/summary")
    public List<Summary> summaries(@PathVariable String id) {
        return repository.findSummariesByProject(id);
    }

    private void rejectIfWriting(String projectId) {
        if (writingTaskExecutor != null && writingTaskExecutor.hasActiveTask(projectId)) {
            throw new IllegalStateException("请先暂停写作再修改设定");
        }
    }

    private int chapterOf(String projectId) {
        return canonPatch.currentChapter(projectId);
    }
}
