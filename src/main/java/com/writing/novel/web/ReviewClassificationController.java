package com.writing.novel.web;

import com.writing.novel.service.ReviewClassificationService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * NCS-023（方案 §13/UX09）：三类信息分层读取——必须处理 / 尚未查清 / 文学建议。
 */
@RestController
@RequestMapping("/api/projects/{id}/review-classification")
public class ReviewClassificationController {

    private final NovelRepository repository;
    private final ReviewClassificationService service;

    public ReviewClassificationController(NovelRepository repository, ReviewClassificationService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> classify(@PathVariable String id) {
        if (repository.findProject(id).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + id);
        }
        return service.classify(id);
    }
}
