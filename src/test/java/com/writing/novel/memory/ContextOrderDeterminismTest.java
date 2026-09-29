package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * p15-fix: assembleRetrievedContext 的 relevantCharacters 顺序必须规范化（按角色卡全集顺序），
 * 不得继承向量检索的相似度排序——平局时排序不稳定会漂移章节 prompt 指纹，破坏 record→replay 确定性。
 */
class ContextOrderDeterminismTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;
    private VectorRetrievalService vr;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ord.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        vr = mock(VectorRetrievalService.class);
        when(vr.search(anyString(), anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        Project p = new Project("顺序书", "构思", "悬疑");
        repo.saveProject(p);
        pid = p.getId();

        repo.saveCharacter(new Character(pid, "陈守愚", "主角"));
        repo.saveCharacter(new Character(pid, "沈六娘", "重要配角/对手·旧秩序执秤人"));
        repo.saveCharacter(new Character(pid, "祝青崖", "重要配角（主角盟友线/独立线执灯人）"));
    }

    @Test
    void 检索命中顺序不影响relevantCharacters规范序() {
        String mengYaiId = idOf("祝青崖");
        String shenId = idOf("沈六娘");
        when(vr.search(anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new VectorSearchResult(mengYaiId, 0.9),
                                    new VectorSearchResult(shenId, 0.8)));

        ContextAssembler assembler;
        {
            var cl = mock(com.writing.novel.service.ClimaxDiversityTracker.class);
            when(cl.generateRotationConstraint()).thenReturn("");
            var nt = mock(com.writing.novel.service.NarrativeThreadTracker.class);
            when(nt.getThreadSection(anyString())).thenReturn("");
            var dv = mock(com.writing.novel.service.DiversityTracker.class);
            when(dv.generateAllConstraints()).thenReturn("");
            var cd = mock(com.writing.novel.service.ClicheDetector.class);
            when(cd.generateForbiddenList(anyString(), anyInt())).thenReturn("");
            assembler = new ContextAssembler(repo,
                    new KnowledgeBaseService(repo, vr), mock(SummaryService.class), vr,
                    mock(EntityGraphService.class), cl, nt, dv, cd, null);
        }

        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "梗概", null);
        List<String> names = ctx.getRelevantCharacters().stream().map(Character::getName).toList();
        assertEquals(List.of("陈守愚", "沈六娘", "祝青崖"), names,
                "relevantCharacters 必须按角色卡全集顺序规范化，不随检索排序漂移");
    }

    private String idOf(String name) {
        return repo.findCharactersByProject(pid).stream()
                .filter(c -> name.equals(c.getName()))
                .map(Character::getId)
                .findFirst().orElseThrow(() -> new IllegalStateException(name + " not found"));
    }
}
