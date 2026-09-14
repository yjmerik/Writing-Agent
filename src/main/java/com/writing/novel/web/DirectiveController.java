package com.writing.novel.web;

import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * p33-06/p37-08：作者意见闭环 API（版本化）。
 * POST 登记（kind/from/to）→ PATCH 范围编辑（expectedRevision CAS）→
 * 系统落实证据（APPLIED_SYSTEM，按章）→ 作者确认（identity+revision）→
 * 显式撤销（revoke 必须携带 reason）。原 /confirm 不再凭空确认 OPEN。
 */
@RestController
@RequestMapping("/api/projects/{id}/directives")
public class DirectiveController {

    private final NovelRepository repository;

    public DirectiveController(NovelRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public Map<String, Object> create(@PathVariable String id,
                                      @RequestBody Map<String, Object> body) {
        repository.findProject(id).orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        String kind = String.valueOf(body.getOrDefault("kind", "ONCE")).toUpperCase();
        if (!Set.of("HARD", "ONCE", "PREFERENCE").contains(kind)) {
            throw new IllegalArgumentException("未知意见类型: " + kind);
        }
        String content = String.valueOf(body.getOrDefault("content", "")).trim();
        if (content.isEmpty()) throw new IllegalArgumentException("意见内容不能为空");
        Integer chapterIndex = body.get("chapterIndex") == null ? null
                : intOrNull(body.get("chapterIndex"));
        AuthorDirective directive = new AuthorDirective(id,
                AuthorDirective.Kind.valueOf(kind), chapterIndex, content);
        Integer from = body.get("fromChapter") == null ? null : intOrNull(body.get("fromChapter"));
        Integer to = body.get("toChapter") == null ? null : intOrNull(body.get("toChapter"));
        directive.validateScope(from, to);
        directive.setScope(from, to);
        repository.saveAuthorDirective(directive);
        return view(repository.findAuthorDirective(id, directive.getId()).orElseThrow());
    }

    /** p37-08: 范围/内容编辑——expectedRevision CAS，revision+1。 */
    @PatchMapping("/{directiveId}")
    public Map<String, Object> update(@PathVariable String id, @PathVariable String directiveId,
                                      @RequestBody Map<String, Object> body) {
        AuthorDirective directive = require(id, directiveId);
        int expectedRevision = intOr(body.get("expectedRevision"), -1);
        if (body.containsKey("content")) {
            String content = String.valueOf(body.get("content")).trim();
            if (content.isEmpty()) throw new IllegalArgumentException("意见内容不能为空");
            directive.setPrivateContent(content);
        }
        Integer from = body.get("fromChapter") == null ? null : intOrNull(body.get("fromChapter"));
        Integer to = body.get("toChapter") == null ? null : intOrNull(body.get("toChapter"));
        directive.validateScope(from, to);
        directive.setScope(from, to);
        repository.updateDirective(directive, expectedRevision);
        return view(repository.findAuthorDirective(id, directiveId).orElseThrow());
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String id,
                                          @RequestParam(required = false) Boolean pendingOnly) {
        return repository.findAuthorDirectives(id).stream()
                .filter(d -> !(pendingOnly != null && pendingOnly)
                        || d.getStatus() == AuthorDirective.Status.OPEN
                        || d.getStatus() == AuthorDirective.Status.APPLIED_SYSTEM)
                .map(this::view).toList();
    }

    /** 系统记录落实证据——不等于作者确认。 */
    @PostMapping("/{directiveId}/apply-evidence")
    public Map<String, Object> applyEvidence(@PathVariable String id, @PathVariable String directiveId,
                                             @RequestBody Map<String, Object> body) {
        AuthorDirective directive = require(id, directiveId);
        String evidence = String.valueOf(body.getOrDefault("evidence", "")).trim();
        if (evidence.isEmpty()) throw new IllegalArgumentException("落实证据不能为空");
        Integer chapterVersion = body.get("chapterVersion") == null ? null
                : intOrNull(body.get("chapterVersion"));
        directive.applySystemEvidence(evidence, chapterVersion);
        repository.saveAuthorDirective(directive);
        return view(directive);
    }

    /** p37-08: 原 /confirm 关闭——凭空确认 OPEN 不再可用（409）。 */
    @PostMapping("/{directiveId}/confirm")
    public Map<String, Object> confirm(@PathVariable String id, @PathVariable String directiveId) {
        AuthorDirective directive = require(id, directiveId);
        if (directive.getStatus() == AuthorDirective.Status.OPEN) {
            throw new IllegalStateException("OPEN 意见不能直接确认，须先有 APPLIED_SYSTEM 证据并经逐章确认");
        }
        directive.confirm();
        repository.saveAuthorDirective(directive);
        return view(directive);
    }

    /** p37-08: 逐章证据确认——identity+revision，事务内重读。 */
    @PostMapping("/{directiveId}/evidence/{evidenceId}/confirm")
    public Map<String, Object> confirmEvidence(@PathVariable String id,
                                               @PathVariable String directiveId,
                                               @PathVariable String evidenceId,
                                               @RequestBody Map<String, Object> body) {
        Map<String, Object> identityRaw = body.get("identity") == null
                ? null : (Map<String, Object>) body.get("identity");
        if (identityRaw == null || !id.equals(String.valueOf(identityRaw.get("projectId")))) {
            throw new IllegalArgumentException("缺少 identity 或 projectId 与路径不符（服务端不补填）");
        }
        Integer version = intOrNull(identityRaw.get("version"));
        String contentHash = stringOrNull(identityRaw.get("contentHash"));
        if (version == null || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("缺少 identity.version/contentHash——服务端不补填");
        }
        Integer directiveRevision = intOrNull(body.get("directiveRevision"));
        if (directiveRevision == null) {
            throw new IllegalArgumentException("缺少 directiveRevision——服务端不补填");
        }
        ChapterApproval.Identity identity = new ChapterApproval.Identity(
                id, version != null ? intOrNull(identityRaw.get("chapterIndex")) : 0,
                version, contentHash);
        AuthorDirective.Evidence confirmed = repository.confirmDirectiveEvidence(
                id, evidenceId, identity, directiveRevision);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", confirmed.id());
        m.put("directiveId", confirmed.directiveId());
        m.put("directiveRevision", confirmed.directiveRevision());
        m.put("chapterIndex", confirmed.chapterIndex());
        m.put("chapterVersion", confirmed.chapterVersion());
        m.put("status", confirmed.status().name());
        return m;
    }

    @PostMapping("/{directiveId}/dismiss")
    public Map<String, Object> dismiss(@PathVariable String id, @PathVariable String directiveId) {
        AuthorDirective directive = require(id, directiveId);
        if (!directive.dismissible()) {
            throw new IllegalStateException("硬性要求或已确认的意见不能撤销；请走显式裁决流程");
        }
        directive.dismiss();
        repository.saveAuthorDirective(directive);
        return view(directive);
    }

    /** p37-08: 显式撤销——HARD 也需要 reason，CAS expectedRevision。 */
    @PostMapping("/{directiveId}/revoke")
    public Map<String, Object> revoke(@PathVariable String id, @PathVariable String directiveId,
                                      @RequestBody Map<String, Object> body) {
        String reason = String.valueOf(body.getOrDefault("reason", "")).trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("撤销必须说明理由");
        Integer expectedRevision = intOrNull(body.get("expectedRevision"));
        if (expectedRevision == null) throw new IllegalArgumentException("缺少 expectedRevision");
        repository.revokeDirective(id, directiveId, reason, expectedRevision);
        return view(repository.findAuthorDirective(id, directiveId).orElseThrow());
    }

    private AuthorDirective require(String projectId, String directiveId) {
        return repository.findAuthorDirective(projectId, directiveId)
                .orElseThrow(() -> new IllegalArgumentException("意见不存在: " + directiveId));
    }

    private Map<String, Object> view(AuthorDirective d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("kind", d.getKind().name());
        m.put("chapterIndex", d.getChapterIndex());
        m.put("content", d.getContent());
        m.put("status", d.getStatus().name());
        m.put("systemEvidence", d.getSystemEvidence());
        m.put("systemEvidenceVersion", d.getSystemEvidenceVersion());
        m.put("createdAt", d.getCreatedAt().toString());
        if (d.getConfirmedAt() != null) m.put("confirmedAt", d.getConfirmedAt().toString());
        m.put("revision", d.getRevision() == null ? 1 : d.getRevision());
        m.put("fromChapter", d.getFromChapter());
        if (d.getToChapter() != null) m.put("toChapter", d.getToChapter());
        m.put("enabled", d.isEnabled());
        List<Map<String, Object>> evidence = repository.findDirectiveEvidence(d.getProjectId(), d.getId());
        m.put("evidence", evidence);
        return m;
    }

    private Integer intOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private int intOr(Object value, int def) {
        Integer v = intOrNull(value);
        return v != null ? v : def;
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
