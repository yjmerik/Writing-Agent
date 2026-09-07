package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WritingTaskTest {

    @Test
    void 三参数构造设置任务类型() {
        WritingTask t = new WritingTask("p1", 10, WritingTask.Type.CONCEIVE);
        assertEquals(WritingTask.Type.CONCEIVE, t.getType());
        assertEquals("p1", t.getProjectId());
        assertEquals(10, t.getTotalChapters());
    }

    @Test
    void 两参数构造默认WRITE_ALL() {
        WritingTask t = new WritingTask("p1", 10);
        assertEquals(WritingTask.Type.WRITE_ALL, t.getType());
    }

    @Test
    void Type枚举包含持续协作与原有任务类型() {
        assertEquals(4, WritingTask.Type.values().length);
        assertNotNull(WritingTask.Type.valueOf("CONCEIVE"));
        assertNotNull(WritingTask.Type.valueOf("WRITE_CHAPTER"));
        assertNotNull(WritingTask.Type.valueOf("WRITE_ALL"));
        assertNotNull(WritingTask.Type.valueOf("AUTO_RUN"));
    }
}
