package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

final class RunTaskSupport {
    private RunTaskSupport() {}

    static void runAndAwait(MockMvc mvc, ObjectMapper om, String projectId) throws Exception {
        String json = mvc.perform(post("/api/projects/" + projectId + "/run"))
                .andReturn().getResponse().getContentAsString();
        var tree = om.readTree(json);
        if (!tree.has("taskId") || tree.get("taskId").isNull()) {
            return;
        }
        String taskId = tree.get("taskId").asText();
        for (int i = 0; i < 80; i++) {
            String body = mvc.perform(get("/api/projects/" + projectId + "/writing-tasks/" + taskId))
                    .andReturn().getResponse().getContentAsString();
            String status = om.readTree(body).path("status").asText("");
            if (!status.isBlank() && !"RUNNING".equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
