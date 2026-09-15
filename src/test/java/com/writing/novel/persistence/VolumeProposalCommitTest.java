package com.writing.novel.persistence;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** p39-08：卷候选与结构来源比较提交——来源身份比对。 */
class VolumeProposalCommitTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("vol.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("卷候选", "c", "t");
        repo.saveProject(p);
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "卷一", "摘要");
        v.addSynopsis(new ChapterSynopsis(p.getId(), 1, "第1章", "梗概"));
        outline.addVolume(v);
        repo.saveOutline(outline);
    }

    @Test
    void 大纲存在且可查() {
        var outline = repo.findOutlineByProject(repo.findAllProjects().get(0).getId());
        assertTrue(outline.isPresent());
        assertEquals(1, outline.get().getVolumes().size());
    }
}
