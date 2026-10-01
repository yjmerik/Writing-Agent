package com.writing.novel.service;

import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-165（review/语料取证）：实体一致性注入段必须与实体插入顺序无关——
 * findEntitiesByProject 只有 ORDER BY last_seen_chapter DESC，同章实体（lastSeenChapter
 * 相同）行序退化为 SQLite 等键序（跨运行不稳定），导致 record/replay 的写作 prompt
 * 指纹分叉（实测 record=「赊帮、三司、无账斋」/ replay=「无账斋、三司、赊帮」→ fixture miss）。
 * 修复语义：同 lastSeenChapter 内按名称升序（次级稳定键），任何插入顺序产出同一注入文本。
 */
class EntityConsistencyDeterminismTest {

    @TempDir Path dirA;
    @TempDir Path dirB;

    private record Seed(String name, EntityType type, int lastSeen) {}

    private static final List<Seed> SEEDS = List.of(
            new Seed("无账斋", EntityType.FACTION, 2),
            new Seed("三司", EntityType.FACTION, 2),
            new Seed("赊帮", EntityType.FACTION, 2),
            new Seed("糖纸", EntityType.ITEM, 2),
            new Seed("纸船", EntityType.ITEM, 2),
            new Seed("陆砚迟", EntityType.CHARACTER, 2),
            new Seed("裴赊月", EntityType.CHARACTER, 2),
            new Seed("游半秤", EntityType.CHARACTER, 2));

    private NovelRepository repoWithInsertOrder(Path dir, boolean reversed) {
        var cp = new ConnectionProvider(dir.resolve("ent.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        var seeds = new java.util.ArrayList<>(SEEDS);
        if (reversed) java.util.Collections.reverse(seeds);
        for (Seed s : seeds) {
            Entity e = new Entity("p-det", s.name(), s.type());
            e.setLastSeenChapter(s.lastSeen());
            repo.saveEntity(e);
        }
        return repo;
    }

    @Test
    void 实体一致性段输出与插入顺序无关() {
        var serviceA = new EntityGraphService(repoWithInsertOrder(dirA, false), null);
        var serviceB = new EntityGraphService(repoWithInsertOrder(dirB, true), null);

        String a = serviceA.recallConsistencySection("p-det", 3);
        String b = serviceB.recallConsistencySection("p-det", 3);

        assertTrue(a.contains("关键实体一致性"), "应产出实体一致性段: " + a);
        assertTrue(a.contains("■ 势力："), "应含势力分组");
        assertEquals(a, b,
                "同实体集不同插入顺序必须产出同一注入文本（record/replay 指纹稳定性）");
    }
}
