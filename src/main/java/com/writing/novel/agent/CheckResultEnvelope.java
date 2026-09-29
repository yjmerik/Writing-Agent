package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NCS-007（方案 §08/§13/R09/F10）：检查结果信封。
 *
 * <p>检测失败与"没有问题"必须区分——未执行、解析失败、空响应、截断、拒绝
 * 分别落为非通过状态，不能伪装成 VALID_EMPTY（空问题列表）自动 PASS。
 * SUCCESS：解析出 ≥1 条问题；VALID_EMPTY：输出明确声明无问题；INVALID：非空但不可解析；UNKNOWN：无法判定。
 * AUTHOR_WAIVED（NCS-088）：作者对未查清项担责豁免——版本化批准自动追加或经 check-waiver 手动豁免，
 * 不可变追加行覆盖最新状态，不计入 unknownCount/未查清，但保留审计追溯。
 */
public record CheckResultEnvelope(Status status, List<ContinuityIssue> issues, String reason) {

    public enum Status { SUCCESS, VALID_EMPTY, INVALID, UNKNOWN, AUTHOR_WAIVED }

    private static final Pattern ISSUE_PATTERN = Pattern.compile("【([^|]+)\\|([^】]+)】(.+)");
    private static final List<String> CLEAN_MARKERS = List.of("未发现", "无问题", "无矛盾", "未发现连续性");

    public static CheckResultEnvelope of(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new CheckResultEnvelope(Status.UNKNOWN, List.of(), "空响应或无输出");
        }
        List<ContinuityIssue> issues = new ArrayList<>();
        Matcher m = ISSUE_PATTERN.matcher(rawContent);
        while (m.find()) {
            issues.add(new ContinuityIssue("pending", 0,
                    parseType(m.group(1).trim()), parseSeverity(m.group(2).trim()), m.group(3).trim()));
        }
        if (!issues.isEmpty()) {
            // 临时占位 projectId/chapterIndex 由调用方重建（信封只做分类与文本抽取）
            return new CheckResultEnvelope(Status.SUCCESS, issues, "");
        }
        // NCS-121: VALID_EMPTY 收紧——输出必须"整体是干净声明"（短文本且含干净标记）才判无问题；
        // 旧逻辑仅 contains 标记，混合输出（第1项未发现+其余2项真问题但未按格式）会被吞成干净
        String trimmed = rawContent.trim();
        boolean cleanStatement = trimmed.length() <= 30
                && CLEAN_MARKERS.stream().anyMatch(trimmed::contains);
        if (cleanStatement) {
            return new CheckResultEnvelope(Status.VALID_EMPTY, List.of(), "");
        }
        return new CheckResultEnvelope(Status.INVALID, List.of(),
                "检查输出不可解析（截断/协议错误/拒绝/混合输出），不能当作无问题");
    }

    private static ContinuityIssue.Type parseType(String s) {
        return switch (s) {
            case "人物" -> ContinuityIssue.Type.CHARACTER;
            case "世界观" -> ContinuityIssue.Type.WORLDBUILDING;
            case "时间线" -> ContinuityIssue.Type.TIMELINE;
            case "伏笔" -> ContinuityIssue.Type.FORESHADOWING;
            case "文风" -> ContinuityIssue.Type.STYLE;
            case "结构" -> ContinuityIssue.Type.STRUCTURE;   // NCS-121: 枚举对齐 prompt 承诺
            case "节奏" -> ContinuityIssue.Type.PACING;
            case "密度" -> ContinuityIssue.Type.DENSITY;
            default -> ContinuityIssue.Type.WORLDBUILDING;
        };
    }

    private static ContinuityIssue.Severity parseSeverity(String s) {
        return switch (s) {
            case "高" -> ContinuityIssue.Severity.HIGH;
            case "中" -> ContinuityIssue.Severity.MEDIUM;
            // NCS-121: prompt 承诺的「已回收」——按 LOW 解析且描述保留原词（统计不再静默扭曲）
            case "已回收" -> ContinuityIssue.Severity.LOW;
            default -> ContinuityIssue.Severity.LOW;
        };
    }
}
