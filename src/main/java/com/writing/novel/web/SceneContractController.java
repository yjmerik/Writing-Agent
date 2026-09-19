package com.writing.novel.web;

import com.writing.novel.service.SceneContractService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NCS-018（方案 §06）：场景合同 API——按需生成/读取指定章的场景因果合同。
 */
@RestController
@RequestMapping("/api/projects/{id}/outline")
public class SceneContractController {

    private final NovelRepository repository;
    private final SceneContractService service;

    public SceneContractController(NovelRepository repository, SceneContractService service) {
        this.repository = repository;
        this.service = service;
    }

    @PostMapping("/scene-contract")
    public Map<String, Object> generate(@PathVariable String id, @RequestParam int chapter) {
        requireProject(id);
        var result = service.generateForChapter(id, chapter);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chapterIndex", result.chapterIndex());
        m.put("generated", result.generated());
        m.put("contract", service.contractOf(id, chapter));
        return m;
    }

    @GetMapping("/scene-contract")
    public Map<String, Object> get(@PathVariable String id, @RequestParam int chapter) {
        requireProject(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chapterIndex", chapter);
        m.put("contract", service.contractOf(id, chapter));
        return m;
    }

    /** 手动写入/修改合同（作者编辑通道；body 为合同 JSON 或空串清除） */
    @PutMapping("/scene-contract")
    public Map<String, Object> put(@PathVariable String id, @RequestParam int chapter,
                                   @RequestBody Map<String, Object> body) {
        requireProject(id);
        service.saveContract(id, chapter, String.valueOf(body.getOrDefault("contract", "")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chapterIndex", chapter);
        m.put("contract", service.contractOf(id, chapter));
        return m;
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }
}
