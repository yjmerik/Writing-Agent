package com.writing.novel.web;

import com.writing.novel.service.CharacterAsOfService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * NCS-010（方案 §07）：人物时点读取 API。
 * GET /api/projects/{id}/characters/{charId}/as-of?chapter=N
 */
@RestController
@RequestMapping("/api/projects/{id}/characters/{charId}")
public class CharacterAsOfController {

    private final CharacterAsOfService service;

    public CharacterAsOfController(CharacterAsOfService service) {
        this.service = service;
    }

    @GetMapping("/as-of")
    public Map<String, Object> asOf(@PathVariable String id,
                                    @PathVariable String charId,
                                    @RequestParam("chapter") int chapter) {
        if (chapter < 0) {
            throw new IllegalArgumentException("chapter 必须是非负章号");
        }
        return service.asOf(id, charId, chapter);
    }
}
