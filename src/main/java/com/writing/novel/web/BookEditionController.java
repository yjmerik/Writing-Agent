package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.BookEdition;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.spi.NovelRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * p38-05/06/07（审计 P1-4）：固定成书版本——快照物化、批准绑定 manifestHash、导出固定字节。
 * Project.bookSnapshot 是长记忆摘要，不是成书版；导出读取 book_edition_chapters 物化副本，
 * 后续修改正文不影响历史下载。
 */
@RestController
@RequestMapping("/api/projects/{id}/book-editions")
public class BookEditionController {

    private final NovelRepository repository;
    private final ObjectMapper objectMapper;

    public BookEditionController(NovelRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 创建一致快照：物化每章正文/身份/批准，计算 manifestHash。 */
    @PostMapping
    public Map<String, Object> create(@PathVariable String id) {
        repository.findProject(id).orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        List<Chapter> chapters = repository.findChaptersByProject(id).stream()
                .sorted((a, b) -> Integer.compare(a.getChapterIndex(), b.getChapterIndex()))
                .toList();
        if (chapters.isEmpty()) {
            throw new IllegalStateException("无章节可成书");
        }
        List<BookEdition.EditionChapter> editionChapters = new ArrayList<>();
        for (Chapter ch : chapters) {
            String hash = ChapterApproval.contentHash(ch.getTitle(), ch.getContent());
            String approvalId = repository.isChapterApproved(id, ch.getChapterIndex(), ch.getVersion(), hash)
                    ? findApprovalId(id, ch.getChapterIndex(), ch.getVersion(), hash)
                    : null;
            editionChapters.add(new BookEdition.EditionChapter(
                    ch.getChapterIndex(), ch.getVersion(), ch.getTitle(), ch.getContent(),
                    hash, approvalId, ch.getPlanRef()));
        }
        String manifestHash = manifestHash(editionChapters);
        BookEdition edition = new BookEdition(UUID.randomUUID().toString(), id, manifestHash,
                BookEdition.State.DRAFT, Instant.now(), null);
        repository.saveBookEdition(edition, editionChapters);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("editionId", edition.getEditionId());
        result.put("manifestHash", manifestHash);
        result.put("state", "DRAFT");
        result.put("chapterCount", editionChapters.size());
        result.put("approvedChapters", editionChapters.stream()
                .filter(c -> c.approvalId() != null).count());
        return result;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String id) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BookEdition edition : repository.listBookEditions(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("editionId", edition.getEditionId());
            m.put("manifestHash", edition.getManifestHash());
            m.put("state", edition.getState().name());
            m.put("createdAt", edition.getCreatedAt().toString());
            if (edition.getApprovedAt() != null) m.put("approvedAt", edition.getApprovedAt().toString());
            result.add(m);
        }
        return result;
    }

    @GetMapping("/{editionId}")
    public Map<String, Object> detail(@PathVariable String id, @PathVariable String editionId) {
        BookEdition edition = repository.findBookEdition(id, editionId)
                .orElseThrow(() -> new IllegalArgumentException("成书版本不存在: " + editionId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("editionId", edition.getEditionId());
        result.put("manifestHash", edition.getManifestHash());
        result.put("state", edition.getState().name());
        result.put("createdAt", edition.getCreatedAt().toString());
        if (edition.getApprovedAt() != null) result.put("approvedAt", edition.getApprovedAt().toString());
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (BookEdition.EditionChapter ch : repository.findBookEditionChapters(editionId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chapterIndex", ch.chapterIndex());
            m.put("version", ch.version());
            m.put("title", ch.title());
            m.put("contentHash", ch.contentHash());
            m.put("approvalId", ch.approvalId());
            m.put("wordCount", ch.content() == null ? 0 : ch.content().length());
            chapters.add(m);
        }
        result.put("chapters", chapters);
        return result;
    }

    /** 批准：重读当前覆盖资格（全部章节有当前身份批准 + 无活动屏障），绑定 manifestHash。 */
    @PostMapping("/{editionId}/approve")
    public Map<String, Object> approve(@PathVariable String id, @PathVariable String editionId,
                                       @RequestBody Map<String, Object> body) {
        BookEdition edition = repository.findBookEdition(id, editionId)
                .orElseThrow(() -> new IllegalArgumentException("成书版本不存在: " + editionId));
        String providedHash = body == null || body.get("manifestHash") == null
                ? null : String.valueOf(body.get("manifestHash"));
        if (providedHash == null || providedHash.isBlank()) {
            throw new IllegalArgumentException("批准必须携带 manifestHash（服务端不补填）");
        }
        if (!providedHash.equals(edition.getManifestHash())) {
            throw new IllegalStateException("manifestHash 不匹配，拒绝批准");
        }
        // 重读当前覆盖资格：无活动屏障 + 每章当前稿已获作者批准
        if (!repository.findActiveReviewBarriers(id).isEmpty()) {
            throw new IllegalStateException("存在活动人审屏障，成书批准被阻塞");
        }
        for (Chapter ch : repository.findChaptersByProject(id)) {
            String hash = ChapterApproval.contentHash(ch.getTitle(), ch.getContent());
            if (!repository.isChapterApproved(id, ch.getChapterIndex(), ch.getVersion(), hash)) {
                throw new IllegalStateException("第" + ch.getChapterIndex() + "章当前稿未获作者批准");
            }
        }
        repository.approveBookEdition(id, editionId, providedHash);
        return Map.of("editionId", editionId, "state", "APPROVED", "manifestHash", providedHash);
    }

    /** 导出：固定字节来自物化快照，后续正文修改不影响下载。 */
    @GetMapping(value = "/{editionId}/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> export(@PathVariable String id, @PathVariable String editionId) {
        BookEdition edition = repository.findBookEdition(id, editionId)
                .orElseThrow(() -> new IllegalArgumentException("成书版本不存在: " + editionId));
        String title = repository.findProject(id).map(p -> p.getTitle()).orElse("未命名");
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("> 成书版本 ").append(editionId)
                .append(" · manifest ").append(edition.getManifestHash())
                .append(" · ").append(edition.getState().name()).append("\n\n");
        for (BookEdition.EditionChapter ch : repository.findBookEditionChapters(editionId)) {
            md.append("## 第").append(ch.chapterIndex()).append("章 ")
                    .append(ch.title() == null ? "" : ch.title().replace("\n", " "))
                    .append("（v").append(ch.version()).append("）\n\n");
            md.append(ch.content() == null ? "" : ch.content()).append("\n\n");
        }
        byte[] bytes = md.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header("Content-Disposition",
                        "attachment; filename=\"book-edition-" + editionId + ".md\"")
                .body(bytes);
    }

    private String findApprovalId(String projectId, int chapterIndex, int version, String hash) {
        // 批准 ID 查询：复用 readReviewContext（当前身份匹配时返回 approval）
        try {
            var ctx = repository.readReviewContext(projectId, chapterIndex);
            if (ctx.approval() != null && ctx.identity().version() == version
                    && ctx.identity().contentHash().equals(hash)) {
                return ctx.approval().approvalId();
            }
        } catch (Exception ignored) {
            // 读失败不阻塞快照（approvalId 可为 null）
        }
        return null;
    }

    /** manifestHash：按章号排序的规范 JSON 全 SHA-256。 */
    private String manifestHash(List<BookEdition.EditionChapter> chapters) {
        try {
            List<Map<String, Object>> canonical = new ArrayList<>();
            TreeMap<Integer, BookEdition.EditionChapter> sorted = new TreeMap<>();
            for (BookEdition.EditionChapter ch : chapters) sorted.put(ch.chapterIndex(), ch);
            for (BookEdition.EditionChapter ch : sorted.values()) {
                Map<String, Object> m = new TreeMap<>();
                m.put("chapterIndex", ch.chapterIndex());
                m.put("version", ch.version());
                m.put("title", ch.title());
                m.put("contentHash", ch.contentHash());
                m.put("approvalId", ch.approvalId());
                canonical.add(m);
            }
            String json = objectMapper.writeValueAsString(canonical);
            return com.writing.novel.domain.RevisionProposal.canonicalJsonSha256(json);
        } catch (Exception e) {
            throw new IllegalStateException("manifestHash 计算失败", e);
        }
    }
}
