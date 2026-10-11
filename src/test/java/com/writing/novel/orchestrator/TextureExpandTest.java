package com.writing.novel.orchestrator;

import com.writing.novel.agent.ConsistencyGuard;
import com.writing.novel.agent.PassageEditor;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-196: 定点扩写修复通道——TextureGate 质感单项越界时挑最弱场景段 EXPAND，
 * ConsistencyGuard 快速复验：通过则版本化替换（情节事实不变），HIGH 漂移回退原文
 * 并升级整章重写。
 */
class TextureExpandTest extends ManualReviewLifecycleTestHarnessSupport {

    /** 复验守卫：failOnMarker 时对含漂移标记的内容返回 HIGH */
    static boolean failOnMarker = false;
    static final AtomicReference<String> lastChecked = new AtomicReference<>();
    static final List<String> expansionInstructions = new ArrayList<>();

    @Override
    protected ConsistencyGuard guardOverride() {
        return new ConsistencyGuard(null, null, null) {
            @Override
            public List<ContinuityIssue> check(String projectId, int chapterIndex,
                                               String chapterContent, List<String> cast) {
                lastChecked.set(chapterContent);
                if (failOnMarker && chapterContent != null && chapterContent.contains("龙从天而降")) {
                    return List.of(new ContinuityIssue(projectId, chapterIndex,
                            ContinuityIssue.Type.CHARACTER, ContinuityIssue.Severity.HIGH,
                            "扩写漂移：无中生有的龙"));
                }
                return List.of();
            }
        };
    }

    private PassageEditor stubEditor(String markerSuffix) {
        return new PassageEditor(new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                throw new UnsupportedOperationException("不应直连网关");
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        }, TestPromptHelper.create()) {
            @Override
            public String editPassage(String projectId, String passageText, String contextBefore,
                                      String contextAfter, String instruction, String mode) {
                assertEquals("EXPAND", mode, "定点扩写必须走 EXPAND 模式");
                expansionInstructions.add(instruction);
                String extra = "油灯的光晕在纸页上晃动，他数着窗棂的影子，等雨声小下去。".repeat(3);
                return passageText.trim() + "\n\n" + (markerSuffix == null ? extra : extra + markerSuffix);
            }
        };
    }

    /** 三个场景段（含分隔行）的合成章节 */
    private Chapter seedSegmentedChapter(Project p) {
        String content = ("账房里的油灯被风吹得晃了一下，沈砚把算盘拨到一半的手停在半空，指尖压着那页残账。".repeat(8)
                + "\n\n* * *\n\n"
                + "郑三斤凑过来，鼻尖几乎贴到纸面，窗外的更声隔着雨传进来，闷闷的两响，巷子里有狗在刨水。".repeat(8)
                + "\n\n* * *\n\n"
                + "拂晓的柜坊刚开门，掌柜的笑意淡得像水，他把手炉往怀里拢了拢，慢慢翻开第二页。".repeat(8)
                + "\n\n");
        Chapter ch = new Chapter(p.getId(), 1, "第一章 残账");
        ch.setContent(content);
        repo.saveChapter(ch);
        return repo.findChapterByIndex(p.getId(), 1).orElseThrow();
    }

    @Test
    void 扩写复验通过则版本化替换且约束情节事实() {
        failOnMarker = false;
        expansionInstructions.clear();
        Project p = seedProject();
        Chapter ch = seedSegmentedChapter(p);
        String original = ch.getContent();
        production().setTexturePassageEditor(stubEditor(null));

        boolean rewritten = production().expandForTexture(p.getId(), 1, ch, List.of(),
                List.of("对白段占比 80% 超出区间上限 55%：在连续对白之间补足成段叙述"));
        assertFalse(rewritten, "扩写成功不触发整章重写");
        String after = repo.findChapterByIndex(p.getId(), 1).orElseThrow().getContent();
        assertTrue(after.contains("油灯的光晕在纸页上晃动"), "扩写内容应替换入库");
        assertTrue(after.length() > original.length(), "扩写应增长");
        assertTrue(after.contains("掌柜的笑意淡得像水"), "未选中段落不变");
        assertFalse(expansionInstructions.isEmpty());
        assertTrue(expansionInstructions.get(0).contains("不得改变任何情节事实"), "扩写指令须约束情节事实不变");
    }

    @Test
    void 复验发现HIGH漂移回退并升级整章重写() {
        failOnMarker = true;
        expansionInstructions.clear();
        Project p = seedProject();
        Chapter ch = seedSegmentedChapter(p);
        String original = ch.getContent();
        production().setTexturePassageEditor(stubEditor("\n\n忽然一头龙从天而降，把账册叼走了。"));

        List<Map<String, Object>> events = new ArrayList<>();
        com.writing.novel.telemetry.EventLog.Listener capture = record -> {
            if (record.get("event").toString().startsWith("TEXTURE_EXPAND")) events.add(record);
        };
        com.writing.novel.telemetry.EventLog.subscribe(capture);
        boolean rewritten;
        try {
            rewritten = production().expandForTexture(p.getId(), 1, ch, List.of(),
                    List.of("对白段占比 80% 超出区间上限 55%：在连续对白之间补足成段叙述"));
        } finally {
            com.writing.novel.telemetry.EventLog.unsubscribe(capture);
        }
        assertTrue(rewritten, "复验失败应升级整章重写");
        assertTrue(events.stream().anyMatch(e -> "TEXTURE_EXPAND_REJECTED".equals(e.get("event"))),
                "应发出拒绝事件: " + events);
        String after = repo.findChapterByIndex(p.getId(), 1).orElseThrow().getContent();
        assertFalse(after.contains("龙从天而降"), "漂移内容不得入库");
        assertFalse(after.contains("油灯的光晕"), "扩写稿整体回退");
    }
}
