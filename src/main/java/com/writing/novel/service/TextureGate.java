package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.NarrativePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NCS-195: 双向质感门禁——写后、读者评审前的确定性检查（零 LLM 成本）。
 *
 * <p>把 StyleMetricsService.computeTexture 的段落级指标与 NarrativePolicy 区间做双向
 * （上界+下界）比对，越界落 ContinuityIssue(TEXTURE/PACING) 并分级路由：
 * <ul>
 *   <li>质感单项（对白占比双向、叙述碎片化、对白乒乓、段落过长、说明文过重）
 *       → {@code expansionTargets}：定点扩写目标，NCS-196 的 EXPAND 通道消费</li>
 *   <li>结构性越界（场景数超区间）→ {@code rewriteTargets}：既有整章重写通道消费，
 *       纠偏指令携带具体质感目标</li>
 * </ul>
 * 宽松策略（现状等效区间，dialogueParagraphRatioMax=1.0）不设防——保证快节奏题材零分叉。
 * ExpositionDetector（p11.1-J4）并入作为反向检测（叙述过重→补对白目标）。
 */
public class TextureGate {

    /** 门禁裁决：issues 需调用方持久化；expansion/rewrite 目标按各自通道路由 */
    public record Verdict(boolean pass, List<ContinuityIssue> issues,
                          List<String> expansionTargets, List<String> rewriteTargets,
                          double dialogueRatio, int pingPongCount) {}

    private final ExpositionDetector expositionDetector;

    public TextureGate() {
        this(new ExpositionDetector());
    }

    public TextureGate(ExpositionDetector expositionDetector) {
        this.expositionDetector = expositionDetector;
    }

    /** 执行门禁；宽松策略或空内容直接 PASS */
    public Verdict check(String projectId, int chapterIndex, String content, NarrativePolicy policy) {
        if (policy == null || policy.getDialogueParagraphRatioMax() >= 1.0) {
            return new Verdict(true, List.of(), List.of(), List.of(), 0, 0);
        }
        if (content == null || content.isBlank()) {
            return new Verdict(true, List.of(), List.of(), List.of(), 0, 0);
        }
        Map<String, Object> t = StyleMetricsService.computeTexture(content);
        double ratio = ((Number) t.get("dialogueParagraphRatio")).doubleValue();
        int narrationRun = ((Number) t.get("narrationRunMaxChars")).intValue();
        int sceneBreaks = ((Number) t.get("sceneBreaks")).intValue();
        int pingPong = ((Number) t.get("dialoguePingPong")).intValue();
        double avgPara = ((Number) t.get("avgParagraphLength")).doubleValue();

        List<ContinuityIssue> issues = new ArrayList<>();
        List<String> expansionTargets = new ArrayList<>();
        List<String> rewriteTargets = new ArrayList<>();

        // ① 对白段占比区间（双向）——质感单项
        if (ratio > policy.getDialogueParagraphRatioMax()) {
            String target = "对白段占比 " + Math.round(ratio * 100) + "% 超出区间上限 "
                    + Math.round(policy.getDialogueParagraphRatioMax() * 100) + "%："
                    + "在连续对白之间补足 ≥" + policy.getNarrationRunMinChars() + " 字的成段叙述（环境/动作/体感锚点）";
            addTextureIssue(issues, chapterIndex, target);
            expansionTargets.add(target);
        } else if (ratio < policy.getDialogueParagraphRatioMin()) {
            String target = "对白段占比 " + Math.round(ratio * 100) + "% 低于区间下限 "
                    + Math.round(policy.getDialogueParagraphRatioMin() * 100) + "%："
                    + "为关键叙述段补入人物对白与反应，打破单方面陈述";
            addTextureIssue(issues, chapterIndex, target);
            expansionTargets.add(target);
        }

        // ② 叙述碎片化——最长成段叙述块低于下限
        if (narrationRun < policy.getNarrationRunMinChars()) {
            String target = "最长成段叙述块 " + narrationRun + " 字低于下限 " + policy.getNarrationRunMinChars()
                    + " 字：把相邻的短叙述标签句合并扩写为成段描写（感官细节/心理/动作）";
            addTextureIssue(issues, chapterIndex, target);
            expansionTargets.add(target);
        }

        // ③ 对白乒乓（铁律 12 的确定性检测）——连续 >3 段对白无锚点行
        if (pingPong > 0) {
            String target = "检出 " + pingPong + " 处连续对白乒乓（连续 >3 段对白无锚点行）："
                    + "在第 4 段对白前补一行环境/动作/感官锚点";
            addTextureIssue(issues, chapterIndex, target);
            expansionTargets.add(target);
        }

        // ④ 段落均长超上限——需要拆段与扩写节奏
        if (policy.getParagraphMaxChars() > 0 && avgPara > policy.getParagraphMaxChars()) {
            String target = "段落均长 " + Math.round(avgPara) + " 字超出上限 " + policy.getParagraphMaxChars()
                    + " 字：拆分长段并在段内补足动作与感官节拍";
            addTextureIssue(issues, chapterIndex, target);
            expansionTargets.add(target);
        }

        // ⑤ 场景数超区间——结构性越界 → 整章重写
        if (policy.getSceneBreaksMax() > 0
                && (sceneBreaks < policy.getSceneBreaksMin() || sceneBreaks > policy.getSceneBreaksMax())) {
            String target = "场景数 " + sceneBreaks + " 超出区间 [" + policy.getSceneBreaksMin()
                    + "," + policy.getSceneBreaksMax() + "]：按章梗概节拍重组场景结构";
            issues.add(new ContinuityIssue(projectId, chapterIndex,
                    ContinuityIssue.Type.PACING, ContinuityIssue.Severity.MEDIUM, target));
            rewriteTargets.add(target);
        }

        // ⑥ ExpositionDetector 并入（反向检测：叙述过重）→ 补对白扩写目标
        try {
            Optional<ContinuityIssue> exposition = expositionDetector.detect(projectId, chapterIndex, content);
            exposition.ifPresent(issue -> {
                issues.add(issue);
                expansionTargets.add("说明文密度过高：插入角色对话与反应打断连续说明段落");
            });
        } catch (Exception ignored) {
            // 反向检测失败不阻断门禁
        }

        return new Verdict(issues.isEmpty(), List.copyOf(issues), List.copyOf(expansionTargets),
                List.copyOf(rewriteTargets), ratio, pingPong);
    }

    private void addTextureIssue(List<ContinuityIssue> issues, int chapterIndex, String description) {
        issues.add(new ContinuityIssue(null, chapterIndex,
                ContinuityIssue.Type.TEXTURE, ContinuityIssue.Severity.MEDIUM, description));
    }
}
