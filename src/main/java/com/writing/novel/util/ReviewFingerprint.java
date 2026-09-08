package com.writing.novel.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** p32-04/p32-05: 内容短指纹——计划出处与审核产物绑定的正文/梗概标识。 */
public final class ReviewFingerprint {

    private ReviewFingerprint() {}

    /** SHA-256 前 6 字节（12 位 hex）——足够区分计划/正文版本变更，不用于安全场景。 */
    public static String sha256Hex12(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
