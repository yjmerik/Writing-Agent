package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * p15-fix: 主角锚点确定性——cast 命中的精确主角不得被 substring 匹配的配角
 * （role 含"主角"字样，如"重要配角（主角盟友线）"）抢走首位。
 * 锚点漂移会改变章节写作 prompt 指纹，破坏 record→replay 确定性。
 */
class RelevanceGatewayAnchorTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private RelevanceGateway gateway;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("anchor.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        ConnectionProvider factCp = new ConnectionProvider(tempDir.resolve("anchor_f.db").toString());
        new SqliteSchemaInitializer(factCp).initialize();
        gateway = new RelevanceGateway(repo, new StoryFactRepository(factCp));

        Project p = new Project("锚点书", "构思", "悬疑");
        repo.saveProject(p);
        pid = p.getId();

        repo.saveCharacter(new Character(pid, "陈守愚", "主角"));
        repo.saveCharacter(new Character(pid, "沈六娘", "重要配角/对手·旧秩序执秤人"));
        repo.saveCharacter(new Character(pid, "祝青崖", "重要配角（主角盟友线/独立线执灯人）"));
    }

    @Test
    void cast命中的精确主角不被substring配角抢首位() {
        List<Character> out = gateway.relevantCharacters(pid, List.of("陈守愚", "沈六娘"), 8);
        assertEquals("陈守愚", out.get(0).getName(),
                "cast 已命中精确主角时，substring 匹配的配角不得插队到首位");
    }

    @Test
    void 无cast时精确主角也在首位() {
        List<Character> out = gateway.relevantCharacters(pid, List.of(), 8);
        assertEquals("陈守愚", out.get(0).getName(),
                "无 cast 时精确主角应排首位（substring 配角不得因 add(0) 抢位）");
    }

    @Test
    void 锚点提醒行取精确主角() {
        WritingContext ctx = new WritingContext();
        ctx.setRelevantCharacters(List.of(
                new Character(pid, "祝青崖", "重要配角（主角盟友线/独立线执灯人）"),
                new Character(pid, "陈守愚", "主角")));
        String userPrompt = ctx.toUserPrompt();
        assertEquals(true, userPrompt.contains("本章主角的名字是「陈守愚」"),
                "锚点行应取精确主角陈守愚，实际：" + userPrompt.substring(0, Math.min(80, userPrompt.length())));
        assertEquals(false, userPrompt.contains("本章主角的名字是「祝青崖」"),
                "substring 配角不得成为锚点");
    }
}
