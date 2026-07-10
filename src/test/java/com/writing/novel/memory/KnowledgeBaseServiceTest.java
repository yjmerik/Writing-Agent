package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseServiceTest {

    @TempDir
    Path tempDir;
    private KnowledgeBaseService service;
    private SqliteNovelRepository repo;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("kb.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("kb.idx").toString());
        service = new KnowledgeBaseService(repo, new VectorRetrievalService(store, emb));
    }

    @Test
    void 保存知识条目并按类型查询() {
        KnowledgeEntry geo = new KnowledgeEntry("p1", "GEOGRAPHY", "玄黄大陆", "东方主大陆");
        KnowledgeEntry power = new KnowledgeEntry("p1", "POWER_SYSTEM", "灵气体系", "九重境界");
        service.saveEntry(geo);
        service.saveEntry(power);

        List<KnowledgeEntry> geoEntries = service.findEntriesByType("p1", "GEOGRAPHY");
        assertEquals(1, geoEntries.size());
        assertEquals("玄黄大陆", geoEntries.get(0).getName());
    }

    @Test
    void 保存人物并查询全部() {
        Character c1 = new Character("p1", "张三", "主角");
        c1.setPersonality("火爆");
        Character c2 = new Character("p1", "李四", "配角");
        service.saveCharacter(c1);
        service.saveCharacter(c2);

        List<Character> chars = service.findAllCharacters("p1");
        assertEquals(2, chars.size());
    }

    @Test
    void 保存知识时同步索引到向量库() {
        KnowledgeEntry entry = new KnowledgeEntry("p1", "GEOGRAPHY", "青云宗",
                "位于玄黄大陆东部的修仙宗门");
        service.saveEntry(entry);

        String indexed = service.getIndexedText(entry.getId());
        assertNotNull(indexed);
        assertTrue(indexed.contains("青云宗"));
    }

    @Test
    void 按名称查人物() {
        Character c = new Character("p1", "王五", "反派");
        service.saveCharacter(c);

        var found = service.findCharacterByName("p1", "王五");
        assertTrue(found.isPresent());
        assertEquals("反派", found.get().getRole());
    }
}
