package com.writing.novel.memory;

import com.writing.novel.domain.Summary;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryServiceTest {

    @TempDir
    Path tempDir;
    private SummaryService service;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("sum.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        service = new SummaryService(new SqliteNovelRepository(cp));
    }

    @Test
    void 保存并查询摘要() {
        service.save(new Summary("p1", 1, 5, "第1-5章摘要"));
        service.save(new Summary("p1", 6, 10, "第6-10章摘要"));

        List<Summary> all = service.findAll("p1");
        assertEquals(2, all.size());
    }

    @Test
    void 获取覆盖指定章节的摘要() {
        service.save(new Summary("p1", 1, 5, "前期"));
        service.save(new Summary("p1", 6, 10, "中期"));

        List<Summary> covering = service.findCovering("p1", 7);
        assertEquals(1, covering.size());
        assertEquals("中期", covering.get(0).getContent());
    }

    @Test
    void 获取最近一段章节的摘要() {
        service.save(new Summary("p1", 1, 5, "前期"));
        service.save(new Summary("p1", 6, 10, "中期"));
        service.save(new Summary("p1", 11, 15, "后期"));

        List<Summary> recent = service.findRecent("p1", 15, 5);
        assertFalse(recent.isEmpty());
        assertTrue(recent.stream().anyMatch(s -> s.getContent().equals("后期")));
    }
}
