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

    @GetMapping("/memory/summary")
    public List<Summary> summaries(@PathVariable String id) {
        return repository.findSummariesByProject(id);
    }
}
