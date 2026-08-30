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
        String sql = "INSERT INTO post_process_ledger (project_id, chapter_index, stage, status, updated_at) " +
                "VALUES (?,?,?,'PENDING',?) ON CONFLICT(project_id, chapter_index, stage) DO NOTHING";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setString(3, stage.name());
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
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
