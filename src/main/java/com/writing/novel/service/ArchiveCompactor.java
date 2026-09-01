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
    /** p16-01: HIGH 老化阈值：detected_at 早于 (当前章 - HIGH_AGING_WINDOW) 的未复发 HIGH 归档（复发会产生新行，不丢问题） */
    private static final int HIGH_AGING_WINDOW = 30;
    /** p11-q0-06: 悬置伏笔阈值：planted 早于 (当前章 - FORESHADOW_STALE_WINDOW) 的非关键 LOW 伏笔放弃 */
    private static final int FORESHADOW_STALE_WINDOW = 30;

    private final NovelRepository repository;

    public ArchiveCompactor(NovelRepository repository) {
        this.repository = repository;
    }

    /** p11-q0-06: 扩展为 5 类归档（W3-03 只做 2/5：edges/issues） */
    public record CompactResult(int edgesDeleted, int issuesArchived, int threadsDeleted,
                                int knowledgeDeduped, int foreshadowingsRetired) {}

    /** 卷末调用：清理过期边 + 旧问题 + 已回收线索 + 重名知识 + 悬置伏笔（p11-q0-06 补齐 5 类） */
    public CompactResult compact(String projectId, int currentChapter) {
        try {
            int edges = repository.deleteExpiredEdges(projectId, currentChapter - EDGE_KEEP_WINDOW);
            int issues = repository.archiveOldLowSeverityIssues(projectId, currentChapter - ISSUE_KEEP_WINDOW);
            // p16-01: 历史未复发 HIGH 老化——否则 400 章残留 HIGH 必超 FINAL 门禁阈值且无自动收敛
            int agedHigh = repository.archiveAgedHighIssues(projectId, currentChapter - HIGH_AGING_WINDOW);
            if (agedHigh > 0) {
                log.info("HIGH 老化(项目{}, 当前章{}): {} 条 30 章前未复发 HIGH 归档", projectId, currentChapter, agedHigh);
            }
            // p11-q0-06: 补齐 foreshadowings/narrative_threads/knowledge_entries 归档
            int threads = repository.deleteResolvedNarrativeThreads(projectId);
            int knowledge = repository.deleteDuplicateKnowledgeEntries(projectId);
            int foreshadowings = repository.retireStaleForeshadowings(projectId, currentChapter - FORESHADOW_STALE_WINDOW);
            if (edges + issues + threads + knowledge + foreshadowings > 0) {
                log.info("归档衰减(项目{}, 当前章{}): 过期边 {}, 旧问题 {}, 已回收线索 {}, 重名知识 {}, 悬置伏笔 {}",
                        projectId, currentChapter, edges, issues, threads, knowledge, foreshadowings);
            }
            return new CompactResult(edges, issues, threads, knowledge, foreshadowings);
        } catch (Exception e) {
            log.warn("归档衰减失败(项目{}): {}", projectId, e.getMessage());
            return new CompactResult(0, 0, 0, 0, 0);
        }
    }
}
