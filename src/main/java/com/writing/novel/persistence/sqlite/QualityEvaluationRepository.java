package com.writing.novel.persistence.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * p32-11（UI 验证发现，体验报告 #7）：全书质量评价历史持久化。
 * POST /evaluate 的结果落库，GET /evaluate 读回最近一次——
 * 修复前端切 tab/刷新后评价丢失（「尚未评价」）的问题。
 */
@org.springframework.stereotype.Component
public class QualityEvaluationRepository {

    private final ConnectionProvider cp;
    private final ObjectMapper mapper;

    public QualityEvaluationRepository(ConnectionProvider cp, ObjectMapper mapper) {
        this.cp = cp;
        this.mapper = mapper;
    }

    /** 追加一条评价历史，返回落库时间戳。 */
    public Instant save(String projectId, int overallScore, Map<String, Object> report) {
        Instant createdAt = Instant.now();
        String sql = "INSERT INTO quality_evaluations (id, project_id, overall_score, report_json, created_at) " +
                "VALUES (?,?,?,?,?)";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, projectId);
            ps.setInt(3, overallScore);
            ps.setString(4, mapper.writeValueAsString(report));
            ps.setString(5, createdAt.toString());
            ps.executeUpdate();
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return createdAt;
    }

    /** 最近一次评价（report + evaluatedAt），无记录返回 empty。 */
    public Optional<Map<String, Object>> findLatest(String projectId) {
        String sql = "SELECT report_json, created_at FROM quality_evaluations " +
                "WHERE project_id=? ORDER BY created_at DESC LIMIT 1";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Map<String, Object> report = mapper.readValue(rs.getString("report_json"),
                        new TypeReference<Map<String, Object>>() {});
                return Optional.of(Map.of(
                        "evaluation", report,
                        "evaluatedAt", rs.getString("created_at")));
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
