package com.writing.novel.agent;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p16-fix: 卷大纲行内「(HIGH，第N章回收)」被误判为新章节起点。
 * e2e 实证（dsv4-20260829）：单行格式前瞻 (?=第\d+章|$) 未行首锚定，第1章行内
 * 伏笔「…烧痕(HIGH，第3章回收)」触发切分——「回收)；埋设-…第2章 以子偿息」被
 * 解析成第3章的脏标题，真正的第2/4章被吞，写作循环 gap-skip 出 1/3/5 跳章。
 */
class OutlineInlineForeshadowSplitTest {

    private static final String DRIFTED_OUTLINE = """
            【总纲】落魄账房学徒沈衡以"债账"法则崛起，查清师父冤死真相。

            【卷1】雨夜账册
            沈衡获得神秘账册，从被裁学徒成长为追债人。
            第1章 雨夜账册：被兴隆号裁员的落魄学徒沈衡冒雨躲进拾遗斋→掌心残页刺疼浮出"欠书贾三钱"字样。爽点：与账册初次绑定。人物：沈衡、谢玉。目的：拉起世界观钩子。张力：5。伏笔：埋设-账册第三页被撕毁且缺口有烧痕(HIGH，第3章回收)；埋设-谢玉手腕露出算盘珠细链(LOW，第4章回收)。
            第2章 以子偿息：沈衡用账册做测试，写下裁他掌柜的名字→掌柜当众出丑。爽点：规则利用。人物：沈衡、谢玉。目的：建立"账实相符"律令。张力：6。伏笔：埋设-账册空白页底有"壬午青苗契"字样(HIGH，第6章回收)。
            第3章 水底残页：悬河坊子夜水位大涨，沈衡与谢玉打捞残页，浮出产妇活当画像。爽点：悬疑反转。人物：沈衡、苏砚。目的：抛出卷一案核心。张力：8。伏笔：回收-撕页烧痕即活当契封皮(HIGH)；埋设-苏砚触页落泪(MEDIUM，第5章回收)。
            """;

    private Outline parse() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(DRIFTED_OUTLINE, 100, 500);
            }
            @Override
            public void streamComplete(LlmRequest request, StreamHandler handler) {}
        };
        PlotterAgent agent = new PlotterAgent(mockGateway, new TestNovelRepositoryStub(), TestPromptHelper.create());
        return agent.plan("proj-split", "账术奇幻故事", "主角以债账法则崛起", 3);
    }

    @Test
    void 行内回收章号不触发章节切分_全部章节解析完整() {
        Outline outline = parse();
        assertEquals(1, outline.getVolumes().size());
        var synopses = outline.getVolumes().get(0).getSynopses();
        assertEquals(3, synopses.size(),
                "行内『(HIGH，第N章回收)』不应触发切分，应解析出全部 3 章，实际: "
                        + synopses.stream().map(s -> s.getChapterIndex() + "=" + s.getTitle()).toList());
    }

    @Test
    void 章节标题干净_不含伏笔标注残片() {
        Outline outline = parse();
        var byIndex = outline.getVolumes().get(0).getSynopses().stream()
                .collect(java.util.stream.Collectors.toMap(ChapterSynopsis::getChapterIndex, s -> s));
        assertEquals("雨夜账册", byIndex.get(1).getTitle());
        assertEquals("以子偿息", byIndex.get(2).getTitle(), "第2章必须独立存在且标题干净");
        assertEquals("水底残页", byIndex.get(3).getTitle());
        for (ChapterSynopsis s : byIndex.values()) {
            assertFalse(s.getTitle().contains("回收"), "标题不得含伏笔标注: " + s.getTitle());
            assertFalse(s.getTitle().contains("埋设"), "标题不得含伏笔标注: " + s.getTitle());
            assertFalse(s.getTitle().contains("\n"), "标题不得含换行: " + s.getTitle());
        }
    }
}
