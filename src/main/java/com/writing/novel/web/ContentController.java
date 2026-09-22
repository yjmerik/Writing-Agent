package com.writing.novel.web;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectActionPolicy;
import com.writing.novel.orchestrator.ProjectActionPolicy.ProjectAction;
import com.writing.novel.service.CanonPatchService;
import com.writing.novel.service.ChapterRevisionService;
import com.writing.novel.service.OutlineStructureService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{id}")
public class ContentController {

    private final NovelRepository repository;
    private final NovelOrchestrator orchestrator;
    private final CanonPatchService canonPatch;
    private final OutlineStructureService outlineStructure;
    private final com.writing.novel.service.PlanEditionService planEdition;
    /** NCS-013: 统一修订请求（设定变更入口登记） */
    private final com.writing.novel.service.RevisionRequestService revisionRequests;
    private final WritingTaskExecutor writingTaskExecutor;
    private final ChapterRevisionService chapterRevision;
    private final ProjectActionPolicy actionPolicy = new ProjectActionPolicy();

    @Autowired
    public ContentController(NovelRepository repository, NovelOrchestrator orchestrator,
                             CanonPatchService canonPatch, WritingTaskExecutor writingTaskExecutor,
                             OutlineStructureService outlineStructure,
                             com.writing.novel.service.PlanEditionService planEdition,
                             com.writing.novel.service.SupportWithdrawalService supportWithdrawal,
                             com.writing.novel.service.RevisionRequestService revisionRequests) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.canonPatch = canonPatch;
        this.writingTaskExecutor = writingTaskExecutor;
        this.outlineStructure = outlineStructure;
        this.planEdition = planEdition;
        this.chapterRevision = new ChapterRevisionService(repository, writingTaskExecutor,
                orchestrator == null ? null : orchestrator::refreshChapterAfterRevisionAsync,
                supportWithdrawal, revisionRequests);
        this.revisionRequests = revisionRequests;
    }

    /** Unit-test compatibility for controller tests that do not exercise outline editing. */
    public ContentController(NovelRepository repository, NovelOrchestrator orchestrator,
                             CanonPatchService canonPatch, WritingTaskExecutor writingTaskExecutor) {
        this(repository, orchestrator, canonPatch, writingTaskExecutor,
                canonPatch == null ? null : new OutlineStructureService(
                        repository, canonPatch, writingTaskExecutor),
                new com.writing.novel.service.PlanEditionService(repository), null, null);
    }

    @GetMapping("/worldbuilding")
    public List<KnowledgeEntry> worldbuilding(@PathVariable String id) {
        return repository.findKnowledgeEntriesByProject(id);
    }

    @PostMapping("/worldbuilding")
    public KnowledgeEntry createWorld(@PathVariable String id, @RequestBody Map<String, String> body) {
        return withChapterWork(id, () -> {
            KnowledgeEntry entry = new KnowledgeEntry(id,
                    body.getOrDefault("type", "CUSTOM"),
                    body.getOrDefault("name", ""),
                    body.getOrDefault("content", ""));
            return (KnowledgeEntry) canonPatch.createWorld(id, entry, chapterOf(id)).entity();
        });
    }

    @GetMapping("/characters")
    public List<Character> characters(@PathVariable String id) {
        return repository.findCharactersByProject(id);
    }

    @PostMapping("/characters")
    public Character createCharacter(@PathVariable String id, @RequestBody Map<String, String> body) {
        return withChapterWork(id, () -> {
            Character created = new Character(id, body.get("name"), body.getOrDefault("role", "配角"));
            if (body.get("personality") != null) created.setPersonality(body.get("personality"));
            if (body.get("ability") != null) created.setAbility(body.get("ability"));
            if (body.get("motivation") != null) created.setMotivation(body.get("motivation"));
            if (body.get("appearance") != null) created.setAppearance(body.get("appearance"));
            if (body.get("relationships") != null) created.setRelationships(body.get("relationships"));
            if (body.get("growthArc") != null) created.setGrowthArc(body.get("growthArc"));
            return (Character) canonPatch.createCharacter(id, created, chapterOf(id)).entity();
        });
    }

    @PutMapping("/characters/{charId}")
    public Object updateCharacter(@PathVariable String id, @PathVariable String charId,
                                  @RequestBody Map<String, Object> body) {
        // p39-04（审计 P1-2）：confirm=false 仅返回影响预览与凭证，绝不写库
        if (!(body.get("confirm") instanceof Boolean explicitConfirm)) {
            throw new IllegalArgumentException("confirm 必须明确为布尔值，请先预览再确认");
        }
        if (!explicitConfirm) {
            Character current = repository.findCharacter(charId)
                    .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + charId));
            if (!id.equals(current.getProjectId())) {
                throw new IllegalArgumentException("人物不属于该项目");
            }
            return java.util.Map.of(
                    "persisted", false,
                    "previewToken", characterPreviewToken(current),
                    "impact", characterImpact(id, current, body));
        }
        return withChapterWork(id, () -> {
            Character patch = new Character(id, str(body.get("name")), str(body.get("role")));
            if (body.get("personality") != null) patch.setPersonality(str(body.get("personality")));
            if (body.get("ability") != null) patch.setAbility(str(body.get("ability")));
            if (body.get("motivation") != null) patch.setMotivation(str(body.get("motivation")));
            if (body.get("appearance") != null) patch.setAppearance(str(body.get("appearance")));
            if (body.get("relationships") != null) patch.setRelationships(str(body.get("relationships")));
            if (body.get("growthArc") != null) patch.setGrowthArc(str(body.get("growthArc")));
            // p39-04: confirm=true 时必须携带与当前来源一致的 previewToken——来源已变化 409
            if (explicitConfirm) {
                Object tokenRaw = body.get("previewToken");
                if (tokenRaw == null || String.valueOf(tokenRaw).isBlank()) {
                    throw new IllegalArgumentException("confirm=true 缺少 previewToken（服务端不补填）");
                }
                Character current = repository.findCharacter(charId)
                        .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + charId));
                if (!characterPreviewToken(current).equals(String.valueOf(tokenRaw))) {
                    throw new IllegalStateException("人物设定已变化，预览凭证失效，请重新预览");
                }
            }
            Character saved = (Character) canonPatch.patchCharacter(id, charId, patch, chapterOf(id)).entity();
            // NCS-013: 设定变更登记为统一修订请求（作者确认即采纳，影响预览记录在案）
            if (revisionRequests != null) {
                revisionRequests.recordAndComplete(id, "SETTING_CHANGE",
                        "修改人物设定：" + saved.getName(),
                        "[{\"characterId\":\"" + charId + "\"}]", "{}", "[]",
                        "作者确认修改（预览凭证校验通过）");
            }
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("persisted", true);
            result.put("character", saved);
            result.put("previewToken", characterPreviewToken(saved));
            return result;
        });
    }

    /** p39-09（审计 P1-2）：设定影响预览端点——只读，返回影响清单与确认凭证。 */
    @PostMapping("/characters/{charId}/impact-preview")
    public Map<String, Object> characterImpactPreview(@PathVariable String id, @PathVariable String charId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        Character current = repository.findCharacter(charId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + charId));
        if (!id.equals(current.getProjectId())) {
            throw new IllegalArgumentException("人物不属于该项目");
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("persisted", false);
        result.put("previewToken", characterPreviewToken(current));
        result.put("impact", characterImpact(id, current, body == null ? Map.of() : body));
        return result;
    }

    /** 预览凭证：人物当前规范状态的 SHA-256（12 hex）。 */
    private String characterPreviewToken(Character c) {
        String canonical = String.join("\u0001",
                n(c.getName()), n(c.getRole()), n(c.getPersonality()), n(c.getAbility()),
                n(c.getMotivation()), n(c.getAppearance()), n(c.getRelationships()), n(c.getGrowthArc()));
        return com.writing.novel.util.ReviewFingerprint.sha256Hex12(canonical);
    }

    /** 影响分析：确定引用（正文含名字）/可能引用（标题或梗概含名字）/覆盖不足（无文本可查）。 */
    private Map<String, Object> characterImpact(String projectId, Character c, Map<String, Object> changes) {
        String name = n(c.getName());
        java.util.List<Integer> certain = new java.util.ArrayList<>();
        java.util.List<Integer> possible = new java.util.ArrayList<>();
        java.util.List<Integer> insufficient = new java.util.ArrayList<>();
        for (Chapter ch : repository.findChaptersByProject(projectId)) {
            String content = ch.getContent();
            if (content == null) {
                insufficient.add(ch.getChapterIndex());
            } else if (!name.isEmpty() && content.contains(name)) {
                certain.add(ch.getChapterIndex());
            } else {
                possible.add(ch.getChapterIndex());
            }
        }
        java.util.Map<String, Object> impact = new java.util.LinkedHashMap<>();
        impact.put("certainReferences", certain);
        impact.put("possibleReferences", possible);
        impact.put("insufficientCoverage", insufficient);
        impact.put("changes", changes);
        impact.put("autoRewriteHistory", false); // 不承诺自动修复历史正文
        return impact;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }

    @PutMapping("/worldbuilding/{entryId}")
    public KnowledgeEntry updateWorld(@PathVariable String id, @PathVariable String entryId,
                                      @RequestBody Map<String, String> body) {
        return withChapterWork(id, () -> {
            KnowledgeEntry patch = new KnowledgeEntry(id,
                    body.getOrDefault("type", "CUSTOM"),
                    body.getOrDefault("name", ""),
                    body.getOrDefault("content", ""));
            return (KnowledgeEntry) canonPatch.patchWorld(id, entryId, patch, chapterOf(id)).entity();
        });
    }

    @PutMapping("/outline")
    public Outline updateOutline(@PathVariable String id, @RequestBody Outline incoming) {
        rejectIfWriting(id);
        return outlineStructure.replaceUnwritten(id, incoming);
    }

    @GetMapping("/facts")
    public List<com.writing.novel.domain.StoryFact> facts(@PathVariable String id) {
        return canonPatch.listActive(id);
    }

    @PostMapping("/facts")
    public com.writing.novel.domain.StoryFact addFact(@PathVariable String id,
                                                      @RequestBody Map<String, String> body) {
        return withChapterWork(id, () -> {
            var type = com.writing.novel.domain.StoryFact.FactType.valueOf(
                    body.getOrDefault("factType", "PLOT_COMMITMENT"));
            return canonPatch.addFact(id, type, body.getOrDefault("subject", "补丁"),
                    body.getOrDefault("assertion", ""), chapterOf(id));
        });
    }

    @PutMapping("/facts/{factId}")
    public com.writing.novel.domain.StoryFact updateFact(@PathVariable String id,
                                                         @PathVariable String factId,
                                                         @RequestBody Map<String, String> body) {
        return withChapterWork(id, () -> {
            Integer importance = body.containsKey("importance") ? Integer.parseInt(body.get("importance")) : null;
            return (com.writing.novel.domain.StoryFact) canonPatch.patchFact(
                    id, factId, body.get("assertion"), importance, chapterOf(id)).entity();
        });
    }

    @PostMapping("/facts/{factId}/retire")
    public com.writing.novel.domain.StoryFact retireFact(@PathVariable String id, @PathVariable String factId) {
        return withChapterWork(id, () -> {
            return canonPatch.retireFact(id, factId);
        });
    }

    @GetMapping("/foreshadowings")
    public List<Foreshadowing> foreshadowings(@PathVariable String id) {
        return repository.findForeshadowingsByProject(id);
    }

    @PostMapping("/foreshadowings")
    public Foreshadowing createForeshadowing(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return withChapterWork(id, () -> {
            String content = String.valueOf(body.getOrDefault("content", body.getOrDefault("description", "")));
            Foreshadowing planted = new Foreshadowing(id, content, "HUMAN", chapterOf(id));
            planted.setMustResolve(Boolean.parseBoolean(String.valueOf(body.getOrDefault("mustResolve", "false"))));
            repository.saveForeshadowing(planted);
            return planted;
        });
    }

    @PostMapping("/foreshadowings/{fid}/resolve")
    public Foreshadowing resolveForeshadowing(@PathVariable String id, @PathVariable String fid) {
        return withChapterWork(id, () -> {
            Foreshadowing existing = repository.findForeshadowingsByProject(id).stream()
                    .filter(f -> fid.equals(f.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("伏笔不存在: " + fid));
            existing.resolve(chapterOf(id));
            repository.saveForeshadowing(existing);
            return existing;
        });
    }

    @PostMapping("/chapters/{chapterIndex}/resolve-review")
    public Chapter resolveManualReview(@PathVariable String id, @PathVariable int chapterIndex) {
        // p36-06: 无版本放行旁路关闭——批准必须携带已读稿件身份走版本化 review-decisions，
        // 旧窗口/旧端点不再能放行未读新稿。
        throw new IllegalStateException(
                "该入口已关闭：请刷新审阅，并使用版本化「批准当前稿」（review-decisions）放行");
    }

    @PostMapping("/replan")
    public Outline replan(@PathVariable String id) {
        return withChapterWork(id, () -> {
            return orchestrator.replanRemaining(id);
        });
    }

    @GetMapping("/outline")
    public Outline outline(@PathVariable String id) {
        return repository.findOutlineByProject(id).orElse(null);
    }

    /**
     * p21-04: 章节元数据分页——只返回标题/字数/索引等元数据（无正文）+ total/page/size。
     * 正文经 GET /chapters/{index} 单章懒加载，避免百章级项目全量序列化正文。
     */
    @GetMapping("/chapters")
    public Map<String, Object> chapters(@PathVariable String id,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.max(1, Math.min(size, 1000)); // 元数据行极小，单页可覆盖 400 章全书目录
        List<Chapter> all = repository.findChapterMetaByProject(id);
        int total = all.size();
        int from = Math.max(0, page * safeSize);
        List<Chapter> pageItems = from >= total
                ? List.of()
                : all.subList(from, Math.min(total, from + safeSize));
        return Map.of(
                "chapters", pageItems,
                "total", total,
                "page", Math.max(0, page),
                "size", safeSize
        );
    }

    /** p21-04: 单章正文懒加载 */
    @GetMapping("/chapters/{chapterIndex}")
    public Chapter chapter(@PathVariable String id, @PathVariable int chapterIndex) {
        return repository.findChapterByIndex(id, chapterIndex).orElse(null);
    }

    @PostMapping("/chapters")
    public Chapter createChapterDraft(@PathVariable String id,
                                      @RequestBody Map<String, Object> body) {
        return chapterRevision.createDraft(id,
                stringValue(body.get("title")), stringValue(body.get("content")));
    }

    @PutMapping("/chapters/{chapterIndex}")
    public Chapter editChapter(@PathVariable String id, @PathVariable int chapterIndex,
                               @RequestBody Map<String, Object> body) {
        return chapterRevision.edit(id, chapterIndex, intValue(body.get("expectedVersion")),
                stringValue(body.get("title")), stringValue(body.get("content")), "DIRECT_EDIT");
    }

    @GetMapping("/chapters/{chapterIndex}/versions")
    public List<ChapterVersion> chapterVersions(@PathVariable String id,
                                                @PathVariable int chapterIndex) {
        return chapterRevision.history(id, chapterIndex);
    }

    @PostMapping("/chapters/{chapterIndex}/restore/{version}")
    public Chapter restoreChapter(@PathVariable String id, @PathVariable int chapterIndex,
                                  @PathVariable int version,
                                  @RequestBody Map<String, Object> body) {
        return chapterRevision.restore(id, chapterIndex, version,
                intValue(body.get("expectedVersion")));
    }

    @PostMapping("/expand/characters")
    public Character expandCharacters(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return withChapterWork(id, () -> {
            String instruction = body != null ? body.getOrDefault("instruction", "") : "";
            return orchestrator.expandCharacters(id, instruction);
        });
    }

    @PostMapping("/expand/world")
    public List<KnowledgeEntry> expandWorld(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return withChapterWork(id, () -> {
            String instruction = body != null ? body.getOrDefault("instruction", "") : "";
            return orchestrator.expandWorld(id, instruction);
        });
    }

    @PostMapping("/characters/promote/{entityId}")
    public Character promoteEntity(@PathVariable String id, @PathVariable String entityId) {
        return withChapterWork(id, () -> {
            return orchestrator.promoteEntity(id, entityId);
        });
    }

    /** p15-05: 单章写作——登记 WRITE_CHAPTER 任务，正文落库后返回 Chapter；失败置 FAILED 并重抛 */
    @PostMapping("/write-chapter")
    public Chapter writeChapter(@PathVariable String id,
                                 @RequestBody(required = false) java.util.Map<String, String> body) {
        requireWriteChapterAllowed(id);
        // p18-03: 写前指令——作者对下一章的定向要求，透传本章 writingNotes
        String instruction = body != null ? body.get("instruction") : null;
        if (writingTaskExecutor == null) {
            return orchestrator.runWritingOneChapter(id, instruction);
        }
        WritingTask task = writingTaskExecutor.startWriteChapter(id,
                () -> requireWriteChapterAllowed(id));
        try {
            Chapter chapter = orchestrator.runWritingOneChapter(id, instruction);
            writingTaskExecutor.finishWriteChapter(id, task, WritingTask.Status.COMPLETED, null);
            return chapter;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "未知错误";
            writingTaskExecutor.finishWriteChapter(id, task, WritingTask.Status.FAILED, msg);
            throw e;
        }
    }

    private void requireWriteChapterAllowed(String projectId) {
        Project project = repository.findProject(projectId).orElseThrow();
        actionPolicy.requireAllowed(project, ProjectAction.WRITE_CHAPTER,
                repository.findOutlineByProject(projectId).isPresent(),
                !repository.findPendingCheckpoints(projectId).isEmpty());
    }

    /** p7-1: 重写指定章节（不限项目状态） */
    @PostMapping("/rewrite-chapter/{chapterIndex}")
    public Chapter rewriteChapter(@PathVariable String id, @PathVariable int chapterIndex,
                                   @RequestBody(required = false) java.util.Map<String, String> body) {
        String instructions = body != null ? body.get("instructions") : null;
        return withChapterWork(id, () -> orchestrator.rewriteChapter(id, chapterIndex, instructions));
    }

    /** p10.1-e1: 伏笔重要性回溯审计——手动触发 */
    @PostMapping("/audit-foreshadowings")
    public java.util.Map<String, Object> auditForeshadowings(@PathVariable String id) {
        int upgraded = orchestrator.auditForeshadowingImportance(id);
        return java.util.Map.of("upgraded", upgraded);
    }

    /** p35-05（设计 5.7 第一版）：成书审查覆盖清单——各章稿号/状态/评分与未回收承诺。 */
    @GetMapping("/book-review")
    public Map<String, Object> bookReview(@PathVariable String id) {
        Project project = repository.findProject(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        var prefs = repository.findWritingPreferences(id)
                .orElseGet(() -> new com.writing.novel.domain.WritingPreferences(id));
        var chapters = repository.findChaptersByProject(id).stream()
                .sorted(java.util.Comparator.comparingInt(com.writing.novel.domain.Chapter::getChapterIndex))
                .<Map<String, Object>>map(ch -> {
                    var report = orchestrator.buildChapterQualityReport(id, ch.getChapterIndex());
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("chapterIndex", ch.getChapterIndex());
                    m.put("title", ch.getTitle());
                    m.put("version", ch.getVersion());
                    m.put("status", ch.getStatus().name());
                    m.put("wordCount", ch.getWordCount());
                    m.put("grade", report.getGrade());
                    m.put("reportCurrent", report.isReportCurrent());
                    m.put("planRef", ch.getPlanRef());
                    // p36-11: DONE 不等于作者批准——批准按当前稿件身份精确匹配
                    m.put("approved", repository.isChapterApproved(id, ch.getChapterIndex(),
                            ch.getVersion(), com.writing.novel.domain.ChapterApproval.contentHash(
                                    ch.getTitle(), ch.getContent())));
                    return m;
                }).toList();
        long done = chapters.stream().filter(m -> "DONE".equals(m.get("status"))).count();
        int totalWords = repository.getTotalWordCount(id);
        var promises = repository.findUnresolvedForeshadowings(id).stream()
                .sorted(java.util.Comparator
                        .comparing(com.writing.novel.domain.Foreshadowing::isMustResolve).reversed()
                        .thenComparing(f -> f.getPriority() == com.writing.novel.domain.Foreshadowing.Priority.HIGH ? 0 : 1))
                .<Map<String, Object>>map(f -> Map.of(
                        "description", f.getDescription(),
                        "priority", f.getPriority().name(),
                        "plantedAtChapter", f.getPlantedAtChapter(),
                        "expectedResolveChapter", f.getExpectedResolveChapter() == null ? -1
                                : f.getExpectedResolveChapter(),
                        "mustResolve", f.isMustResolve()))
                .toList();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("projectId", id);
        result.put("title", project.getTitle());
        result.put("state", project.getState().name());
        result.put("targetChapters", prefs.getTargetChapters());
        result.put("chapters", chapters);
        result.put("summary", Map.of(
                "doneChapters", done,
                "totalChapters", chapters.size(),
                "totalWords", totalWords));
        result.put("unresolvedPromises", promises);
        return result;
    }

    /** p35-05：全书 Markdown 导出（正文 + 稿号；导出基于当前稿）。 */
    @GetMapping(value = "/book-export", produces = "text/markdown;charset=UTF-8")
    public String bookExport(@PathVariable String id) {
        Project project = repository.findProject(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        StringBuilder sb = new StringBuilder();
        sb.append("# 成书审查：").append(project.getTitle()).append("\n\n");
        sb.append("> 导出基于当前稿（未定稿标识）。各章标题行标注稿号。\n\n");
        repository.findChaptersByProject(id).stream()
                .sorted(java.util.Comparator.comparingInt(com.writing.novel.domain.Chapter::getChapterIndex))
                .forEach(ch -> {
                    sb.append("## 第").append(ch.getChapterIndex()).append("章 ")
                            .append(ch.getTitle()).append("（v").append(ch.getVersion()).append("）\n\n");
                    sb.append(ch.getContent() == null ? "" : ch.getContent()).append("\n\n");
                });
        return sb.toString();
    }

    /** p33-08（设计 5.6）：篇幅变更——先预览影响，confirm 才生效。 */
    /**
     * p38-03（审计 P1-3）：篇幅变更——预览返回凭证（previewId+sourcePlanHash），
     * 确认必须携带与预览一致的凭证、目标与逐项承诺处置；缺凭证 400、过期/不符 409。
     */
    @PostMapping("/plan/target-chapters")
    public Map<String, Object> changeTargetChapters(@PathVariable String id,
                                                    @RequestBody Map<String, Object> body) {
        int newTarget = Integer.parseInt(String.valueOf(body.get("targetChapters")));
        boolean confirm = Boolean.parseBoolean(String.valueOf(body.getOrDefault("confirm", "false")));
        if (!confirm) {
            return planEdition.previewTarget(id, newTarget);
        }
        String previewId = body.get("previewId") == null ? null : String.valueOf(body.get("previewId"));
        String sourcePlanHash = body.get("sourcePlanHash") == null ? null
                : String.valueOf(body.get("sourcePlanHash"));
        String requestKey = body.get("requestKey") == null ? null : String.valueOf(body.get("requestKey"));
        java.util.List<com.writing.novel.domain.PlanChange.PromiseDecision> decisions = new java.util.ArrayList<>();
        Object decisionsRaw = body.get("decisions");
        if (decisionsRaw instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    decisions.add(new com.writing.novel.domain.PlanChange.PromiseDecision(
                            String.valueOf(m.get("id")),
                            previewId,
                            String.valueOf(m.get("promiseId")),
                            String.valueOf(m.get("action")),
                            m.get("targetChapter") == null ? null
                                    : Integer.parseInt(String.valueOf(m.get("targetChapter"))),
                            m.get("reason") == null ? null : String.valueOf(m.get("reason"))));
                }
            }
        }
        return planEdition.confirmTarget(id, previewId, sourcePlanHash, newTarget, decisions, requestKey);
    }

    /**
     * p33-02（设计 7）：统一工作区状态——前端按钮可用性的单一来源。
     * collabState 由屏障/任务/项目状态推导；todoList 含章节稿号与原因；
     * allowedActions 由后端判定，前端不再自行猜测。
     */
    @GetMapping("/workspace")
    public Map<String, Object> workspace(@PathVariable String id) {
        Project project = repository.findProject(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        boolean busy = writingTaskExecutor.hasActiveTask(id);
        var barriers = repository.findActiveReviewBarriers(id);
        String blockedReason = busy ? "正在处理内容" : writingTaskExecutor.collaborationBlocker(id);

        // NCS-074: 匿名同步占坑（补查/批准/内容修改等）——workspace 暴露进行中操作与已用时
        var syncWork = writingTaskExecutor.activeSyncWork(id).orElse(null);

        String collabState;
        if (busy && repository.findActiveWritingTask(id)
                .map(t -> t.isStopRequested()).orElse(false)) {
            collabState = "HANDING_OVER";
        } else if (busy) {
            collabState = "WRITING";
        } else if (!barriers.isEmpty()) {
            collabState = "AWAITING_REVIEW";
        } else if (project.getState() == com.writing.novel.domain.NovelState.FINAL) {
            collabState = "FINISHED";
        } else if (blockedReason != null) {
            collabState = "BLOCKED";
        } else {
            collabState = "WRITING";
        }

        String primaryAction = switch (collabState) {
            case "AWAITING_REVIEW" -> "REVIEW_CHAPTER";
            case "FINISHED" -> "EXPORT";
            case "HANDING_OVER" -> "WAIT_HANDOVER";
            case "WRITING" -> busy ? "TAKE_OVER" : "WRITE_NEXT";
            default -> "RESOLVE_BLOCKED";
        };

        var todoList = barriers.stream().<Map<String, Object>>map(b -> Map.of(
                "chapterIndex", b.getChapterIndex(),
                "chapterVersion", b.getChapterVersion(),
                "kind", "MANUAL_REVIEW",
                "reason", b.getReason() == null ? "" : b.getReason(),
                "raisedAt", b.getCreatedAt().toString())).toList();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("projectId", id);
        result.put("stage", project.getState().name());
        result.put("busy", busy);
        result.put("collabState", collabState);
        result.put("primaryAction", primaryAction);
        result.put("canContinue", !busy && blockedReason == null);
        result.put("blockedReason", blockedReason);
        result.put("todoList", todoList);
        result.put("currentChapter", project.getCurrentChapterIndex());
        if (syncWork != null) {
            long elapsed = java.time.Duration.between(syncWork.startedAt(),
                    java.time.Instant.now()).getSeconds();
            result.put("activeWork", Map.of(
                    "kind", syncWork.kind(),
                    "label", syncWorkLabel(syncWork.kind()),
                    "workId", syncWork.workId(),
                    "startedAt", syncWork.startedAt().toString(),
                    "elapsedSeconds", elapsed));
        }
        return result;
    }

    /** NCS-074: 取消匿名同步占坑——立即释放项目工作权；原操作若最终完成仍按稿件身份落库。
     *  任务型占坑 released=false（走既有「终止任务」路径）。 */
    @PostMapping("/active-work/cancel")
    public Map<String, Object> cancelActiveWork(@PathVariable String id) {
        boolean released = writingTaskExecutor.cancelSyncWork(id);
        return Map.of("released", released);
    }

    /** NCS-074: 同步工作种类 → 作者可读标签 */
    private static String syncWorkLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "recheck" -> "连续性补查";
            case "review-decision" -> "批准处理";
            case "checkpoint-revision" -> "审阅修订";
            case "chapter-change" -> "内容修改";
            case "step" -> "创作推进";
            case "review-policy" -> "审核节奏调整";
            default -> "同步内容处理";
        };
    }

    /** p11-I2: 获取章节聚合质量报告 */
    @GetMapping("/chapters/{chapterIndex}/quality")
    public ChapterQualityReport chapterQuality(@PathVariable String id,
                                                 @PathVariable int chapterIndex) {
        return orchestrator.buildChapterQualityReport(id, chapterIndex);
    }

    /** p11-G1: 段落级编辑——局部改写/扩充/插入 */
    @PostMapping("/chapters/{chapterIndex}/passage")
    public Chapter editPassage(@PathVariable String id, @PathVariable int chapterIndex,
                                @RequestBody PassageEditRequest req) {
        return withChapterWork(id, () -> orchestrator.editPassage(id, chapterIndex,
                req.getParagraphIndex(), req.getSelectedText(),
                req.getInstruction(), req.getMode()));
    }

    @GetMapping("/memory/summary")
    public List<Summary> summaries(@PathVariable String id) {
        return repository.findSummariesByProject(id);
    }

    private void rejectIfWriting(String projectId) {
        if (writingTaskExecutor != null && writingTaskExecutor.hasActiveTask(projectId)) {
            throw new IllegalStateException("请先暂停写作再修改设定");
        }
    }

    private int chapterOf(String projectId) {
        return canonPatch.currentChapter(projectId);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) throw new IllegalArgumentException("expectedVersion 不能为空");
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expectedVersion 必须是整数");
        }
    }

    private <T> T withChapterWork(String projectId, java.util.function.Supplier<T> work) {
        if (writingTaskExecutor == null) return work.get();
        String workId = "chapter-change-" + java.util.UUID.randomUUID();
        writingTaskExecutor.beginWork(projectId, workId);
        try {
            return work.get();
        } finally {
            writingTaskExecutor.endWork(projectId, workId);
        }
    }
}
