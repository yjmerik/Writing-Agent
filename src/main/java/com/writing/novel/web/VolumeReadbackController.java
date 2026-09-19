package com.writing.novel.web;

import com.writing.novel.service.VolumeReadbackService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-020（方案 §06/J08）：卷末回读 API——生成/列表/确认。
 */
@RestController
@RequestMapping("/api/projects/{id}/volume-readbacks")
public class VolumeReadbackController {

    private final NovelRepository repository;
    private final VolumeReadbackService service;
    private final com.writing.novel.service.ReplanFromTextService replanFromText;

    public VolumeReadbackController(NovelRepository repository, VolumeReadbackService service,
                                    com.writing.novel.service.ReplanFromTextService replanFromText) {
        this.repository = repository;
        this.service = service;
        this.replanFromText = replanFromText;
    }

    @PostMapping("/generate")
    public Map<String, Object> generate(@PathVariable String id, @RequestParam int volume) {
        requireProject(id);
        var result = service.generate(id, volume);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("volumeIndex", result.volumeIndex());
        m.put("generated", result.generated());
        return m;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable String id) {
        requireProject(id);
        List<Map<String, Object>> items = new ArrayList<>();
        service.list(id).forEach(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("volumeIndex", r.getVolumeIndex());
            m.put("report", r.getReportJson());
            m.put("status", r.getStatus());
            m.put("acknowledgedAt", r.getAcknowledgedAt() == null ? null : r.getAcknowledgedAt().toString());
            items.add(m);
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", id);
        m.put("readbacks", items);
        return m;
    }

    @PostMapping("/{volumeIndex}/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable String id, @PathVariable int volumeIndex) {
        requireProject(id);
        var r = service.acknowledge(id, volumeIndex);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("volumeIndex", r.getVolumeIndex());
        m.put("status", r.getStatus());
        return m;
    }

    /** NCS-021: 按正文更新计划（回读确认后触发未写部分再规划） */
    @PostMapping("/{volumeIndex}/replan-from-text")
    public Map<String, Object> replanFromText(@PathVariable String id, @PathVariable int volumeIndex) {
        requireProject(id);
        var result = replanFromText.replanFromText(id, volumeIndex);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("volumeIndex", result.volumeIndex());
        m.put("replanned", result.replanned());
        return m;
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }
}
