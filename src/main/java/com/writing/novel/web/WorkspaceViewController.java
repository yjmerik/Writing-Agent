package com.writing.novel.web;

import com.writing.novel.service.WorkspaceViewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * NCS-005（方案 §15）: 统一工作台读取端点。
 * GET /api/projects/{id}/workspace-view → identity/contentStatus/run/review/availableActions。
 */
@RestController
@RequestMapping("/api/projects/{id}")
public class WorkspaceViewController {

    private final WorkspaceViewService service;

    public WorkspaceViewController(WorkspaceViewService service) {
        this.service = service;
    }

    @GetMapping("/workspace-view")
    public Map<String, Object> workspaceView(@PathVariable String id) {
        return service.view(id);
    }
}
