package com.writing.novel.memory;

import java.util.List;

/**
 * NCS-114: 召回使用反馈回路——注入清单（NCS-096）× FactHarvester 抽取结果交叉，
 * 计算「注入的约束是否被正文实际引用」的命中率，打破 VALIDATED-recency 马太效应：
 * <ul>
 *   <li>FACT 命中 = 抽取到的主体与注入清单 FACT 条目主体一致（被复述/再次确认）</li>
 *   <li>低命中（有 ≥3 章样本且 &lt; 0.2）→ 建议真典注入配额减半（宁缺毋滥）</li>
 *   <li>探索配额在 {@link RelevanceGateway} 侧实现（长窗未注入的高重要度事实保底）</li>
 * </ul>
 * 统计持久化经 {@link Store}（persistence 实现），memory 层不依赖存储细节。
 */
public class RecallFeedbackService {

    /** 单类目命中率的最低样本章数（不足不下结论） */
    static final int MIN_SAMPLES = 3;
    /** 低于此命中率建议配额减半 */
    static final double LOW_HIT_RATE = 0.2;

    public interface Store {
        void save(String projectId, int chapterIndex, String kind, int injected, int hit);

        /** 聚合：[cumulativeInjected, cumulativeHit] */
        long[] aggregate(String projectId, String kind);
    }

    private final Store store;

    public RecallFeedbackService(Store store) {
        this.store = store;
    }

    /**
     * 记录一章的收割结果对注入清单的命中。
     * @param manifest    本章注入清单（null 跳过——旧装配兼容）
     * @param harvestedSubjects 收取到的事实主体（含 ADDED/VALIDATED 等）
     */
    public void recordHarvestOutcome(String projectId, int chapterIndex,
                                     InjectionManifest manifest, List<String> harvestedSubjects) {
        if (store == null || manifest == null || harvestedSubjects == null) return;
        List<InjectionManifest.Entry> factEntries = manifest.entriesOfKind(InjectionManifest.Kind.FACT);
        if (factEntries.isEmpty()) return;
        int hit = 0;
        for (var entry : factEntries) {
            String subject = entry.label == null ? "" : entry.label.split("：")[0];
            if (!subject.isBlank() && harvestedSubjects.stream()
                    .anyMatch(s -> s != null && subject.equals(s.trim()))) {
                hit++;
            }
        }
        store.save(projectId, chapterIndex, "FACT", factEntries.size(), hit);
    }

    /** 累计命中率（样本不足返回 -1） */
    public double hitRate(String projectId, String kind) {
        if (store == null) return -1;
        long[] agg = store.aggregate(projectId, kind);
        if (agg[0] < MIN_SAMPLES) return -1;
        return (double) agg[1] / agg[0];
    }

    /**
     * 建议真典注入 cap：低命中（≥3 章样本且 &lt; 0.2）减半——注入但从不被正文引用的
     * 事实大概率是噪声；命中恢复后自动回到默认。
     */
    public int recommendedFactCap(String projectId, int defaultCap) {
        double rate = hitRate(projectId, "FACT");
        if (rate >= 0 && rate < LOW_HIT_RATE) return Math.max(8, defaultCap / 2);
        return defaultCap;
    }
}
