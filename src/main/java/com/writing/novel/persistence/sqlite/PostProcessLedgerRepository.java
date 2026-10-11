package com.writing.novel.persistence.sqlite;

import com.writing.novel.orchestrator.ChapterPostProcessor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * p20-03: 后处理阶段账本持久化——每 (project, chapter, stage) 一行，状态机
 * PENDING → RUNNING → COMPLETED。
 *
 * <p>语义：
 * <ul>
 *   <li>主键约束保证同 project/chapter/stage 恰好一行（重复标记幂等）</li>
 *   <li>COMPLETED 是终态——迟到的 RUNNING/重复 COMPLETED 不得降级或新增行</li>
 *   <li>{@link #unfinishedStages} 按阶段声明顺序返回，重启续跑依序补跑</li>
 * </ul>
 */
@Component
public class PostProcessLedgerRepository {

    public enum StageStatus { PENDING, RUNNING, COMPLETED }

    private final ConnectionProvider cp;

    public PostProcessLedgerRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    /** 无行则建 PENDING 行（有行不改动——不覆盖已 RUNNING/COMPLETED） */
    public void ensureStage(String projectId, int chapterIndex, ChapterPostProcessor.Stage stage) {
        ensureStage(projectId, chapterIndex, stage, 0);
    }

    /**
     * NCS-041: 带来源稿版本的入账——(项目,章,投影类型) 唯一；
     * 来源稿版本升级（重写）时重新武装 PENDING 并绑定新版本。
     */
    public void ensureStage(String projectId, int chapterIndex,
                            ChapterPostProcessor.Stage stage, int sourceVersion) {
        String sql = "INSERT INTO post_process_ledger (project_id, chapter_index, stage, status, updated_at, source_version, attempts) " +
                "VALUES (?,?,?,'PENDING',?,?,0) " +
                "ON CONFLICT(project_id, chapter_index, stage) DO UPDATE SET " +
                "status=CASE WHEN excluded.source_version>COALESCE(post_process_ledger.source_version,0) " +
                "  AND post_process_ledger.status!='COMPLETED' THEN 'PENDING' ELSE post_process_ledger.status END, " +
                "source_version=CASE WHEN excluded.source_version>COALESCE(post_process_ledger.source_version,0) " +
                "  THEN excluded.source_version ELSE post_process_ledger.source_version END, " +
                "updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setString(3, stage.name());
            ps.setString(4, Instant.now().toString());
            ps.setInt(5, sourceVersion);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** NCS-043: 失败持久待重试——状态回 PENDING、attempts+1（重启后仍在账） */
    public void failStage(String projectId, int chapterIndex, ChapterPostProcessor.Stage stage) {
        String sql = "UPDATE post_process_ledger SET status='PENDING', " +
                "attempts=COALESCE(attempts,0)+1, updated_at=? " +
                "WHERE project_id=? AND chapter_index=? AND stage=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, projectId);
            ps.setInt(3, chapterIndex);
            ps.setString(4, stage.name());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /**
     * NCS-043: 带来源版本的完成标记——旧来源不得完成新稿：
     * 账本版本与预期不符时返回 false（调用方按新稿重新入账）。
     */
    public boolean markCompletedForVersion(String projectId, int chapterIndex,
                                           ChapterPostProcessor.Stage stage, int expectedSourceVersion) {
        ensureStage(projectId, chapterIndex, stage, expectedSourceVersion);
        String sql = "UPDATE post_process_ledger SET status='COMPLETED', updated_at=? " +
                "WHERE project_id=? AND chapter_index=? AND stage=? AND status!='COMPLETED' " +
                "AND COALESCE(source_version,0)=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, projectId);
            ps.setInt(3, chapterIndex);
            ps.setString(4, stage.name());
            ps.setInt(5, expectedSourceVersion);
            int rows = ps.executeUpdate();
            return rows == 1;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** NCS-041: 待执行项（含来源稿版本与重试计数）——重启后仍在（SQLite 持久） */
    public List<Map<String, Object>> pendingTasks(String projectId, int chapterIndex) {
        String sql = "SELECT stage, status, COALESCE(source_version,0) AS sv, COALESCE(attempts,0) AS att " +
                "FROM post_process_ledger WHERE project_id=? AND chapter_index=? AND status!='COMPLETED'";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("stage", ChapterPostProcessor.Stage.valueOf(rs.getString("stage")));
                row.put("status", rs.getString("status"));
                row.put("sourceVersion", rs.getInt("sv"));
                row.put("attempts", rs.getInt("att"));
                out.add(row);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }


    /** 标记 RUNNING——COMPLETED 是终态，不降级 */
    public void markRunning(String projectId, int chapterIndex, ChapterPostProcessor.Stage stage) {
        update(projectId, chapterIndex, stage, StageStatus.RUNNING);
    }

    /** 标记 COMPLETED——幂等（重复标记不产生新行、不改变结果） */
    public void markCompleted(String projectId, int chapterIndex, ChapterPostProcessor.Stage stage) {
        update(projectId, chapterIndex, stage, StageStatus.COMPLETED);
    }

    private void update(String projectId, int chapterIndex,
                        ChapterPostProcessor.Stage stage, StageStatus target) {
        ensureStage(projectId, chapterIndex, stage);
        String sql = "UPDATE post_process_ledger SET status=?, updated_at=? " +
                "WHERE project_id=? AND chapter_index=? AND stage=? AND status!='COMPLETED'";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, target.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, projectId);
            ps.setInt(4, chapterIndex);
            ps.setString(5, stage.name());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Optional<StageStatus> statusOf(String projectId, int chapterIndex,
                                          ChapterPostProcessor.Stage stage) {
        String sql = "SELECT status FROM post_process_ledger WHERE project_id=? AND chapter_index=? AND stage=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setString(3, stage.name());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(StageStatus.valueOf(rs.getString("status")));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 未完成（非 COMPLETED）阶段，按声明顺序排列 */
    public List<ChapterPostProcessor.Stage> unfinishedStages(String projectId, int chapterIndex) {
        String sql = "SELECT stage, status FROM post_process_ledger WHERE project_id=? AND chapter_index=?";
        List<ChapterPostProcessor.Stage> unfinished = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (!"COMPLETED".equals(rs.getString("status"))) {
                    unfinished.add(ChapterPostProcessor.Stage.valueOf(rs.getString("stage")));
                }
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        unfinished.sort(Comparator.comparingInt(Enum::ordinal));
        return unfinished;
    }

    /** 测试/诊断：同主键行数（应恒为 0 或 1） */
    public int rowCount(String projectId, int chapterIndex, ChapterPostProcessor.Stage stage) {
        String sql = "SELECT COUNT(*) AS n FROM post_process_ledger " +
                "WHERE project_id=? AND chapter_index=? AND stage=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setString(3, stage.name());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("n") : 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 章节所有阶段已完成（或无账本行——视为无需续跑） */
    public boolean allCompleted(String projectId, int chapterIndex) {
        return unfinishedStages(projectId, chapterIndex).isEmpty();
    }
}
