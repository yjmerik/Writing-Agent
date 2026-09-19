package com.writing.novel.web;

import com.writing.novel.service.CharacterDecisionService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-012（方案 §07）：关键决策 API。
 * POST /decisions/extract?chapter=N 按需提取（幂等）；GET /decisions?character=名 查询。
 */
@RestController
@RequestMapping("/api/projects/{id}/decisions")
public class CharacterDecisionController {

    private final NovelRepository repository;
    private final CharacterDecisionService service;

    public CharacterDecisionController(NovelRepository repository, CharacterDecisionService service) {
        this.repository = repository;
        this.service = service;
    }

    @PostMapping("/extract")
    public Map<String, Object> extract(@PathVariable String id, @RequestParam int chapter) {
        repository.findProject(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        var chapterEntity = repository.findChapterByIndex(id, chapter)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapter));
        var result = service.extractForChapter(id, chapterEntity);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chapterIndex", chapter);
        m.put("chapterVersion", chapterEntity.getVersion());
        m.put("extracted", result.extracted());
        m.put("status", result.status().name());
        m.put("decisions", service.decisions(id).stream()
                .filter(d -> d.getChapterIndex() == chapter)
                .map(this::toMap).toList());
        return m;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable String id,
                                    @RequestParam(required = false) String character) {
        repository.findProject(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (var d : character == null || character.isBlank()
                ? service.decisions(id) : service.decisionsOf(id, character)) {
            decisions.add(toMap(d));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", id);
        m.put("decisions", decisions);
        return m;
    }

    private Map<String, Object> toMap(com.writing.novel.domain.CharacterDecision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("character", d.getCharacterName());
        m.put("chapterIndex", d.getChapterIndex());
        m.put("chapterVersion", d.getChapterVersion());
        m.put("triggerEvent", d.getTriggerEvent());
        m.put("knewThen", d.getKnewThen());
        m.put("options", d.getOptions());
        m.put("chosenAction", d.getChosenAction());
        m.put("cost", d.getCost());
        m.put("conflictWithCommitment", d.getConflictWithCommitment());
        m.put("evidenceQuote", d.getEvidenceQuote());
        return m;
    }
}
