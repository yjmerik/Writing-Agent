package com.writing.novel.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NCS-186: 冻结 ChapterApproval.contentHash 的长度前缀字节格式。
 *
 * toLengthPrefix(int) 按 8 字节写法做了 &gt;&gt;&gt;56..&gt;&gt;&gt;0 八次位移，但 Java 对 int
 * 的位移距离取模 32，因此实际输出是「4 字节大端长度重复两遍」（共 8 字节），
 * 而不是真 8 字节大端。该格式已被库中全部已存批准/检查点身份绑定，任何"修正"
 * （改成真 be64）都会一次性作废所有存量身份——此测试就是防退化的哨兵。
 *
 * e2e.sh 侧对应教训（NCS-186）：客户端不得自行按 be64 复算哈希，必须使用
 * review-context 返回的服务端身份。
 */
class ChapterApprovalHashFormatTest {

    private static String sha256Hex(byte[] bytes) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 期望字节布局：每段前缀 = 4 字节大端长度（int 位移语义）重复两遍，随后是内容字节。 */
    private static byte[] frozenLayout(String title, String content) {
        byte[] t = (title == null ? "" : title).getBytes(StandardCharsets.UTF_8);
        byte[] c = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        int total = prefixLen(t.length) + t.length + prefixLen(c.length) + c.length;
        byte[] out = new byte[total];
        int p = 0;
        p = writePrefix(out, p, t.length);
        System.arraycopy(t, 0, out, p, t.length);
        p += t.length;
        p = writePrefix(out, p, c.length);
        System.arraycopy(c, 0, out, p, c.length);
        return out;
    }

    private static int prefixLen(int len) {
        return 8; // 4 字节大端重复两遍
    }

    /** 写入 [len>>>24, len>>>16, len>>>8, len] 重复两遍（即 int 移位语义下 >>>56..>>>0 的实际结果）。 */
    private static int writePrefix(byte[] out, int p, int len) {
        for (int rep = 0; rep < 2; rep++) {
            out[p++] = (byte) (len >>> 24);
            out[p++] = (byte) (len >>> 16);
            out[p++] = (byte) (len >>> 8);
            out[p++] = (byte) len;
        }
        return p;
    }

    @Test
    void 短输入的长度前缀是四字节大端重复两遍() {
        // len=2: 前缀 = 00 00 00 02 00 00 00 02 —— 与真 be64（00*6 + 00 02）明确区分
        assertEquals(sha256Hex(frozenLayout("AB", "CD")),
                ChapterApproval.contentHash("AB", "CD"));
    }

    @Test
    void 长度超过一字节时高位字节出现在第三第四字节并整体重复() {
        // 300 字节内容: 0x012C → 前缀 = 00 00 01 2C 00 00 01 2C
        String content = "字".repeat(150); // UTF-8 每字 3 字节 = 450 字节 → 00 00 01 C2 ×2
        assertEquals(sha256Hex(frozenLayout("标题", content)),
                ChapterApproval.contentHash("标题", content));
    }

    @Test
    void null字段按空串参与哈希() {
        assertEquals(ChapterApproval.contentHash("", ""),
                ChapterApproval.contentHash(null, null));
    }
}
