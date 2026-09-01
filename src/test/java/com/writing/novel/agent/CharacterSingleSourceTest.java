package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * p11-q0-03: 人物单轨化——可变状态由真典承载（消除 Character 卡漂移双轨）。
 */
class CharacterSingleSourceTest {

    private static LlmGateway llmReturning(String content) {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse(content, 10, 10); }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    private static Chapter chapterWith(String name) {
        Chapter ch = new Chapter("p", 1, "测试章");
        ch.setContent(name + " 出场片段，展开情节。".repeat(10));
        return ch;
    }

    @Test
    void arcUpdater_成长弧演进同步写真典CHARACTER_STATE() {
        NovelRepository repo = mock(NovelRepository.class);
        StoryFactRepository factRepo = mock(StoryFactRepository.class);
        var mc = new Character("p", "季衡", "主角");
        mc.setGrowthArc("初出茅庐");
        when(repo.findCharactersByProject("p")).thenReturn(List.of(mc));
        when(repo.findRecentChapters(eq("p"), anyInt())).thenReturn(List.of(chapterWith("季衡")));
        when(factRepo.findActiveBySubject("p", "季衡")).thenReturn(List.of());

        var updater = new ArcUpdater(llmReturning("【成长弧】筑基大成，剑意初成"),
                repo, TestPromptHelper.create(), new SqliteStoryCanon(factRepo));
        updater.updateArcs("p", 10);

        verify(factRepo).save(argThat(f -> f.getFactType() == StoryFact.FactType.CHARACTER_STATE
                && f.getSubject().equals("季衡")
                && f.getAssertion().contains("筑基大成")));
    }

    @Test
    void arcUpdater_成长弧无变化时刷新确认章不新建() {
        NovelRepository repo = mock(NovelRepository.class);
        StoryFactRepository factRepo = mock(StoryFactRepository.class);
        var mc = new Character("p", "季衡", "主角");
        mc.setGrowthArc("筑基大成");
        var existing = new StoryFact("p", StoryFact.FactType.CHARACTER_STATE, "季衡", "成长弧：筑基大成", 5);
        when(repo.findCharactersByProject("p")).thenReturn(List.of(mc));
        when(repo.findRecentChapters(eq("p"), anyInt())).thenReturn(List.of(chapterWith("季衡")));
        when(factRepo.findActiveBySubject("p", "季衡")).thenReturn(List.of(existing));

        var updater = new ArcUpdater(llmReturning("【成长弧】筑基大成"),
                repo, TestPromptHelper.create(), new SqliteStoryCanon(factRepo));
        updater.updateArcs("p", 10);

        // 无变化：刷新 existing（validateAt），不新建、不 supersede
        verify(factRepo).save(existing);
        verify(factRepo, never()).supersede(any(), any());
    }

    @Test
    void characterProfileUpgrader_建档播种初始真典状态() {
        NovelRepository repo = mock(NovelRepository.class);
        StoryFactRepository factRepo = mock(StoryFactRepository.class);
        RelevanceGateway gw = mock(RelevanceGateway.class);
        var entity = new Entity("p", "新配角", EntityType.CHARACTER);
        entity.seenAt(3);
        entity.seenAt(5);
        when(repo.findEntitiesByProject("p")).thenReturn(List.of(entity));
        when(repo.findCharactersByProject("p")).thenReturn(List.of());
        when(repo.findRecentChapters(eq("p"), anyInt())).thenReturn(List.of(chapterWith("新配角")));
        when(repo.findWritingPreferences(any())).thenReturn(Optional.empty());
        when(factRepo.findActiveBySubject("p", "新配角")).thenReturn(List.of());
        when(gw.relevantFacts(any(), any(), anyInt())).thenReturn(List.of());

        String profile = "【角色定位】配角\n【性格】沉稳\n【能力】剑术初成\n【动机】报恩\n";
        var upgrader = new CharacterProfileUpgrader(llmReturning(profile),
                repo, TestPromptHelper.create(), gw, new SqliteStoryCanon(factRepo));
        upgrader.upgrade("p", 5);

        verify(factRepo).save(argThat(f -> f.getSubject().equals("新配角")
                && f.getAssertion().contains("初始能力")
                && f.getFactType() == StoryFact.FactType.CHARACTER_STATE));
    }

    @Test
    void characterProfileUpgrader_已有真典状态则不覆盖() {
        NovelRepository repo = mock(NovelRepository.class);
        StoryFactRepository factRepo = mock(StoryFactRepository.class);
        RelevanceGateway gw = mock(RelevanceGateway.class);
        var entity = new Entity("p", "旧配角", EntityType.CHARACTER);
        entity.seenAt(3);
        entity.seenAt(5);
        var existingFact = new StoryFact("p", StoryFact.FactType.CHARACTER_STATE, "旧配角", "已演进状态", 4);
        when(repo.findEntitiesByProject("p")).thenReturn(List.of(entity));
        when(repo.findCharactersByProject("p")).thenReturn(List.of());
        when(repo.findRecentChapters(eq("p"), anyInt())).thenReturn(List.of(chapterWith("旧配角")));
        when(repo.findWritingPreferences(any())).thenReturn(Optional.empty());
        when(factRepo.findActiveBySubject("p", "旧配角")).thenReturn(List.of(existingFact));
        when(gw.relevantFacts(any(), any(), anyInt())).thenReturn(List.of());

        var upgrader = new CharacterProfileUpgrader(llmReturning("【角色定位】配角\n【能力】新能力\n"),
                repo, TestPromptHelper.create(), gw, new SqliteStoryCanon(factRepo));
        upgrader.upgrade("p", 5);

        // 已有真典状态：不覆盖（避免重建档覆盖演进）
        verify(factRepo, never()).save(any(StoryFact.class));
    }
}
