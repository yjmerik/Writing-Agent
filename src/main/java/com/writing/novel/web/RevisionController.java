package com.writing.novel.web;

import com.writing.novel.service.AgentRevisionService;
import com.writing.novel.service.AgentRevisionService.ApplyResult;
import com.writing.novel.service.AgentRevisionService.RevisionPreview;
import com.writing.novel.service.AgentRevisionService.RevisionRequest;
import com.writing.novel.domain.RevisionProposal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{id}/revisions")
public class RevisionController {

    private final AgentRevisionService revisions;
    private final com.writing.novel.service.LongTaskService longTasks;

    public RevisionController(AgentRevisionService revisions,
                              com.writing.novel.service.LongTaskService longTasks) {
        this.revisions = revisions;
        this.longTasks = longTasks;
    }

    @PostMapping("/preview")
    public RevisionPreview preview(@PathVariable String id, @RequestBody RevisionRequest request) {
        return revisions.preview(id, request);
    }

    /** NCS-094: 预览异步化——立即返回 jobId，前端轮询 long-tasks/{jobId}。 */
    @PostMapping("/preview-async")
    public Map<String, Object> previewAsync(@PathVariable String id, @RequestBody RevisionRequest request) {
        String jobId = longTasks.submit(id, "agent-revision-preview", "修订候选生成",
                () -> revisions.preview(id, request));
        return Map.of("jobId", jobId, "kind", "agent-revision-preview");
    }

    /** NCS-094: 长任务状态查询（项目隔离；不存在/不匹配返回 404）。 */
    @GetMapping("/long-tasks/{jobId}")
    public com.writing.novel.service.LongTaskService.JobState longTask(@PathVariable String id,
                                                                       @PathVariable String jobId) {
        return longTasks.get(id, jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在或已过期: " + jobId));
    }

    @PostMapping("/{previewId}/apply")
    public ApplyResult apply(@PathVariable String id, @PathVariable String previewId) {
        return revisions.apply(id, previewId);
    }

    /** p37-03: 持久候选列表——重启/恢复查询入口。 */
    @GetMapping
    public List<RevisionProposal> list(@PathVariable String id,
                                       @RequestParam(required = false) String targetId,
                                       @RequestParam(required = false) String status) {
        return revisions.listProposals(id, targetId, status);
    }

    /** p37-03: 持久候选详情。 */
    @GetMapping("/{previewId}")
    public RevisionProposal detail(@PathVariable String id, @PathVariable String previewId) {
        return revisions.findProposal(id, previewId)
                .orElseThrow(() -> new IllegalArgumentException("修订候选不存在"));
    }

    /** p37-03: 取消持久候选（CAS READY→CANCELLED）。 */
    @PostMapping("/{previewId}/cancel")
    public RevisionProposal cancel(@PathVariable String id, @PathVariable String previewId) {
        return revisions.cancel(id, previewId);
    }
}
