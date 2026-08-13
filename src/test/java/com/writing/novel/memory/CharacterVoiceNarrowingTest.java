package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p10-w2-05: 检查器收窄——buildCharacterVoiceSection 经 RelevanceGateway 收窄，
 * 不再 cast 过滤失败即回退全量（根因 A：百万字下角色全量注入致 token 爆炸）。
 */
class CharacterVoiceNarrowingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("voice.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("voice书", "构思", "玄幻");
        repo.saveProject(p);
        pid = p.getId();

        // 角色：主角 + 配角(本章 cast) + 无关龙套，都有 speakingStyle
        Character protagonist = new Character(pid, "季衡", "主角");
        protagonist.setSpeakingStyle("沉稳简练");
        repo.saveCharacter(protagonist);
        Character shen = new Character(pid, "沈墨", "配角");
        shen.setSpeakingStyle("阴冷多疑");
        repo.saveCharacter(shen);
        Character mob = new Character(pid, "路人甲", "龙套");
        mob.setSpeakingStyle("絮叨"); // 无关角色，不应被注入本章
        repo.saveCharacter(mob);

        // 大纲：第 1 章 cast 含沈墨
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "第一卷", "卷摘要");
        ChapterSynopsis syn = new ChapterSynopsis(pid, 1, "第一章", "梗概");
        syn.setCast("沈墨");
        v.getSynopses().add(syn);
        outline.getVolumes().add(v);
        repo.saveOutline(outline);

        // p10-w2-05: 启用角色声音注入（buildCharacterVoiceSection 前置条件）
        com.writing.novel.domain.WritingPreferences prefs = new com.writing.novel.domain.WritingPreferences(pid);
        prefs.setEnableCharacterVoice(true);
        prefs.setDialogueDifferentiation("contrast");
        repo.saveWritingPreferences(prefs);
    }

    private ContextAssembler assemblerWith(StoryFactRepository factRepo, RelevanceGateway gateway) {
        VectorRetrievalService vr = mock(VectorRetrievalService.class);
        when(vr.search(anyString(), anyString(), anyInt())).thenReturn(java.util.List.of());
        com.writing.novel.service.EntityGraphService eg = mock(com.writing.novel.service.EntityGraphService.class);
        when(eg.recallConsistencySection(anyString(), anyInt())).thenReturn("");
        com.writing.novel.service.ClimaxDiversityTracker cl = mock(com.writing.novel.service.ClimaxDiversityTracker.class);
        when(cl.generateRotationConstraint()).thenReturn("");
        com.writing.novel.service.NarrativeThreadTracker nt = mock(com.writing.novel.service.NarrativeThreadTracker.class);
        when(nt.getThreadSection(anyString())).thenReturn("");
        com.writing.novel.service.DiversityTracker dv = mock(com.writing.novel.service.DiversityTracker.class);
        when(dv.generateAllConstraints()).thenReturn("");
        com.writing.novel.service.ClicheDetector cd = mock(com.writing.novel.service.ClicheDetector.class);
        when(cd.generateForbiddenList(anyString(), anyInt())).thenReturn("");
        ContextAssembler a = new ContextAssembler(repo, new KnowledgeBaseService(repo, vr),
                mock(SummaryService.class), vr, eg, cl, nt, dv, cd, null);
        if (factRepo != null) a.setStoryFactRepository(factRepo);
        if (gateway != null) a.setRelevanceGateway(gateway);
        return a;
    }

    @Test
    void 注入RelevanceGateway后角色声音不回退全量_无关龙套不注入() {
        StoryFactRepository factRepo = new StoryFactRepository(
                new ConnectionProvider(tempDir.resolve("voice_f.db").toString()));
        // schema 已初始化（共享？）——用新连接但同库可能未初始化，单独初始化
        new SqliteSchemaInitializer(new ConnectionProvider(tempDir.resolve("voice_f.db").toString())).initialize();
        RelevanceGateway gateway = new RelevanceGateway(repo, factRepo);
        ContextAssembler a = assemblerWith(factRepo, gateway);

        WritingContext ctx = a.assemble(pid, 1, "第一章", "梗概", null);
        String voice = ctx.getCharacterVoiceSection();
        assertNotNull(voice);
        assertTrue(voice.contains("沈墨"), "应含 cast 命中的沈墨");
        assertTrue(voice.contains("季衡"), "主角保底应含季衡");
        assertFalse(voice.contains("路人甲"), "无关龙套不应注入（不回退全量）");
    }

    @Test
    void 未注入gateway时回退旧路径_向后兼容() {
        ContextAssembler a = assemblerWith(null, null); // 无 gateway
        WritingContext ctx = a.assemble(pid, 1, "第一章", "梗概", null);
        // 旧路径：cast 过滤，沈墨命中；季衡是否在取决于 cast（旧逻辑主角不保底）
        String voice = ctx.getCharacterVoiceSection();
        assertTrue(voice.contains("沈墨"), "旧路径 cast 过滤应含沈墨");
    }
}
