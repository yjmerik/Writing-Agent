package com.writing.novel.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p32-14（UI 验证发现）：/api/** 响应必须禁止浏览器缓存——
 * 启发式缓存曾使普通刷新后章节卡显示旧版本（v3/4981 vs 实际 v4/5008）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-api-cache.db",
        "persistence.vector-store.index-path=./data/test-api-cache-vecs.idx"
})
class ApiCacheControlTest {

    @Autowired MockMvc mvc;

    @Test
    void api列表响应携带no_store() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void 静态页面不受api缓存策略影响() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    /** NCS-177: SPA 壳 no-store（升级后浏览器首屏拿新 bundle）；带指纹 assets 长缓存 */
    @org.junit.jupiter.api.Test
    void indexHtml携带noStore_带指纹assets长缓存() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        var assetsDir = new java.io.File("src/main/resources/static/assets");
        var files = assetsDir.listFiles();
        org.junit.jupiter.api.Assumptions.assumeTrue(files != null && files.length > 0,
                "前端未构建时跳过 assets 断言（init.sh 构建后生效）");
        mvc.perform(get("/assets/" + files[0].getName()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age")));
    }
}
