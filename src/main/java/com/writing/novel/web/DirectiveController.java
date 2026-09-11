package com.writing.novel.web;

import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * p33-06（设计 5.3）：作者意见闭环 API。
 * 意见登记（OPEN）→ 系统落实证据（APPLIED_SYSTEM）→ 作者确认（CONFIRMED）；
 * 软性意见可撤销（DISMISSED，留痕）；HARD 意见不可被自动流程关闭。
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
                : Integer.valueOf(String.valueOf(body.get("chapterIndex")));
        AuthorDirective directive = new AuthorDirective(id,
                AuthorDirective.Kind.valueOf(kind), chapterIndex, content);
        repository.saveAuthorDirective(directive);
        return view(directive);
    }

    /** pendingOnly=true：仅 OPEN/APPLIED_SYSTEM（待作者确认或待处理）。 */
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
                : Integer.valueOf(String.valueOf(body.get("chapterVersion")));
        directive.applySystemEvidence(evidence, chapterVersion);
        repository.saveAuthorDirective(directive);
        return view(directive);
    }

    @PostMapping("/{directiveId}/confirm")
    public Map<String, Object> confirm(@PathVariable String id, @PathVariable String directiveId) {
        AuthorDirective directive = require(id, directiveId);
        directive.confirm();
        repository.saveAuthorDirective(directive);
        return view(directive);
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
        return m;
    }
}
