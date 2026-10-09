package com.writing.novel.agent;

import com.writing.novel.domain.Outline;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Volume;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlotterAgentTest {

    /** NCS-188: 节拍数超过上限时合并裁剪并事件化（计划侧节奏算术） */
    @Test
    void 节拍数超上限被合并裁剪并事件化() {
        PlotterAgent agent = new PlotterAgent(null, new TestNovelRepositoryStub(), TestPromptHelper.create());
        ChapterSynopsis cs = new ChapterSynopsis("proj-1", 7, "测试章", "梗概");
        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        com.writing.novel.telemetry.EventLog.Listener capture = record -> {
            if ("OUTLINE_BEATS_TRIMMED".equals(record.get("event"))) events.add(record);
        };
        com.writing.novel.telemetry.EventLog.subscribe(capture);
        try {
            agent.parseChapterDetails(cs,
                    "场景节拍：金殿对质→袖中密折→夜宴交锋→丙夜纵火→城门截信→禅位遗诏。爽点：密折翻案。人物：主角。目的：夺嫡转折。张力：9。",
                    3);
        } finally {
            com.writing.novel.telemetry.EventLog.unsubscribe(capture);
        }
        String[] parts = cs.getBeats().split("→");
        assertEquals(3, parts.length, "6 节拍超出上限 3 应合并为 3 段");
        assertEquals(1, events.size(), "应发出一条 OUTLINE_BEATS_TRIMMED 事件");
        assertEquals(7, events.get(0).get("chapterIndex"));
        assertEquals(6, events.get(0).get("beatsBefore"));
        assertEquals(3, events.get(0).get("beatsAfter"));
    }

    /** NCS-188: 节拍数在限内原样保留、不发事件；maxBeats<=0 表示不限制 */
    @Test
    void 节拍数在限内不裁剪() {
        PlotterAgent agent = new PlotterAgent(null, new TestNovelRepositoryStub(), TestPromptHelper.create());
        ChapterSynopsis cs = new ChapterSynopsis("proj-1", 2, "测试章", "梗概");
        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        com.writing.novel.telemetry.EventLog.Listener capture = record -> {
            if ("OUTLINE_BEATS_TRIMMED".equals(record.get("event"))) events.add(record);
        };
        com.writing.novel.telemetry.EventLog.subscribe(capture);
        try {
            agent.parseChapterDetails(cs,
                    "场景节拍：入城→探监→对峙。爽点：翻供。人物：主角。目的：真相。张力：7。", 3);
            assertEquals(3, cs.getBeats().split("→").length);
            agent.parseChapterDetails(cs,
                    "场景节拍：甲→乙→丙→丁→戊。爽点：x。人物：y。目的：z。张力：5。", 0);
            assertEquals(5, cs.getBeats().split("→").length, "maxBeats=0 不限制");
        } finally {
            com.writing.novel.telemetry.EventLog.unsubscribe(capture);
        }
        assertEquals(0, events.size(), "限内与不限制都不应发裁剪事件");
    }

    @Test
    void proposeVolumeRevisionIncludesInstructionsWithoutPersistingCandidate() {
        class RecordingGateway implements LlmGateway {
            LlmRequest request;
            @Override public LlmResponse complete(LlmRequest request) {
                this.request = request;
                return new LlmResponse("""
                        第2章 账册失踪：场景节拍：盘点→追查。人物：主角。目的：查案。张力：7。
                        第3章 夜访证人：场景节拍：跟踪→问证。人物：主角。目的：取证。张力：8。
                        """, 10, 10);
            }
            @Override public void streamComplete(LlmRequest request, StreamHandler handler) { }
        }
        AtomicInteger saves = new AtomicInteger();
        var repository = new TestNovelRepositoryStub() {
            @Override public void saveOutline(Outline outline) { saves.incrementAndGet(); }
        };
        RecordingGateway gateway = new RecordingGateway();
        PlotterAgent agent = new PlotterAgent(gateway, repository, TestPromptHelper.create());
        Volume source = new Volume("proj-1", 1, "旧卷", "旧梗概");
        source.addSynopsis(new ChapterSynopsis("proj-1", 1, "旧一", "已写"));
        source.addSynopsis(new ChapterSynopsis("proj-1", 2, "旧二", "未写二"));
        source.addSynopsis(new ChapterSynopsis("proj-1", 3, "旧三", "未写三"));

        Volume candidate = agent.proposeVolumeRevision(
                "proj-1", "全书总纲", source, 1, "把第二章改为调查账册失踪案");

        assertEquals(2, candidate.getSynopses().size());
        assertTrue(gateway.request.getUserMessage().contains("把第二章改为调查账册失踪案"));
        assertEquals(0, saves.get(), "candidate generation must not persist before validation");
    }

    @Test
    void 生成全书大纲与卷() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("""
                    【总纲】张三从凡人少年成长为剑道宗师，期间经历灭门、拜师、复仇、悟道，最终证道飞升。

                    【卷1】初入仙门
                    张三家破人亡，机缘巧合拜入青云宗，开始修炼之路。
                    第1章 灵根测试：张三参加灵根测试，意外觉醒上古剑骨。
                    第2章 拜入宗门：张三正式成为青云宗外门弟子。
                    第3章 初次修炼：张三开始修炼基础剑法，展现惊人天赋。

                    【卷2】宗门风云
                    张三在宗门中崭露头角，卷入内门争斗。
                    第4章 外门比武：张三参加外门比武大会。
                    第5章 结怨：张三与内门弟子结下梁子。
                    """, 100, 500);
            }
            @Override
            public void streamComplete(LlmRequest request, StreamHandler handler) {}
        };

        PlotterAgent agent = new PlotterAgent(mockGateway, new TestNovelRepositoryStub(), TestPromptHelper.create());
        Outline outline = agent.plan("proj-1", "剑修复仇玄幻故事",
                "主角从凡人修炼至大乘", 3);

        assertNotNull(outline);
        assertTrue(outline.getPremise().contains("张三从凡人少年成长为剑道宗师"));
        assertEquals(2, outline.getVolumes().size());
        assertEquals("初入仙门", outline.getVolumes().get(0).getTitle());
        assertEquals(3, outline.getVolumes().get(0).getSynopses().size());
        assertEquals(2, outline.getVolumes().get(1).getSynopses().size());
    }
}
