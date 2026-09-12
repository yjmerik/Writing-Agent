package com.writing.novel.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * p36-02（A4）：不可变章节批准审计——作者批准绑定明确稿件身份，DONE 不等于作者批准。
 * 批准行由原子比较提交写入（p36-03），普通章节保存不触碰本记录；
 * 正文修订只失效其有效性，永不改写历史批准的 identity。
 *
 * @param approvalId     批准记录 ID（UUID）
 * @param identity       被批准的稿件身份
 * @param reviewSetHash  批准时校验通过的待审集合指纹
 * @param idempotencyKey 作者动作幂等键（同项目唯一）
 * @param reason         作者备注，可空
 * @param createdAt      批准时间
 */
public record ChapterApproval(
        String approvalId,
        Identity identity,
        String reviewSetHash,
        String idempotencyKey,
        String reason,
        Instant createdAt) {

    /**
     * 稿件身份 (projectId, chapterIndex, version, contentHash)。
     * contentHash 只由标题与正文决定，状态/updatedAt 等元数据不参与。
     */
    public record Identity(String projectId, int chapterIndex, int version, String contentHash) {

        /** 从章节实体派生身份：哈希基于当前标题与正文。 */
        public static Identity of(Chapter chapter) {
            return new Identity(chapter.getProjectId(), chapter.getChapterIndex(),
                    chapter.getVersion(), ChapterApproval.contentHash(chapter.getTitle(), chapter.getContent()));
        }
    }

    /**
     * 作者批准请求。身份、待审集合指纹与幂等键由客户端显式携带，服务端不补填；
     * 同幂等键同请求恢复原结果，同幂等键不同请求为冲突。
     */
    public record Request(Identity identity, String reviewSetHash, String idempotencyKey, String reason) {
    }

    /** 批准结果：replayed=true 表示同幂等键重试返回原批准，不产生第二次批准。 */
    public record Result(String approvalId, Identity identity, boolean replayed) {
    }

    /**
     * 审阅上下文：批准方读取的完整待审集合快照。
     * 集合默认空——空只表示"当前没有待审项"，绝不表示"已通过"。
     *
     * @param reportCurrent        当前报告身份（含章节版本与指纹），null=无与当前稿匹配的报告
     * @param approval             与当前身份匹配的既有批准，null=当前稿尚未被作者批准
     * @param issues               预留：有效问题裁决身份（p38 提供）
     * @param dispositions         预留：承诺处置身份（p38 提供）
     * @param directives           预留：适用作者意见及证据身份（p37 提供）
     */
    public record ReviewContext(
            Identity identity,
            String reviewSetHash,
            List<String> pendingCheckpointIds,
            List<String> barrierIds,
            String reportCurrent,
            ChapterApproval approval,
            List<String> issues,
            List<String> dispositions,
            List<String> directives) {

        public ReviewContext {
            pendingCheckpointIds = normalized(pendingCheckpointIds);
            barrierIds = normalized(barrierIds);
            issues = normalized(issues);
            dispositions = normalized(dispositions);
            directives = normalized(directives);
        }

        private static List<String> normalized(List<String> input) {
            return input == null ? List.of() : List.copyOf(input);
        }
    }

    /**
     * 稿件内容指纹：SHA-256(UTF-8[标题字节长度前缀 ‖ 标题 ‖ 正文字节长度前缀 ‖ 正文])，64位小写hex。
     * 长度前缀（8字节大端）杜绝不同 (标题,正文) 切分拼接出同一输入；不做去空白等归一化。
     */
    public static String contentHash(String title, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] titleBytes = (title == null ? "" : title).getBytes(StandardCharsets.UTF_8);
            byte[] contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            md.update(toLengthPrefix(titleBytes.length));
            md.update(titleBytes);
            md.update(toLengthPrefix(contentBytes.length));
            md.update(contentBytes);
            return toHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("contentHash 计算失败", e);
        }
    }

    private static byte[] toLengthPrefix(int byteLength) {
        return new byte[]{
                (byte) (byteLength >>> 56), (byte) (byteLength >>> 48),
                (byte) (byteLength >>> 40), (byte) (byteLength >>> 32),
                (byte) (byteLength >>> 24), (byte) (byteLength >>> 16),
                (byte) (byteLength >>> 8), (byte) byteLength
        };
    }

    private static String toHex(byte[] digest) {
        // domain.Character 遮蔽 java.lang.Character，十六进制表须用查表法
        final String hexDigits = "0123456789abcdef";
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(hexDigits.charAt((b >> 4) & 0xF));
            hex.append(hexDigits.charAt(b & 0xF));
        }
        return hex.toString();
    }
}
