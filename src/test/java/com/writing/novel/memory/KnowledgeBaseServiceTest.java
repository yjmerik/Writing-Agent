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
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseServiceTest {

    @TempDir
    Path tempDir;
    private KnowledgeBaseService service;
    private SqliteNovelRepository repo;
    private ConnectionProvider connectionProvider;
    private MemoryRevision memoryRevision;

    @BeforeEach
    void setUp() {
        connectionProvider = new ConnectionProvider(tempDir.resolve("kb.db").toString());
        new SqliteSchemaInitializer(connectionProvider).initialize();
        repo = new SqliteNovelRepository(connectionProvider);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("kb.idx").toString());
        memoryRevision = new MemoryRevision();
        service = new KnowledgeBaseService(repo, new VectorRetrievalService(store, emb), memoryRevision);
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
    void 整体替换知识时先提交数据库_再清旧向量并批量索引新条目() {
        KnowledgeEntry old = new KnowledgeEntry("p1", "POWER_SYSTEM", "九境灵气", "修士可飞升");
        service.saveEntry(old);
        long beforeRevision = memoryRevision.current("p1");
        KnowledgeEntry history = new KnowledgeEntry("p1", "HISTORY", "永熙年间", "低魔王朝末年");
        KnowledgeEntry rule = new KnowledgeEntry("p1", "RULE", "方术限制", "只能辨迹寻物");

        service.replaceEntries("p1", List.of(history, rule));

        assertNull(service.getIndexedText(old.getId()));
        assertTrue(service.getIndexedText(history.getId()).contains("永熙年间"));
        assertTrue(service.getIndexedText(rule.getId()).contains("方术限制"));
        assertEquals(Set.of(history.getId(), rule.getId()), repo.findKnowledgeEntriesByProject("p1").stream()
                .map(KnowledgeEntry::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(beforeRevision + 1, memoryRevision.current("p1"));
    }

    @Test
    void 数据库整体替换失败时不提前改变向量索引() throws Exception {
        KnowledgeEntry old = new KnowledgeEntry("p1", "RULE", "旧规则", "原内容");
        service.saveEntry(old);
        KnowledgeEntry replacement = new KnowledgeEntry("p1", "RULE", "触发失败", "不得落库");
        try (Connection connection = connectionProvider.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER abort_kb_replace BEFORE INSERT ON knowledge_entries "
                    + "WHEN NEW.name = '触发失败' BEGIN SELECT RAISE(ABORT, 'forced failure'); END");
        }
        long beforeRevision = memoryRevision.current("p1");

        assertThrows(RuntimeException.class,
                () -> service.replaceEntries("p1", List.of(replacement)));

        assertNotNull(service.getIndexedText(old.getId()));
        assertNull(service.getIndexedText(replacement.getId()));
        assertEquals("原内容", repo.findKnowledgeEntriesByProject("p1").get(0).getContent());
        assertEquals(beforeRevision, memoryRevision.current("p1"));
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
