package com.writing.novel.web;

import com.writing.novel.service.RunDetailsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** p40-02: 运行详情端点。 */
@RestController
@RequestMapping("/api/projects/{id}")
public class RunDetailsController {

    private final RunDetailsService runDetailsService;

    public RunDetailsController(RunDetailsService runDetailsService) {
        this.runDetailsService = runDetailsService;
    }

    @GetMapping("/run-details")
    public Map<String, Object> runDetails(@PathVariable String id) {
        return runDetailsService.runDetails(id);
    }
}
