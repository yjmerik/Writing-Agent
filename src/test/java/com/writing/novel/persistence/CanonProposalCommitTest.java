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

import static org.junit.jupiter.api.Assertions.*;

/** p39-07：世界人物候选与真典原子提交——save+supersede 同事务，异常全回滚。 */
class CanonProposalCommitTest {

    @TempDir
    Path tempDir;
    private StoryFactRepository factRepo;

    private StoryFact fact(String id, String subject, String assertion) {
        StoryFact f = new StoryFact("p1", StoryFact.FactType.WORLD_RULE, subject, assertion, 1);
        f.setId(id);
        return f;
    }

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("canon.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        factRepo = new StoryFactRepository(cp);
    }

    @Test
    void 原子save_supersede成功() {
        factRepo.save(fact("old", "旧铁律", "旧内容"));
        factRepo.runInTransaction(conn -> {
            factRepo.supersede(conn, factRepo.findById("old").orElseThrow(),
                    fact("new", "新铁律", "新内容"));
            return null;
        });
        assertEquals(2, countFacts());
        assertEquals(StoryFact.Status.SUPERSEDED, factRepo.findById("old").orElseThrow().getStatus());
    }

    @Test
    void 原子save_supersede异常全回滚() {
        factRepo.save(fact("old", "旧铁律", "旧内容"));
        assertThrows(RuntimeException.class, () -> factRepo.runInTransaction(conn -> {
            factRepo.supersede(conn, factRepo.findById("old").orElseThrow(),
                    fact("new", "新铁律", "新内容"));
            throw new RuntimeException("fail");
        }));
        assertEquals(1, countFacts(), "全回滚");
        assertEquals(StoryFact.Status.ACTIVE, factRepo.findById("old").orElseThrow().getStatus());
    }

    private int countFacts() {
        return factRepo.read(conn -> {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM story_facts")) {
                rs.next();
                return rs.getInt(1);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
