package com.writing.novel.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-136: AgentHarness 死代码清理——宣称通用 harness 但生产零消费
 * （各 Agent 手写重试行为发散，与 NCS-129 单一重试所有权冲突）。
 * 固化：类已删除且 src/main 无引用。
 */
class AgentHarnessRemovalTest {

    @Test
    void 生产代码无AgentHarness残留() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            assertTrue(files.filter(p -> p.toString().endsWith(".java"))
                    .noneMatch(p -> p.getFileName().toString().equals("AgentHarness.java")),
                    "AgentHarness.java 应已删除");
        }
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            boolean referenced = files.filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(p -> {
                        try {
                            return Files.readString(p).contains("AgentHarness");
                        } catch (IOException e) { return false; }
                    });
            assertFalse(referenced, "src/main 无 AgentHarness 引用");
        }
    }
}
