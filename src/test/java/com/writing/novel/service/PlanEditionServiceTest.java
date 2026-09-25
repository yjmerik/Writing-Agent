package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * NCS-083（听雪刀 P1-3）：压缩结构贯穿延迟生成——
 * 确认压缩后 outline.premise 必须追加【篇幅与结构（当前有效）】权威覆盖段
 * （此前总纲文本仍是旧 24 章结构，延迟生成的末章按旧结构规划成"新篇开头"），
 * 且 ContextAssembler 稳定段缓存失效。
 */
class PlanEditionServiceTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private PlanEditionService service;
    private ContextAssembler contextAssembler;
    private String pid;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("plan-edit.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        service = new PlanEditionService(repo);
        contextAssembler = mock(ContextAssembler.class);
        service.setContextAssembler(contextAssembler);
        Project p = new Project("压缩书", "构思", "武侠");
        p.setCurrentChapterIndex(9);
        repo.saveProject(p);
        pid = p.getId();
        Outline outline = new Outline(pid, "旧总纲（24章结构）：卷一第1-8章，卷二第9-16章（含第14章回收位），卷三第17-24章（含第22章回收位）。");
        Volume v = new Volume(pid, 1, "卷一", "摘要");
        for (int i = 1; i <= 24; i++) {
            v.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概" + i));
        }
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
    }

    private Map<String, Object> previewAndConfirm(int target) {
        Map<String, Object> preview = service.previewTarget(pid, target);
        return service.confirmTarget(pid, (String) preview.get("previewId"),
                (String) preview.get("sourcePlanHash"), target, List.of(), "k-" + target);
    }

    @Test
    void 确认压缩后premise追加权威覆盖段() {
        previewAndConfirm(10);

        Outline outline = repo.findOutlineByProject(pid).orElseThrow();
        String premise = outline.getPremise();
        assertTrue(premise.startsWith("旧总纲"), "原总纲应保留（历史溯源）");
        assertTrue(premise.contains("【篇幅与结构（当前有效）】"),
                "必须追加权威覆盖段，实际: " + premise);
        assertTrue(premise.contains("目标章数：10"), "覆盖段应声明压缩后目标");
        assertTrue(premise.contains("已完成"), "覆盖段应声明已写边界");
        assertTrue(premise.contains("收束主线"), "覆盖段应要求末段收束与回收伏笔");
        assertTrue(premise.contains("冲突时以本段为准"), "覆盖段必须声明其优先级覆盖旧总纲");
    }

    @Test
    void 二次压缩只保留一份覆盖段() {
        previewAndConfirm(10);
        previewAndConfirm(9);

        String premise = repo.findOutlineByProject(pid).orElseThrow().getPremise();
        assertEquals(1, premise.split("【篇幅与结构（当前有效）】", -1).length - 1,
                "覆盖段只保留一份（替换而非叠加）");
        assertTrue(premise.contains("目标章数：9"), "覆盖段应反映最新目标");
    }

    @Test
    void 确认后失效上下文稳定段缓存() {
        previewAndConfirm(10);
        verify(contextAssembler).invalidateProject(pid);
    }
}
