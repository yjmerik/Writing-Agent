package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.service.CanonPatchService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * p21-04: 章节导航分页接口——元数据与正文分离。
 *
 * - GET /chapters?page&size 只返回元数据（无正文）+ total/page/size
 * - GET /chapters/{index} 返回单章正文
 * - 400 章种子下，分页响应绝不序列化未选中章节的正文
 */
class ChapterNavigationApiTest {

    private static final int TOTAL = 400;
    private NovelRepository repo;
    private ContentController controller;
    private final String pid = "proj-nav";

    @BeforeEach
    void setUp() {
        repo = mock(NovelRepository.class);
        controller = new ContentController(repo, mock(NovelOrchestrator.class),
                mock(CanonPatchService.class), mock(WritingTaskExecutor.class));

        List<Chapter> metas = new ArrayList<>();
        for (int i = 1; i <= TOTAL; i++) {
            // findChapterMetaByProject 语义：只有元数据，正文为空
            Chapter m = new Chapter(pid, i, "第" + i + "章 标题");
            metas.add(m);
        }
        when(repo.findChapterMetaByProject(pid)).thenReturn(metas);
        Chapter full = new Chapter(pid, 5, "第5章 标题");
        full.setContent("这是第五章的完整正文。".repeat(200));
        when(repo.findChapterByIndex(pid, 5)).thenReturn(Optional.of(full));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 分页接口只返回元数据且带总数() throws Exception {
        Map<String, Object> resp = controller.chapters(pid, 2, 50);
        assertEquals(TOTAL, resp.get("total"));
        assertEquals(2, resp.get("page"));
        assertEquals(50, resp.get("size"));
        List<Chapter> chapters = (List<Chapter>) resp.get("chapters");
        assertEquals(50, chapters.size());
        assertEquals(101, chapters.get(0).getChapterIndex(), "第 3 页从第 101 章开始");
        assertEquals(150, chapters.get(49).getChapterIndex());
        for (Chapter c : chapters) {
            assertEquals("", c.getContent(), "元数据条目不得携带正文");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 序列化后未选中章节正文不出现() throws Exception {
        Map<String, Object> resp = controller.chapters(pid, 0, 50);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(resp);
        assertFalse(json.contains("这是第五章的完整正文"), "分页响应不得包含任何章节正文");
        assertTrue(json.contains("\"total\":" + TOTAL));
        // 正文只经单章接口返回
        Chapter one = controller.chapter(pid, 5);
        assertTrue(one.getContent().contains("这是第五章的完整正文"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 越界页返回空列表与正确总数() {
        Map<String, Object> resp = controller.chapters(pid, 9, 50);
        assertEquals(TOTAL, resp.get("total"));
        assertTrue(((List<Chapter>) resp.get("chapters")).isEmpty(), "越界页应为空列表而非异常");
    }
}
