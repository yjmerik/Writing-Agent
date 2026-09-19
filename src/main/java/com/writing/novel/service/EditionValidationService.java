package com.writing.novel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.*;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.util.ReviewFingerprint;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** 固定整书版本的确定性证据核对；不把已有模型检查解释为文学质量保证。 */
@Service
public class EditionValidationService {
    private final NovelRepository repository;
    private final ObjectMapper mapper;
    public EditionValidationService(NovelRepository repository, ObjectMapper mapper) {
        this.repository = repository; this.mapper = mapper.copy()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public Map<String, Object> validate(String projectId, String editionId) {
        return repository.withEditionTransaction(repo -> {
            BookEdition edition = edition(repo, projectId, editionId);
            Map<String, Object> report = evaluate(repo, edition);
            report.put("evidenceHash", digest(report));
            report.put("reportId", UUID.randomUUID().toString());
            report.put("createdAt", Instant.now().toString());
            repo.saveEditionValidation(projectId, editionId, edition.getManifestHash(),
                    (String) report.get("reportId"), json(report));
            return decorate(repo, edition, report);
        });
    }

    public Map<String, Object> current(String projectId, String editionId) {
        return repository.withEditionTransaction(repo -> {
            BookEdition edition = edition(repo, projectId, editionId);
            return decorate(repo, edition, stored(repo, edition));
        });
    }

    public void approve(String projectId, String editionId, String manifestHash) {
        repository.withEditionTransaction(repo -> {
            BookEdition edition = edition(repo, projectId, editionId);
            if (!edition.getManifestHash().equals(manifestHash)) throw new IllegalStateException("整书记录不匹配");
            Map<String, Object> report = stored(repo, edition);
            if (!"PASS".equals(report.get("status"))) throw new IllegalStateException("请先校验此整书记录并处理全部阻断项");
            if (!Objects.equals(report.get("evidenceHash"), digest(evaluate(repo, edition)))) {
                throw new IllegalStateException("检查证据已变化，请重新校验此整书记录");
            }
            repo.approveBookEdition(projectId, editionId, manifestHash);
            return null;
        });
    }

    private BookEdition edition(NovelRepository repo, String projectId, String editionId) {
        return repo.findBookEdition(projectId, editionId).orElseThrow(() -> new IllegalArgumentException("整书记录不存在"));
    }
    private Map<String, Object> stored(NovelRepository repo, BookEdition edition) {
        return repo.findEditionValidation(edition.getProjectId(), edition.getEditionId(), edition.getManifestHash())
                .map(this::parse).orElseGet(() -> new LinkedHashMap<>(Map.of("status", "NOT_RUN",
                        "editionId", edition.getEditionId(), "manifestHash", edition.getManifestHash())));
    }
    private Map<String, Object> decorate(NovelRepository repo, BookEdition edition, Map<String, Object> report) {
        boolean changed = false;
        var snapshots = repo.findBookEditionChapters(edition.getEditionId());
        var current = repo.findChaptersByProject(edition.getProjectId());
        Map<Integer, Chapter> byIndex = new HashMap<>();
        current.forEach(c -> byIndex.put(c.getChapterIndex(), c));
        if (current.size() != snapshots.size()) changed = true;
        for (var c : snapshots) {
            Chapter now = byIndex.get(c.chapterIndex());
            if (now == null || now.getVersion() != c.version() || !c.contentHash().equals(
                    ChapterApproval.contentHash(now.getTitle(), now.getContent()))) changed = true;
        }
        report.put("stale", report.containsKey("evidenceHash")
                && !Objects.equals(report.get("evidenceHash"), digest(evaluate(repo, edition))));
        report.put("workingCopyChanged", changed);
        return report;
    }

    private Map<String, Object> evaluate(NovelRepository repo, BookEdition edition) {
        String id = edition.getProjectId();
        List<Map<String, Object>> coverage = new ArrayList<>(), mustFix = new ArrayList<>(),
                unknown = new ArrayList<>(), exceptions = new ArrayList<>(), promises = new ArrayList<>(),
                suggestions = new ArrayList<>();
        var chapters = repo.findBookEditionChapters(edition.getEditionId());
        Map<Integer, BookEdition.EditionChapter> byIndex = new TreeMap<>();
        Map<String, ReviewDisposition> dispositions = new HashMap<>();
        int expectedIndex = 1;
        for (var c : chapters) {
            byIndex.put(c.chapterIndex(), c);
            if (c.chapterIndex() != expectedIndex) mustFix.add(item("COVERAGE_GAP", c.chapterIndex(), "章节覆盖不连续"));
            expectedIndex = c.chapterIndex() + 1;
            if (c.content() == null || c.content().isBlank()) mustFix.add(item("EMPTY_CHAPTER", c.chapterIndex(), "正文为空"));
            boolean approved = repo.isChapterApproved(id, c.chapterIndex(), c.version(), c.contentHash());
            if (!approved) mustFix.add(item("AUTHOR_APPROVAL", c.chapterIndex(), "此章稿尚未获作者批准"));
            var checks = repo.findEditionChecks(id, c.chapterIndex(), c.content());
            boolean continuity = false;
            for (var check : checks) {
                boolean valid = check.bound() && ("VALID_EMPTY".equals(check.status()) || "SUCCESS".equals(check.status()));
                if (check.checker().equals("continuity")) continuity = true;
                if (!valid) unknown.add(item("CHECK", c.chapterIndex(), check.checker() + "：" +
                        (check.bound() ? check.reason() : "旧检查未绑定正文，需重新检查")));
            }
            if (!continuity) unknown.add(item("MISSING_CHECK", c.chapterIndex(), "所选章稿尚无连续性检查证据"));
            coverage.add(Map.of("chapterIndex", c.chapterIndex(), "version", c.version(), "contentHash", c.contentHash(),
                    "authorApproved", approved, "checks", checks));
            repo.findDispositions(id, c.chapterIndex(), c.version(), c.contentHash()).stream()
                    .sorted(Comparator.comparing(ReviewDisposition::getCreatedAt).thenComparing(ReviewDisposition::getId))
                    .forEach(d -> dispositions.put(d.getIssueId(), d));
        }
        if (chapters.isEmpty()) mustFix.add(item("EMPTY_EDITION", 0, "整书记录没有章节"));
        // 不使用近期窗口或最多 200 条的 UI 查询；所有未解决问题逐一匹配快照。
        repo.findUnresolvedIssues(id).stream().sorted(Comparator.comparing(ContinuityIssue::getId)).forEach(issue -> {
            var c = byIndex.get(issue.getDetectedAtChapter());
            if (c == null) return;
            if (issue.getChapterVersion() != null && (issue.getChapterVersion() != c.version()
                    || (issue.getContentHash() != null && !issue.getContentHash().equals(ReviewFingerprint.sha256Hex12(c.content()))))) return;
            var card = item("ISSUE", c.chapterIndex(), issue.getDescription());
            card.put("issueId", issue.getId()); card.put("severity", issue.getSeverity().name());
            ReviewDisposition d = dispositions.get(issue.getId());
            if (d != null && d.getAction() != ReviewDisposition.Action.DEFER) {
                card.put("action", d.getAction().name()); card.put("reason", d.getReason());
                card.put("dispositionId", d.getId()); card.put("version", c.version()); exceptions.add(card);
            } else if (issue.getSeverity() == ContinuityIssue.Severity.LOW) suggestions.add(card);
            else mustFix.add(card);
        });
        repo.findActiveReviewBarriers(id).stream().sorted(Comparator.comparing(ReviewBarrier::getId)).forEach(b -> {
            var c = byIndex.get(b.getChapterIndex());
            if (c != null && c.version() == b.getChapterVersion()) {
                var card = item("REVIEW_BARRIER", b.getChapterIndex(), b.getReason());
                card.put("barrierId", b.getId()); mustFix.add(card);
            }
        });
        Map<String, PlanChange.PromiseDecision> decisions = new HashMap<>();
        repo.findPromiseDecisions(id).forEach(d -> decisions.put(d.promiseId(), d));
        int lastChapter = byIndex.isEmpty() ? 0 : Collections.max(byIndex.keySet());
        repo.findForeshadowingsByProject(id).stream().sorted(Comparator.comparing(Foreshadowing::getId)).forEach(f -> {
            if (!byIndex.containsKey(f.getPlantedAtChapter())) return;
            var card = item("PROMISE", f.getPlantedAtChapter(), f.getDescription()); card.put("promiseId", f.getId());
            var decision = decisions.get(f.getId());
            boolean resolved = f.isResolved() && f.getResolvedAtChapter() != null && f.getResolvedAtChapter() <= lastChapter
                    && f.getResolveEvidence() != null && !f.getResolveEvidence().isBlank();
            boolean open = decision != null && "OPEN_END".equals(decision.action())
                    && decision.reason() != null && !decision.reason().isBlank();
            card.put("state", resolved ? "RESOLVED" : open ? "OPEN_END" : "UNRESOLVED");
            card.put("reason", resolved ? f.getResolveEvidence() : open ? decision.reason() : "承诺尚无兑现或有意开放的决定");
            if (decision != null) card.put("decision", decision);
            promises.add(card);
            if (!resolved && !open) mustFix.add(card);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("editionId", edition.getEditionId()); result.put("manifestHash", edition.getManifestHash());
        result.put("scope", "覆盖所选记录全部章稿的身份、作者批准、连续性检查证据、未决问题与承诺；不代表文学质量保证");
        result.put("coverage", coverage); result.put("mustFix", mustFix); result.put("unknown", unknown);
        result.put("exceptions", exceptions); result.put("promises", promises); result.put("suggestions", suggestions);
        result.put("status", mustFix.isEmpty() && unknown.isEmpty() ? "PASS" : "BLOCKED");
        return result;
    }
    private Map<String, Object> item(String kind, int chapterIndex, String description) {
        Map<String, Object> item = new LinkedHashMap<>(); item.put("kind", kind); item.put("chapterIndex", chapterIndex);
        item.put("description", description == null ? "" : description); return item;
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("报告序列化失败", e); }
    }
    private String digest(Object value) { return RevisionProposal.canonicalJsonSha256(json(value)); }
    private Map<String, Object> parse(String json) {
        try { return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>(){}); }
        catch (Exception e) { throw new IllegalStateException("报告不可读取，请重新校验", e); }
    }
}
