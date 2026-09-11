
package com.writing.novel.web;

import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** p33-07: 候选线索层——作者提升为正式伏笔或拒绝（体验 #15 台账分层）。 */
@RestController
@RequestMapping("/api/projects/{id}/foreshadow-candidates")
public class ForeshadowCandidateController {

    private final NovelRepository repository;

    public ForeshadowCandidateController(NovelRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Object> list(@PathVariable String id) {
        return repository.findForeshadowCandidates(id).stream().<Object>map(c -> Map.of(
                "id", c.getId(),
                "description", c.getDescription(),
                "plantedContext", c.getPlantedContext() == null ? "" : c.getPlantedContext(),
                "plantedAtChapter", c.getPlantedAtChapter(),
                "status", c.getStatus().name())).toList();
    }

    @PostMapping("/{candidateId}/promote")
    public Map<String, Object> promote(@PathVariable String id, @PathVariable String candidateId) {
        repository.promoteForeshadowCandidate(id, candidateId);
        return Map.of("promoted", true, "candidateId", candidateId);
    }

    @PostMapping("/{candidateId}/reject")
    public Map<String, Object> reject(@PathVariable String id, @PathVariable String candidateId) {
        repository.rejectForeshadowCandidate(id, candidateId);
        return Map.of("rejected", true, "candidateId", candidateId);
    }
}
