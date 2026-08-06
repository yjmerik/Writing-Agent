package com.writing.novel.web;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{id}")
public class ContentController {

    private final NovelRepository repository;
    private final NovelOrchestrator orchestrator;

    public ContentController(NovelRepository repository, NovelOrchestrator orchestrator) {
        this.repository = repository;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/worldbuilding")
    public List<KnowledgeEntry> worldbuilding(@PathVariable String id) {
        return repository.findKnowledgeEntriesByProject(id);
    }

    @GetMapping("/characters")
    public List<Character> characters(@PathVariable String id) {
        return repository.findCharactersByProject(id);
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

    @PostMapping("/write-chapter")
    public Chapter writeChapter(@PathVariable String id) {
        return orchestrator.runWritingOneChapter(id);
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

    @GetMapping("/memory/summary")
    public List<Summary> summaries(@PathVariable String id) {
        return repository.findSummariesByProject(id);
    }
}
