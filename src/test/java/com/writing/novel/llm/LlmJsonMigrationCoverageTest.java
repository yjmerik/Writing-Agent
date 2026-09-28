package com.writing.novel.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-106: 结构化解析全量收口——生产代码（agent/ 与 service/）不得再有手写
 * 「剥 JSON 围栏 / 直连 readTree」解析点，统一走 LlmJson（新增解析必须走中心组件，
 * 本测试固化防回潮）。
 */
class LlmJsonMigrationCoverageTest {

    private static final Pattern FENCE_STRIP = Pattern.compile("replaceAll\\(\"\\^```");
    private static final Pattern READ_TREE = Pattern.compile("\\.readTree\\(");

    @Test
    void 生产代码无手写围栏剥离_LlmJson唯一() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/writing/novel"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                if (p.endsWith("llm/LlmJson.java")) return;
                try {
                    List<String> lines = Files.readAllLines(p);
                    for (int i = 0; i < lines.size(); i++) {
                        if (FENCE_STRIP.matcher(lines.get(i)).find()) {
                            offenders.add(p + ":" + (i + 1));
                        }
                    }
                } catch (IOException ignored) {}
            });
        }
        assertTrue(offenders.isEmpty(),
                "围栏剥离只允许存在于 LlmJson，发现手写点: " + offenders);
    }

    @Test
    void agent与service层无直连readTree() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String dir : List.of("src/main/java/com/writing/novel/agent",
                "src/main/java/com/writing/novel/service")) {
            try (Stream<Path> files = Files.walk(Path.of(dir))) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        List<String> lines = Files.readAllLines(p);
                        for (int i = 0; i < lines.size(); i++) {
                            if (READ_TREE.matcher(lines.get(i)).find()) {
                                offenders.add(p + ":" + (i + 1));
                            }
                        }
                    } catch (IOException ignored) {}
                });
            }
        }
        assertTrue(offenders.isEmpty(),
                "agent/service 层 JSON 解析必须走 LlmJson（readTree 直连禁止）: " + offenders);
    }

    @Test
    void 围栏剥离支持任意语言标记() {
        assertEquals("编辑后的段落", LlmJson.stripFences("```markdown\n编辑后的段落\n```"));
        assertEquals("{\"a\":1}", LlmJson.stripFences("```json\n{\"a\":1}\n```"));
    }
}
