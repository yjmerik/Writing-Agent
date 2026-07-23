package com.writing.novel.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 启动时初始化 SQLite schema（如库不存在则建表）。
 * 由 PersistenceConfig 装配为 Bean（非 @Component）。
 */
public class SqliteSchemaInitializer {

    private static final String[] DDL = {
        "CREATE TABLE IF NOT EXISTS projects (" +
            "id TEXT PRIMARY KEY, title TEXT, concept TEXT, tags TEXT, " +
            "mode TEXT, state TEXT, current_chapter_index INTEGER, " +
            "created_at TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS knowledge_entries (" +
            "id TEXT PRIMARY KEY, project_id TEXT, type TEXT, name TEXT, " +
            "content TEXT, version INTEGER, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS characters (" +
            "id TEXT PRIMARY KEY, project_id TEXT, name TEXT, role TEXT, " +
            "personality TEXT, ability TEXT, motivation TEXT, appearance TEXT, " +
            "relationships TEXT, growth_arc TEXT, " +
            "speaking_style TEXT, knowledge_domains TEXT, " +
            "version INTEGER, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS outlines (" +
            "project_id TEXT PRIMARY KEY, premise TEXT)",

        "CREATE TABLE IF NOT EXISTS volumes (" +
            "id TEXT PRIMARY KEY, project_id TEXT, volume_index INTEGER, " +
            "title TEXT, summary TEXT)",

        "CREATE TABLE IF NOT EXISTS chapter_synopses (" +
            "id TEXT PRIMARY KEY, project_id TEXT, volume_id TEXT, " +
            "chapter_index INTEGER, title TEXT, synopsis TEXT, writing_notes TEXT, " +
            "plot_purpose TEXT, tension_level INTEGER DEFAULT 5, volume_arc_position TEXT)",

        "CREATE TABLE IF NOT EXISTS chapters (" +
            "id TEXT PRIMARY KEY, project_id TEXT, chapter_index INTEGER, " +
            "title TEXT, content TEXT, word_count INTEGER, version INTEGER, " +
            "created_at TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS summaries (" +
            "id TEXT PRIMARY KEY, project_id TEXT, from_chapter INTEGER, " +
            "to_chapter INTEGER, content TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS foreshadowings (" +
            "id TEXT PRIMARY KEY, project_id TEXT, description TEXT, " +
            "planted_context TEXT, planted_at_chapter INTEGER, " +
            "resolved INTEGER, resolved_at_chapter INTEGER, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS style_profiles (" +
            "project_id TEXT PRIMARY KEY, tone TEXT, voice TEXT, " +
            "dos_and_donts TEXT, sample_chapter_indices TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS continuity_issues (" +
            "id TEXT PRIMARY KEY, project_id TEXT, detected_at_chapter INTEGER, " +
            "type TEXT, severity TEXT, description TEXT, resolved INTEGER, created_at TEXT)",

        "CREATE TABLE IF NOT EXISTS checkpoints (" +
            "id TEXT PRIMARY KEY, project_id TEXT, type TEXT, " +
            "description TEXT, status TEXT, created_at TEXT)",

        // 图数据库：实体表
        "CREATE TABLE IF NOT EXISTS entities (" +
            "id TEXT PRIMARY KEY, project_id TEXT, name TEXT, type TEXT, " +
            "aliases TEXT, first_seen_chapter INTEGER, last_seen_chapter INTEGER, " +
            "metadata TEXT, created_at TEXT, updated_at TEXT)",

        // 图数据库：关系边表
        "CREATE TABLE IF NOT EXISTS entity_edges (" +
            "id TEXT PRIMARY KEY, project_id TEXT, source_id TEXT, target_id TEXT, " +
            "relationship TEXT, weight REAL, metadata TEXT, created_at TEXT)",

        // 卷末质量评价记录表
        "CREATE TABLE IF NOT EXISTS evaluation_records (" +
            "id TEXT PRIMARY KEY, project_id TEXT, volume_index INTEGER, " +
            "scores_json TEXT, overall_score INTEGER, summary TEXT, " +
            "improvement_notes TEXT, created_at TEXT)",

        // 写作偏好表（作者可控制的写作参数）
        "CREATE TABLE IF NOT EXISTS writing_preferences (" +
            "project_id TEXT PRIMARY KEY, " +
            "dialogue_density REAL DEFAULT 0.35, enable_dialogue_enforcement INTEGER DEFAULT 1, " +
            "enable_climax_rotation INTEGER DEFAULT 1, enable_character_voice INTEGER DEFAULT 1, " +
            "enable_knowledge_quotes INTEGER DEFAULT 1, preferred_quote_sources TEXT DEFAULT '[]', " +
            "prevent_transition_overlap INTEGER DEFAULT 1, updated_at TEXT)"
    };

    private final ConnectionProvider cp;

    public SqliteSchemaInitializer(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void initialize() {
        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement()) {
            for (String ddl : DDL) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            throw new RuntimeException("SQLite schema 初始化失败", e);
        }
    }
}
