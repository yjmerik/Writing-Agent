package com.writing.novel.web;

import com.writing.novel.persistence.FactEvidenceRepository;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * p39-01/02/03（审计 P1-4）：事实来源锚点与知情区间查询、人审补证据。
 * 来源总是某章某稿；quote 未核实时为 null（UNKNOWN 语义）；不按字符串猜测。
 */
@RestController
@RequestMapping("/api/projects/{id}/facts")
public class FactEvidenceController {

    private final NovelRepository repository;
    private final FactEvidenceRepository factEvidence;
    private final StoryFactRepository storyFacts;

    public FactEvidenceController(NovelRepository repository,
                                  FactEvidenceRepository factEvidence,
                                  StoryFactRepository storyFacts) {
        this.repository = repository;
        this.factEvidence = factEvidence;
        this.storyFacts = storyFacts;
    }

    /** 证据查询：按事实 / 截至章号（叙事时点）/ 知情者过滤。 */
    @GetMapping("/evidence")
    public List<Map<String, Object>> evidence(@PathVariable String id,
                                              @RequestParam String factId,
                                              @RequestParam(required = false) Integer asOfChapter,
                                              @RequestParam(required = false) String characterId) {
        List<Map<String, Object>> rows = asOfChapter == null
                ? factEvidence.findByFact(id, factId)
                : factEvidence.findKnownAt(id, factId, asOfChapter);
        if (characterId == null || characterId.isBlank()) {
            return rows;
        }
        // 知情者过滤：仅返回该角色在 asOfChapter（或证据章）前已知情的条目
        return rows.stream().filter(row -> {
            String evidenceId = String.valueOf(row.get("id"));
            int limit = asOfChapter != null ? asOfChapter
                    : ((Number) row.get("chapterIndex")).intValue();
            return factEvidence.findObservers(id, evidenceId).stream()
                    .anyMatch(o -> characterId.equals(o.get("characterId"))
                            && ((Number) o.get("learnedAtChapter")).intValue() <= limit);
        }).toList();
    }

    /** 人审补证据：必须绑定章/稿身份；quote 可选（缺省即 UNKNOWN，不伪造）。 */
    @PostMapping("/{factId}/evidence")
    public Map<String, Object> addEvidence(@PathVariable String id, @PathVariable String factId,
                                           @RequestBody Map<String, Object> body) {
        repository.findProject(id).orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        if (storyFacts.findById(factId).isEmpty()) {
            throw new IllegalArgumentException("事实不存在: " + factId);
        }
        Integer chapterIndex = intOf(body.get("chapterIndex"));
        Integer chapterVersion = intOf(body.get("chapterVersion"));
        String contentHash = strOf(body.get("contentHash"));
        if (chapterIndex == null || chapterVersion == null
                || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("证据必须绑定 chapterIndex/chapterVersion/contentHash");
        }
        String quote = strOf(body.get("quote")); // 可为 null = UNKNOWN
        Integer validFrom = intOf(body.get("validFromChapter"));
        Integer validTo = intOf(body.get("validToChapter"));
        String evidenceId = UUID.randomUUID().toString();
        storyFacts.runInTransaction(conn -> {
            storyFacts.saveFactEvidence(conn, evidenceId, factId, id,
                    chapterIndex, chapterVersion, contentHash,
                    quote, body.get("paragraphIndex") == null ? 0
                            : intOf(body.get("paragraphIndex")),
                    strOf(body.get("anchorHash")),
                    validFrom != null ? validFrom : chapterIndex, validTo);
            Object observersRaw = body.get("observers");
            if (observersRaw instanceof List<?> observers) {
                for (Object o : observers) {
                    if (o instanceof Map<?, ?> m) {
                        String characterId = m.get("characterId") == null ? null
                                : String.valueOf(m.get("characterId"));
                        Integer learnedAt = m.get("learnedAtChapter") == null ? null
                                : Integer.valueOf(String.valueOf(m.get("learnedAtChapter")));
                        if (characterId == null || learnedAt == null) {
                            throw new IllegalArgumentException("observer 需要 characterId 与 learnedAtChapter");
                        }
                        try (var ps = conn.prepareStatement(
                                "INSERT INTO fact_evidence_observers (id, evidence_id, character_id, " +
                                        "learned_at_chapter, chapter_version, quote) VALUES (?,?,?,?,?,?)")) {
                            ps.setString(1, UUID.randomUUID().toString());
                            ps.setString(2, evidenceId);
                            ps.setString(3, characterId);
                            ps.setInt(4, learnedAt);
                            ps.setInt(5, chapterVersion);
                            ps.setString(6, m.get("quote") == null ? null : String.valueOf(m.get("quote")));
                            ps.executeUpdate();
                        } catch (java.sql.SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            return null;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", evidenceId);
        result.put("factId", factId);
        result.put("chapterIndex", chapterIndex);
        result.put("chapterVersion", chapterVersion);
        result.put("quoteKnown", quote != null && !quote.isBlank());
        return result;
    }

    private Integer intOf(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
