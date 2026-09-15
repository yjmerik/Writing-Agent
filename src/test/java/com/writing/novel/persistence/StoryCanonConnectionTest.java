package com.writing.novel.persistence;

import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

/** p39-10：真典同连接事务辅助——save/supersede 组合异常全回滚；连接数=1；原契约通过。 */
class StoryCanonConnectionTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;
    private StoryFactRepository factRepo;

    private StoryFact fact(String id, String subject, String content) {
        StoryFact f = new StoryFact("p1", StoryFact.FactType.WORLD_RULE, subject, content, 1);
        f.setId(id);
        return f;
    }

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("canon.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        factRepo = new StoryFactRepository(cp);
    }

    @Test
    void 同一事务save_supersede异常时全回滚() {
        factRepo.save(fact("old-1", "旧事实", "内容甲"));

        AtomicInteger connectionCount = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> factRepo.runInTransaction(conn -> {
            connectionCount.incrementAndGet();
            StoryFact newFact = fact("new-1", "新事实", "内容乙");
            StoryFact old = factRepo.findById("old-1").orElseThrow();
            factRepo.supersede(conn, old, newFact);
            // 组合操作后注入异常 → 全回滚
            throw new RuntimeException("injected failure");
        }), "注入异常必须传播");

        assertEquals(1, connectionCount.get(), "事务使用单一连接");
        assertEquals(1, countFacts(), "回滚后只有原始一行");
        assertTrue(factRepo.findById("old-1").orElseThrow().getStatus() == StoryFact.Status.ACTIVE,
                "旧事实未被 supersede");
        assertTrue(factRepo.findById("new-1").isEmpty(), "新事实未写入");
    }

    @Test
    void 同一事务save_supersede正常提交() {
        factRepo.save(fact("old-2", "旧事实", "内容甲"));
        factRepo.runInTransaction(conn -> {
            StoryFact newFact = fact("new-2", "新事实", "内容乙");
            StoryFact old = factRepo.findById("old-2").orElseThrow();
            factRepo.supersede(conn, old, newFact);
            return null;
        });
        assertEquals(2, countFacts());
        assertEquals(StoryFact.Status.SUPERSEDED,
                factRepo.findById("old-2").orElseThrow().getStatus());
        assertEquals(StoryFact.Status.ACTIVE,
                factRepo.findById("new-2").orElseThrow().getStatus());
    }

    @Test
    void 事实证据同事务写入() {
        factRepo.runInTransaction(conn -> {
            factRepo.save(conn, fact("f-3", "事实三", "内容"));
            factRepo.saveFactEvidence(conn, "ev-1", "f-3", "p1", 3, 1, "hash", "引文", 0, "a", 3, null);
            return null;
        });
        assertEquals(1, countFacts());
        try (var conn = cp.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM fact_evidence WHERE fact_id='f-3'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void 原SqliteStoryCanon单参构造契约通过() {
        // 原单参 SqliteStoryCanon 构造保持兼容
        SqliteStoryCanon canon = new SqliteStoryCanon(factRepo);
        assertNotNull(canon);
    }

    private int countFacts() {
        try (var conn = cp.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM story_facts")) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
