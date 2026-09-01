package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p14-01: deleteProject must not 500 on projects.id, and must cascade outline/facts.
 */
class SqliteNovelRepositoryDeleteTest {

    @TempDir Path tempDir;

    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private StoryFactRepository facts;

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("del.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        facts = new StoryFactRepository(cp);
    }

    @Test
    void deleteProject_withOutlineWorldAndFacts_doesNotThrow_andLeavesNoRows() throws Exception {
        Project p = new Project("删测", "构思", "志怪");
        repo.saveProject(p);
        String id = p.getId();

        repo.saveKnowledgeEntry(new KnowledgeEntry(id, "RULE", "债册", "凡录名必偿"));
        repo.saveOutline(new Outline(id, "总纲一句"));
        facts.save(new StoryFact(id, StoryFact.FactType.WORLD_RULE, "债册", "凡录名必偿", 1));

        assertTrue(count("projects", "id", id) == 1);
        assertTrue(count("knowledge_entries", "project_id", id) == 1);
        assertTrue(count("outlines", "project_id", id) == 1);
        assertTrue(count("story_facts", "project_id", id) == 1);

        assertDoesNotThrow(() -> repo.deleteProject(id));

        assertEquals(0, count("projects", "id", id));
        assertEquals(0, count("knowledge_entries", "project_id", id));
        assertEquals(0, count("outlines", "project_id", id));
        assertEquals(0, count("story_facts", "project_id", id));
    }

    private int count(String table, String col, String id) throws SQLException {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + col + "=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }
}
