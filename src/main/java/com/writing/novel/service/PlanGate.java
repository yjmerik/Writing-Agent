package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * p11-q1-04: 章节梗概预检——写正文前校验梗概质量（对应用户目标1：过程中实时审查）。
 * <p>
 * 软预检：梗概不达标（缺标题/内容/节拍）标记 quality_risk（不阻塞，重生成梗概可后续优化）。
 */
@Component
public class PlanGate {

    public record GateResult(boolean pass, List<String> issues, String feedback) {
        public static GateResult ok() { return new GateResult(true, List.of(), ""); }
    }

    public GateResult checkSynopsis(ChapterSynopsis synopsis) {
        if (synopsis == null) {
            return new GateResult(false, List.of("章节梗概为空"), "章节梗概为空，需重新生成该卷梗概。");
        }
        List<String> issues = new ArrayList<>();
        if (isBlank(synopsis.getTitle())) issues.add("缺少章节标题");
        if (isBlank(synopsis.getSynopsis())) issues.add("梗概内容为空");
        if (isBlank(synopsis.getBeats())) issues.add("缺少情节节拍（beats）");
        if (issues.isEmpty()) return GateResult.ok();
        return new GateResult(false, issues, "章节梗概质量问题：" + String.join("；", issues) + "。");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
