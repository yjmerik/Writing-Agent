package com.writing.novel.orchestrator;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FailLoudTest {

    @Test
    void 复检与纠偏重写路径不含ignored吞异常() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java"));
        int start = src.indexOf("private boolean checkContinuityAfterWriting");
        int rewrite = src.indexOf("private void rewriteWithCorrection");
        int afterRewrite = src.indexOf("private void applyRepairClassification");
        assertTrue(start > 0 && rewrite > start && afterRewrite > rewrite);
        String checkMethod = src.substring(start, rewrite);
        String rewriteMethod = src.substring(rewrite, afterRewrite);
        assertFalse(checkMethod.contains("catch (Exception ignored)"),
                "checkContinuityAfterWriting 复检路径不得 catch (Exception ignored)");
        assertFalse(rewriteMethod.contains("catch (Exception ignored)"),
                "rewriteWithCorrection 不得 catch (Exception ignored)");
        assertTrue(src.contains("DETECT_FAILED"),
                "失败必须 emit CONSISTENCY_ISSUE DETECT_FAILED");
    }
}
