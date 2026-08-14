package com.writing.novel.service;

import com.writing.novel.spi.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * p10-w3-03: 归档衰减器——卷末清理无界增长的派生数据（根因 B：图表只增不减）。
 * - 过期实体边物理删除（validTo<当前章-5）：RelationshipEvolutionTracker 标 validTo 但不删，百万字下边表数万行
 * - 旧的非 HIGH 连续性问题归档（detected_at<当前章-20）：feedback 段不再注入陈年噪声
 * 减小"活跃集"，让全量查询（findEdgesByProject/findUnresolvedIssues）在百万字下可控。
 */
@Component
public class ArchiveCompactor {

    private static final Logger log = LoggerFactory.getLogger(ArchiveCompactor.class);
    /** 过期边阈值：validTo 早于 (当前章 - EDGE_KEEP_WINDOW) 的删除 */
    private static final int EDGE_KEEP_WINDOW = 5;
    /** 旧问题归档阈值：detected_at 早于 (当前章 - ISSUE_KEEP_WINDOW) 的非 HIGH 归档 */
    private static final int ISSUE_KEEP_WINDOW = 20;

    private final NovelRepository repository;

    public ArchiveCompactor(NovelRepository repository) {
        this.repository = repository;
    }

    public record CompactResult(int edgesDeleted, int issuesArchived) {}

    /** 卷末调用：清理过期边 + 旧问题 */
    public CompactResult compact(String projectId, int currentChapter) {
        try {
            int edges = repository.deleteExpiredEdges(projectId, currentChapter - EDGE_KEEP_WINDOW);
            int issues = repository.archiveOldLowSeverityIssues(projectId, currentChapter - ISSUE_KEEP_WINDOW);
            if (edges > 0 || issues > 0) {
                log.info("归档衰减(项目{}, 当前章{}): 删除过期边 {} 条, 归档旧问题 {} 条",
                        projectId, currentChapter, edges, issues);
            }
            return new CompactResult(edges, issues);
        } catch (Exception e) {
            log.warn("归档衰减失败(项目{}): {}", projectId, e.getMessage());
            return new CompactResult(0, 0);
        }
    }
}
