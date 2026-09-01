package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Volume;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutlineReplannerTest {

    @Test
    void 替换未写卷_已写梗概不变_不改premise() {
        Outline outline = new Outline("p", "原始总纲不得被追加偏离段");
        Volume v1 = new Volume("p", 1, "卷一", "已写");
        v1.addSynopsis(new ChapterSynopsis("p", 1, "第1章 旧", "已写梗概1"));
        v1.addSynopsis(new ChapterSynopsis("p", 2, "第2章 旧", "已写梗概2"));
        Volume v2 = new Volume("p", 2, "卷二旧", "未写旧");
        v2.addSynopsis(new ChapterSynopsis("p", 3, "第3章 旧未写", "旧未写梗概"));
        outline.addVolume(v1);
        outline.addVolume(v2);

        Volume replacement = new Volume("p", 2, "卷二新", "重规划");
        replacement.addSynopsis(new ChapterSynopsis("p", 3, "第3章 新", "新梗概"));
        replacement.addSynopsis(new ChapterSynopsis("p", 4, "第4章 新", "新梗概2"));

        new OutlineReplanner().replaceUnwritten(outline, 2, List.of(replacement));

        assertEquals("原始总纲不得被追加偏离段", outline.getPremise());
        assertFalse(outline.getPremise().contains("剧情偏离与重规划"));
        assertEquals("第1章 旧", outline.getVolumes().get(0).getSynopses().get(0).getTitle());
        assertEquals("第2章 旧", outline.getVolumes().get(0).getSynopses().get(1).getTitle());
        Volume second = outline.getVolumes().get(1);
        assertEquals("卷二新", second.getTitle());
        assertEquals(2, second.getSynopses().size());
        assertEquals("第3章 新", second.getSynopses().get(0).getTitle());
    }
}
