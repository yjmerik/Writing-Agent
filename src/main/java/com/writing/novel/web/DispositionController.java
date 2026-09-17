package com.writing.novel.web;

import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.ReviewDisposition;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** p38-11：问题裁决 API——保存/查询/按当前稿重新检查。 */
@RestController
@RequestMapping("/api/projects/{id}/dispositions")
public class DispositionController {

    private final NovelRepository repository;

    public DispositionController(NovelRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public Map<String, Object> create(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String issueId = String.valueOf(body.getOrDefault("issueId", ""));
        if (issueId.isBlank()) throw new IllegalArgumentException("issueId 不能为空");
        String action = String.valueOf(body.getOrDefault("action", "")).toUpperCase();
        if (!Set.of("FALSE_POSITIVE", "EXCEPTION", "DEFER").contains(action)) {
            throw new IllegalArgumentException("未知裁决类型: " + action);
        }
        String reason = body.get("reason") instanceof String text ? text.trim() : "";
        if (reason.isEmpty()) throw new IllegalArgumentException("裁决必须说明理由");
        Integer deferUntil = body.get("deferUntilChapter") == null ? null
                : Integer.valueOf(String.valueOf(body.get("deferUntilChapter")));
        if ("DEFER".equals(action) && deferUntil == null) {
            throw new IllegalArgumentException("DEFER 必须指定 deferUntilChapter");
        }

        if (!(body.get("identity") instanceof Map<?, ?> identityRaw)) {
            throw new IllegalArgumentException("identity 必须是稿件身份对象");
        }
        String identityProjectId = String.valueOf(identityRaw.get("projectId"));
        if (!id.equals(identityProjectId)) {
            throw new IllegalArgumentException("identity.projectId 与路径项目不符，不能跨项目操作");
        }
        Integer chapterIndex = intOf(identityRaw.get("chapterIndex"));
        Integer version = intOf(identityRaw.get("version"));
        String hash = identityRaw.get("contentHash") instanceof String text ? text : "";
        if (chapterIndex == null || chapterIndex < 1 || version == null || version < 1 || hash.isBlank()) {
            throw new IllegalArgumentException("identity 需要有效 chapterIndex/version/contentHash");
        }
        var issue = repository.findContinuityIssuesByProject(id).stream()
                .filter(item -> issueId.equals(item.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("问题不存在或不属于该项目"));
        if (issue.getDetectedAtChapter() != chapterIndex) {
            throw new IllegalArgumentException("问题不属于指定章节");
        }
        var chapter = repository.findChapterByIndex(id, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("指定章节不存在"));
        if (version != chapter.getVersion() || !hash.equals(ChapterApproval.contentHash(chapter.getTitle(), chapter.getContent()))) {
            throw new IllegalStateException("稿件身份已变化或无效，请刷新后重新裁决");
        }
        ChapterApproval.Identity identity = new ChapterApproval.Identity(id, chapterIndex, version, hash);

        ReviewDisposition disposition = new ReviewDisposition(
                UUID.randomUUID().toString(), id, issueId,
                "fp-" + issueId, identity, ReviewDisposition.Action.valueOf(action),
                reason, deferUntil, java.time.Instant.now());
        repository.saveDisposition(disposition);
        return view(disposition);
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String id,
                                          @RequestParam int chapterIndex,
                                          @RequestParam int chapterVersion,
                                          @RequestParam String contentHash) {
        return repository.findDispositions(id, chapterIndex, chapterVersion, contentHash)
                .stream().map(this::view).toList();
    }

    private Map<String, Object> view(ReviewDisposition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("issueId", d.getIssueId());
        m.put("action", d.getAction().name());
        m.put("reason", d.getReason());
        m.put("deferUntilChapter", d.getDeferUntilChapter());
        m.put("createdAt", d.getCreatedAt().toString());
        return m;
    }

    private Integer intOf(Object value) {
        if (value == null) return null;
        try { return Integer.parseInt(String.valueOf(value).trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
