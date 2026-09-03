package com.writing.novel.web;

import com.writing.novel.service.AgentRevisionService;
import com.writing.novel.service.AgentRevisionService.ApplyResult;
import com.writing.novel.service.AgentRevisionService.RevisionPreview;
import com.writing.novel.service.AgentRevisionService.RevisionRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/revisions")
public class RevisionController {

    private final AgentRevisionService revisions;

    public RevisionController(AgentRevisionService revisions) {
        this.revisions = revisions;
    }

    @PostMapping("/preview")
    public RevisionPreview preview(@PathVariable String id, @RequestBody RevisionRequest request) {
        return revisions.preview(id, request);
    }

    @PostMapping("/{previewId}/apply")
    public ApplyResult apply(@PathVariable String id, @PathVariable String previewId) {
        return revisions.apply(id, previewId);
    }
}
