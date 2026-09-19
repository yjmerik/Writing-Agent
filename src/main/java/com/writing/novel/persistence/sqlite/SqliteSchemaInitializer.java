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
            "status TEXT, death_chapter INTEGER, " +
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
            "status TEXT DEFAULT 'DONE', " +
            "created_at TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS chapter_versions (" +
            "id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, " +
            "version INTEGER NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, " +
            "source TEXT NOT NULL, created_at TEXT NOT NULL, " +
            "UNIQUE(project_id, chapter_index, version))",

        "CREATE TABLE IF NOT EXISTS summaries (" +
            "id TEXT PRIMARY KEY, project_id TEXT, from_chapter INTEGER, " +
            "to_chapter INTEGER, content TEXT, updated_at TEXT, " +
            "source_chapter_version INTEGER DEFAULT 0, dirty INTEGER DEFAULT 0)",

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
            "description TEXT, status TEXT, created_at TEXT, payload TEXT)",

        // p32-11: 全书质量评价历史（POST /evaluate 落库，GET 读回最近一次）
        "CREATE TABLE IF NOT EXISTS quality_evaluations (" +
            "id TEXT PRIMARY KEY, project_id TEXT, overall_score INTEGER, " +
            "report_json TEXT, created_at TEXT)",

        // p33-07: 候选线索——细节线索前置层，作者提升/拒绝
        "CREATE TABLE IF NOT EXISTS foreshadow_candidates (" +
            "id TEXT PRIMARY KEY, project_id TEXT, description TEXT, planted_context TEXT, " +
            "planted_at_chapter INTEGER, status TEXT, created_at TEXT, updated_at TEXT)",

        // p33-06: 作者意见/要求——意见闭环持久化（系统落实与作者确认分开）
        "CREATE TABLE IF NOT EXISTS author_directives (" +
            "id TEXT PRIMARY KEY, project_id TEXT, kind TEXT, chapter_index INTEGER, " +
            "content TEXT, status TEXT, system_evidence TEXT, system_evidence_version INTEGER, " +
            "created_at TEXT, confirmed_at TEXT, updated_at TEXT)",

        // p33-01: 人审屏障——标记时立即落盘，写作调度查全部 ACTIVE 屏障
        "CREATE TABLE IF NOT EXISTS review_barriers (" +
            "id TEXT PRIMARY KEY, project_id TEXT, chapter_index INTEGER, chapter_version INTEGER, " +
            "reason TEXT, status TEXT, created_at TEXT, resolved_at TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_review_barriers_project ON review_barriers(project_id, status)",

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
                "target_chapters INTEGER DEFAULT 400, " +
                "layered_outline INTEGER DEFAULT 1, " +
                "cost_budget_yuan REAL DEFAULT 0, " +
                "rewrite_budget INTEGER DEFAULT 1, " +
                "circuit_breaker_threshold INTEGER DEFAULT 10, " +
                "checkpoint_span INTEGER DEFAULT 1, " +
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

        // p12-K2: 异步写作任务表（p15-05: task_type 区分构思/单章/全卷任务）
        "CREATE TABLE IF NOT EXISTS writing_tasks (" +
            "id TEXT PRIMARY KEY, project_id TEXT, status TEXT, " +
            "task_type TEXT DEFAULT 'WRITE_ALL', " +
            "current_chapter INTEGER, total_chapters INTEGER, " +
            "chapters_written INTEGER, total_words INTEGER, " +
            "error_message TEXT, created_at TEXT, updated_at TEXT)",

        // p10-w2-01: 故事真典——可累积可纠错的事实真相层
        "CREATE TABLE IF NOT EXISTS story_facts (" +
            "id TEXT PRIMARY KEY, project_id TEXT, fact_type TEXT, " +
            "subject TEXT, assertion TEXT, chapter_established INTEGER, " +
            "source_chapter_version INTEGER DEFAULT 0, importance INTEGER DEFAULT 3, " +
            "status TEXT DEFAULT 'ACTIVE', superseded_by TEXT, " +
            "last_validated_chapter INTEGER, created_at TEXT, updated_at TEXT)",

        // NCS-001: 项目级写入门禁租约——epoch 单调递增，跨重启保持
        "CREATE TABLE IF NOT EXISTS project_write_leases (" +
            "project_id TEXT PRIMARY KEY, epoch INTEGER NOT NULL DEFAULT 0, " +
            "reason TEXT, updated_at TEXT)",

        // NCS-003: 创作约定——append-only 版本化（(project_id, revision) 主键，历史不可变）
        "CREATE TABLE IF NOT EXISTS author_intents (" +
            "project_id TEXT, revision INTEGER, premise TEXT, genre_expectation TEXT, " +
            "themes TEXT, pov TEXT, language_samples TEXT, length_range TEXT, " +
            "autonomy TEXT, unwanted_patterns TEXT, updated_at TEXT, " +
            "PRIMARY KEY (project_id, revision))",

        // NCS-007: 检查结果信封——(章×检查器) 逐次追加，读取取最新；UNKNOWN/INVALID=尚未查清
        "CREATE TABLE IF NOT EXISTS check_results (" +
            "id TEXT PRIMARY KEY, project_id TEXT, chapter_index INTEGER, checker TEXT, " +
            "status TEXT, reason TEXT, created_at TEXT)",

        // p17-05: 自定义预设持久化（跨项目共享，name 主键 upsert）
        "CREATE TABLE IF NOT EXISTS custom_presets (" +
            "name TEXT PRIMARY KEY, content TEXT, updated_at TEXT)",

        // p20-03: 后处理阶段账本——每项目/章节/阶段一行，重启续跑只补未完成阶段
        "CREATE TABLE IF NOT EXISTS post_process_ledger (" +
            "project_id TEXT, chapter_index INTEGER, stage TEXT, " +
            "status TEXT DEFAULT 'PENDING', updated_at TEXT, " +
            "PRIMARY KEY (project_id, chapter_index, stage))",

        // p36-02: 不可变章节批准审计——DONE 不等于作者批准。
        // 批准绑定稿件身份 (project_id, chapter_index, chapter_version, content_hash)；
        // 正文/屏障修订只失效其有效性，永不改写历史批准行。已存在 DONE 不回填。
        "CREATE TABLE IF NOT EXISTS chapter_approvals (" +
            "id TEXT PRIMARY KEY, project_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, " +
            "chapter_version INTEGER NOT NULL, content_hash TEXT NOT NULL, " +
            "review_set_hash TEXT NOT NULL, idempotency_key TEXT NOT NULL, " +
            "reason TEXT, created_at TEXT NOT NULL, " +
            "UNIQUE(project_id, idempotency_key))",

        // p36-02: 屏障身份历史——修订/放行等变迁追加旧屏障身份与修订前后版本，只增不改
        "CREATE TABLE IF NOT EXISTS review_barrier_history (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, barrier_id TEXT NOT NULL, " +
            "project_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, " +
            "old_chapter_version INTEGER, new_chapter_version INTEGER, " +
            "event TEXT NOT NULL, created_at TEXT NOT NULL)",

        // p37-01: 持久修订候选——候选是 DB 资源而非弹窗内存；刷新/重启后可恢复。
        // UNIQUE(project_id, request_key) 幂等；APPLIED/STALE/CANCELLED 均留记录，无 TTL 自动删除。
        "CREATE TABLE IF NOT EXISTS revision_proposals (" +
            "id TEXT PRIMARY KEY, project_id TEXT NOT NULL, target_type TEXT NOT NULL, " +
            "target_id TEXT NOT NULL, chapter_index INTEGER, passage_index INTEGER, " +
            "base_version INTEGER NOT NULL, source_hash TEXT NOT NULL, " +
            "instructions TEXT, before_json TEXT, after_json TEXT, summary TEXT, " +
            "status TEXT NOT NULL, request_key TEXT NOT NULL, result_json TEXT, " +
            "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
            "UNIQUE(project_id, request_key))",
        "CREATE INDEX IF NOT EXISTS idx_revision_proposals_project ON revision_proposals(project_id, status)",

        // p37-06: 逐章证据——同意见可有多章多稿证据，互不覆盖
        "CREATE TABLE IF NOT EXISTS directive_evidence (" +
            "id TEXT PRIMARY KEY, directive_id TEXT NOT NULL, project_id TEXT NOT NULL, " +
            "directive_revision INTEGER NOT NULL, chapter_index INTEGER NOT NULL, " +
            "chapter_version INTEGER NOT NULL, content_hash TEXT, proposal_id TEXT, " +
            "quote TEXT, anchor TEXT, status TEXT NOT NULL, reason TEXT, created_at TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS idx_directive_evidence ON directive_evidence(project_id, directive_id)",

        // p38-01: 计划变更凭证——预览→确认持久化，预览内容不可被后续目标改写
        "CREATE TABLE IF NOT EXISTS plan_changes (" +
            "preview_id TEXT NOT NULL, project_id TEXT NOT NULL, source_plan_hash TEXT NOT NULL, " +
            "target_chapters INTEGER NOT NULL, written_boundary INTEGER NOT NULL, " +
            "affected_promises_json TEXT, pruned_chapters_json TEXT, " +
            "status TEXT NOT NULL DEFAULT 'PENDING', created_at TEXT NOT NULL, " +
            "UNIQUE(project_id, preview_id))",

        // p38-01: 承诺处置审计——用 promiseId 而非描述匹配
        "CREATE TABLE IF NOT EXISTS promise_decisions (" +
            "id TEXT PRIMARY KEY, plan_change_id TEXT NOT NULL, promise_id TEXT NOT NULL, " +
            "action TEXT NOT NULL, target_chapter INTEGER, reason TEXT, created_at TEXT NOT NULL)",

        // p38-05: 不可变成书版本——正文快照物化，批准绑定 manifestHash
        "CREATE TABLE IF NOT EXISTS book_editions (" +
            "edition_id TEXT PRIMARY KEY, project_id TEXT NOT NULL, manifest_hash TEXT NOT NULL, " +
            "state TEXT NOT NULL DEFAULT 'DRAFT', created_at TEXT NOT NULL, approved_at TEXT)",

        "CREATE TABLE IF NOT EXISTS book_edition_chapters (" +
            "edition_id TEXT NOT NULL, chapter_index INTEGER NOT NULL, version INTEGER NOT NULL, " +
            "title TEXT NOT NULL, content TEXT NOT NULL, content_hash TEXT NOT NULL, " +
            "approval_id TEXT, plan_ref TEXT, " +
            "PRIMARY KEY (edition_id, chapter_index))",

        // p38-09: 版本绑定的问题裁决——identity 变化不继承
        "CREATE TABLE IF NOT EXISTS review_dispositions (" +
            "id TEXT PRIMARY KEY, project_id TEXT NOT NULL, issue_id TEXT NOT NULL, " +
            "issue_fingerprint TEXT NOT NULL, chapter_index INTEGER NOT NULL, " +
            "chapter_version INTEGER NOT NULL, content_hash TEXT NOT NULL, " +
            "action TEXT NOT NULL, reason TEXT NOT NULL, defer_until_chapter INTEGER, " +
            "created_at TEXT NOT NULL)",

        // p39-01: 事实来源锚点与知情区间
        "CREATE TABLE IF NOT EXISTS fact_evidence (" +
            "id TEXT PRIMARY KEY, fact_id TEXT NOT NULL, project_id TEXT NOT NULL, " +
            "chapter_index INTEGER NOT NULL, chapter_version INTEGER NOT NULL, " +
            "content_hash TEXT NOT NULL, quote TEXT, paragraph_index INTEGER, anchor_hash TEXT, " +
            "valid_from_chapter INTEGER NOT NULL, valid_to_chapter INTEGER, " +
            "created_at TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS idx_fact_evidence ON fact_evidence(project_id, fact_id)",

        // p39-01: 知情者——按人在不同章分别记录
        "CREATE TABLE IF NOT EXISTS fact_evidence_observers (" +
            "id TEXT PRIMARY KEY, evidence_id TEXT NOT NULL, character_id TEXT NOT NULL, " +
            "learned_at_chapter INTEGER NOT NULL, chapter_version INTEGER NOT NULL, " +
            "quote TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_fact_observers ON fact_evidence_observers(evidence_id)"
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
            "CREATE INDEX IF NOT EXISTS idx_prefs_project ON writing_preferences(project_id)",
            // p19-02: story_facts 按 project_id + status 过滤——findActive/findActiveBySubject 每章高频调用，
            // 千行级事实无索引时全表扫（400 章 × 5+ 次/章劣化）
            "CREATE INDEX IF NOT EXISTS idx_story_facts_project_status ON story_facts(project_id, status)",
            // p36-02: 批准审计按稿件身份查询（审阅上下文读取当前稿批准）
            "CREATE INDEX IF NOT EXISTS idx_chapter_approvals_chapter ON chapter_approvals(project_id, chapter_index, chapter_version)",
            // p36-02: 屏障历史按项目+章节追溯
            "CREATE INDEX IF NOT EXISTS idx_barrier_history_project ON review_barrier_history(project_id, chapter_index)"
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
        // p18-02: checkpoints 表新增 payload 列（审批质量信息）
        safeAddColumn(st, "ALTER TABLE checkpoints ADD COLUMN payload TEXT");
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
        // mc-p2-01: versioned memory——summaries 派生版本与脏标记
        safeAddColumn(st, "ALTER TABLE summaries ADD COLUMN source_chapter_version INTEGER DEFAULT 0");
        safeAddColumn(st, "ALTER TABLE summaries ADD COLUMN dirty INTEGER DEFAULT 0");
        // cd-fix: chapter_synopses 持久化 cast/beats/climax（之前解析后丢失）
        safeAddColumn(st, "ALTER TABLE chapter_synopses ADD COLUMN cast TEXT");
        safeAddColumn(st, "ALTER TABLE chapter_synopses ADD COLUMN beats TEXT");
        safeAddColumn(st, "ALTER TABLE chapter_synopses ADD COLUMN climax TEXT");
        // C2: 全书摘要——卷末压缩为≤3句的"全书一句话摘要"，修复记忆地平线坍塌
        safeAddColumn(st, "ALTER TABLE projects ADD COLUMN book_summary TEXT");
        // p19-03: 全书结构化快照
        safeAddColumn(st, "ALTER TABLE projects ADD COLUMN book_snapshot TEXT");
        // p19-01: 角色存续状态
        safeAddColumn(st, "ALTER TABLE characters ADD COLUMN status TEXT");
        safeAddColumn(st, "ALTER TABLE characters ADD COLUMN death_chapter INTEGER");
        // p10-w1-04: 成本预算熔断
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN cost_budget_yuan REAL DEFAULT 0");
        // p11-q2-04/05: 重写预算 + 质量熔断阈值（可配置）
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN rewrite_budget INTEGER DEFAULT 1");
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN circuit_breaker_threshold INTEGER DEFAULT 10");
        // p10-w1-06: 章节状态机
        safeAddColumn(st, "ALTER TABLE chapters ADD COLUMN status TEXT DEFAULT 'DONE'");
        safeAddColumn(st, "ALTER TABLE writing_preferences ADD COLUMN checkpoint_span INTEGER DEFAULT 1");
        // p15-05: 写作任务类型（旧库无此列时补列，存量行默认 WRITE_ALL）
        safeAddColumn(st, "ALTER TABLE writing_tasks ADD COLUMN task_type TEXT DEFAULT 'WRITE_ALL'");
        safeAddColumn(st, "ALTER TABLE writing_tasks ADD COLUMN instruction TEXT");
        safeAddColumn(st, "ALTER TABLE writing_tasks ADD COLUMN stop_requested INTEGER NOT NULL DEFAULT 0");
        // NCS-001: 任务启动时捕获的写入门禁 epoch（旧任务默认 0 与无租约行匹配）
        safeAddColumn(st, "ALTER TABLE writing_tasks ADD COLUMN lease_epoch INTEGER NOT NULL DEFAULT 0");
        // p32-04: 章节写作采用的计划出处（卷号+梗概指纹），旧行 NULL
        safeAddColumn(st, "ALTER TABLE chapters ADD COLUMN plan_ref TEXT");
        // p32-06: 真典事实证据分级（机器收割的世界铁律=UNVERIFIED），旧行默认 CONFIRMED
        safeAddColumn(st, "ALTER TABLE story_facts ADD COLUMN evidence_grade TEXT DEFAULT 'CONFIRMED'");
        // NCS-008: 谓词化断言——谓词/取值/极性/生效区间（旧行 NULL/默认）
        safeAddColumn(st, "ALTER TABLE story_facts ADD COLUMN predicate TEXT");
        safeAddColumn(st, "ALTER TABLE story_facts ADD COLUMN object_value TEXT");
        safeAddColumn(st, "ALTER TABLE story_facts ADD COLUMN polarity TEXT");
        safeAddColumn(st, "ALTER TABLE story_facts ADD COLUMN valid_from INTEGER");
        safeAddColumn(st, "ALTER TABLE story_facts ADD COLUMN valid_to INTEGER");
        // NCS-009: 证据失效标记——来源稿被修订后旧版本证据不再支持事实
        safeAddColumn(st, "ALTER TABLE fact_evidence ADD COLUMN stale INTEGER NOT NULL DEFAULT 0");
        // p32-05: 审核产物绑定产生时点的章节版本与正文指纹，旧行 NULL
        safeAddColumn(st, "ALTER TABLE continuity_issues ADD COLUMN chapter_version INTEGER");
        safeAddColumn(st, "ALTER TABLE continuity_issues ADD COLUMN content_hash TEXT");
        safeAddColumn(st, "ALTER TABLE chapter_reviews ADD COLUMN chapter_version INTEGER");
        safeAddColumn(st, "ALTER TABLE chapter_reviews ADD COLUMN content_hash TEXT");
        // p32-08: 伏笔回收证据（detector reason/人工确认），旧行 NULL
        safeAddColumn(st, "ALTER TABLE foreshadowings ADD COLUMN resolve_evidence TEXT");
        // p37-06: 意见范围与逐章证据迁移——旧行 chapterIndex→同起止、null→全书从1起、revision=1、enabled=1
        safeAddColumn(st, "ALTER TABLE author_directives ADD COLUMN revision INTEGER");
        safeAddColumn(st, "ALTER TABLE author_directives ADD COLUMN from_chapter INTEGER");
        safeAddColumn(st, "ALTER TABLE author_directives ADD COLUMN to_chapter INTEGER");
        safeAddColumn(st, "ALTER TABLE author_directives ADD COLUMN enabled INTEGER DEFAULT 1");
        try {
            st.execute("UPDATE author_directives SET revision=1, enabled=1, " +
                    "from_chapter=COALESCE(chapter_index, 1), to_chapter=chapter_index " +
                    "WHERE revision IS NULL");
        } catch (SQLException e) {
            if (!e.getMessage().contains("no such column")) throw new RuntimeException(e);
        }
    }
}
