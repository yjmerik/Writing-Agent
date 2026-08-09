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
            "genre TEXT, created_at TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS knowledge_entries (" +
            "id TEXT PRIMARY KEY, project_id TEXT, type TEXT, name TEXT, " +
            "content TEXT, version INTEGER, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS characters (" +
            "id TEXT PRIMARY KEY, project_id TEXT, name TEXT, role TEXT, " +
            "personality TEXT, ability TEXT, motivation TEXT, appearance TEXT, " +
            "relationships TEXT, growth_arc TEXT, " +
            "speaking_style TEXT, knowledge_domains TEXT, " +
            "strategy_level INTEGER, countermeasures TEXT, independent_will TEXT, blind_spot TEXT, " +
            "arc_stage TEXT, last_seen_chapter INTEGER, " +
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
            "resolved INTEGER, resolved_at_chapter INTEGER, updated_at TEXT, " +
            "priority TEXT DEFAULT 'MEDIUM', expected_resolve_chapter INTEGER, must_resolve INTEGER DEFAULT 0)",

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
            "relationship TEXT, weight REAL, metadata TEXT, created_at TEXT, " +
            "valid_from_chapter INTEGER DEFAULT 0, valid_to_chapter INTEGER DEFAULT 0)",

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
            "prevent_transition_overlap INTEGER DEFAULT 1, " +
            "enable_feedback_injection INTEGER DEFAULT 1, feedback_window INTEGER DEFAULT 10, " +
            "dialogue_differentiation TEXT DEFAULT 'contrast', " +
            "plot_stall_window INTEGER DEFAULT 3, character_upgrade_span INTEGER DEFAULT 2, " +
            "min_chapter_length INTEGER DEFAULT 2000, " +
            "max_beats_per_chapter INTEGER DEFAULT 4, " +
            "max_chapter_length INTEGER DEFAULT 6000, " +
            "chapter_length_variation TEXT DEFAULT 'VARIED', " +
            "fast_pace_window INTEGER DEFAULT 4, " +
            "foreshadow_suspense_threshold INTEGER DEFAULT 8, " +
            "target_chapters INTEGER DEFAULT 80, " +
            "layered_outline INTEGER DEFAULT 1, " +
            "updated_at TEXT)",

        // 生成日志表（创意多样性追踪）
        "CREATE TABLE IF NOT EXISTS generation_logs (" +
            "id TEXT PRIMARY KEY, project_id TEXT, agent_type TEXT, " +
            "summary TEXT, key_elements TEXT, volume_index INTEGER, created_at TEXT)",

        // 叙事线索表（叙事连贯性追踪）
        "CREATE TABLE IF NOT EXISTS narrative_threads (" +
            "id TEXT PRIMARY KEY, project_id TEXT, name TEXT, status TEXT, " +
            "first_seen_chapter INTEGER, last_seen_chapter INTEGER, " +
            "suspense_chapters INTEGER, description TEXT, created_at TEXT, updated_at TEXT)",

        // p11-I1: 章节评价表（每章读者评审报告持久化）
        "CREATE TABLE IF NOT EXISTS chapter_reviews (" +
            "id TEXT PRIMARY KEY, project_id TEXT, chapter_index INTEGER, " +
            "overall_score INTEGER, engagement INTEGER, coherence INTEGER, " +
            "character_score INTEGER, hook INTEGER, " +
            "summary TEXT, issues TEXT, improvement TEXT, created_at TEXT)",

        // p12-K2: 异步写作任务表
        "CREATE TABLE IF NOT EXISTS writing_tasks (" +
            "id TEXT PRIMARY KEY, project_id TEXT, status TEXT, " +
            "current_chapter INTEGER, total_chapters INTEGER, " +
            "chapters_written INTEGER, total_words INTEGER, " +
            "error_message TEXT, created_at TEXT, updated_at TEXT)"
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
            // p12-K3: 创建索引（百万字性能基础）
            createIndexes(st);
            // p10: 增量迁移——为旧库补充新增列（CREATE TABLE IF NOT EXISTS 不会改已有表结构）
            applyMigrations(st);
        } catch (SQLException e) {
            throw new RuntimeException("SQLite schema 初始化失败", e);
        }
    }

    /** 安全地添加列：忽略"列已存在"错误 */
    private void safeAddColumn(Statement st, String sql) {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            // duplicate column name → 列已存在，忽略
            if (!e.getMessage().contains("duplicate column name")) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * p12-K3: 创建数据库索引——百万字级别的性能基础。
     * 所有 WHERE/ORDER BY/GROUP BY 用到的列都需要索引。
     */
    private void createIndexes(Statement st) {
        String[] indexes = {
            // chapters: 按 project_id 查、按 index 排序
            "CREATE INDEX IF NOT EXISTS idx_chapters_project ON chapters(project_id, chapter_index)",
            // chapter_synopses: 按 project_id 查
            "CREATE INDEX IF NOT EXISTS idx_synopses_project ON chapter_synopses(project_id, chapter_index)",
            // characters: 按 project_id 查
            "CREATE INDEX IF NOT EXISTS idx_characters_project ON characters(project_id)",
            // foreshadowings: 按 project_id + resolved 过滤
            "CREATE INDEX IF NOT EXISTS idx_foreshadowings_project ON foreshadowings(project_id, resolved, priority)",
            // continuity_issues: 按 project_id + resolved 过滤
            "CREATE INDEX IF NOT EXISTS idx_issues_project ON continuity_issues(project_id, resolved, detected_at_chapter)",
            // entities: 按 project_id + type 过滤
            "CREATE INDEX IF NOT EXISTS idx_entities_project ON entities(project_id, type)",
            // entity_edges: 按 project_id / source / target 查
            "CREATE INDEX IF NOT EXISTS idx_edges_project ON entity_edges(project_id)",
            "CREATE INDEX IF NOT EXISTS idx_edges_source ON entity_edges(source_id)",
            "CREATE INDEX IF NOT EXISTS idx_edges_target ON entity_edges(target_id)",
            // summaries: 按 project_id 查
            "CREATE INDEX IF NOT EXISTS idx_summaries_project ON summaries(project_id)",
            // volumes: 按 project_id 查
            "CREATE INDEX IF NOT EXISTS idx_volumes_project ON volumes(project_id)",
            // knowledge_entries: 按 project_id 查
            "CREATE INDEX IF NOT EXISTS idx_knowledge_project ON knowledge_entries(project_id)",
            // chapter_reviews: 按 project_id + chapter 查
            "CREATE INDEX IF NOT EXISTS idx_reviews_project ON chapter_reviews(project_id, chapter_index)",
            // generation_logs: 按 project_id + agent_type 查
            "CREATE INDEX IF NOT EXISTS idx_genlogs_project ON generation_logs(project_id, agent_type)",
            // narrative_threads: 按 project_id 查
            "CREATE INDEX IF NOT EXISTS idx_threads_project ON narrative_threads(project_id)",
            // checkpoints: 按 project_id 查
            "CREATE INDEX IF NOT EXISTS idx_checkpoints_project ON checkpoints(project_id)",
            // evaluation_records: 按 project_id 查
            "CREATE INDEX IF NOT EXISTS idx_eval_project ON evaluation_records(project_id)",
            // writing_preferences: 按 project_id 查（主键已有，但显式声明）
            "CREATE INDEX IF NOT EXISTS idx_prefs_project ON writing_preferences(project_id)"
        };
        for (String idx : indexes) {
            try {
                st.execute(idx);
            } catch (SQLException e) {
                // 索引已存在则忽略
            }
        }
    }

    private void applyMigrations(Statement st) {
        // p10-c1: foreshadowings 表新增列
        safeAddColumn(st, "ALTER TABLE foreshadowings ADD COLUMN priority TEXT DEFAULT 'MEDIUM'");
        safeAddColumn(st, "ALTER TABLE foreshadowings ADD COLUMN expected_resolve_chapter INTEGER");
        safeAddColumn(st, "ALTER TABLE foreshadowings ADD COLUMN must_resolve INTEGER DEFAULT 0");
        // p10-a1: characters 表新增列
        safeAddColumn(st, "ALTER TABLE characters ADD COLUMN strategy_level INTEGER");
        safeAddColumn(st, "ALTER TABLE characters ADD COLUMN countermeasures TEXT");
        safeAddColumn(st, "ALTER TABLE characters ADD COLUMN independent_will TEXT");
        safeAddColumn(st, "ALTER TABLE characters ADD COLUMN blind_spot TEXT");
        // p10-a2: 角色弧光调度
        safeAddColumn(st, "ALTER TABLE characters ADD COLUMN arc_stage TEXT");
        safeAddColumn(st, "ALTER TABLE characters ADD COLUMN last_seen_chapter INTEGER");
        // p10-b1: writing_preferences 新增列
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN max_beats_per_chapter INTEGER DEFAULT 4");
        // p11-F2/F3: writing_preferences 新增列
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN max_chapter_length INTEGER DEFAULT 6000");
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN chapter_length_variation TEXT DEFAULT 'VARIED'");
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN fast_pace_window INTEGER DEFAULT 4");
        // p11.1-J3
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN foreshadow_suspense_threshold INTEGER DEFAULT 8");
    }
}
