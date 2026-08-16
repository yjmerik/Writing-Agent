package com.writing.novel.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BibleBudgetProtectionTest {

    @Test
    void 超预算时premise与bible保留_先裁quote和voice() {
        var assembler = new ContextAssembler(null, null, null, null, null, null, null, null, null);
        WritingContext ctx = new WritingContext();
        ctx.setPremiseSection("全书原旨：复仇与救赎");
        ctx.setBibleSection("■ [WORLD_RULE] 灵气：第1卷铁律");
        ctx.setKnowledgeQuoteSection("引用素材".repeat(400));
        ctx.setCharacterVoiceSection("角色声音".repeat(400));
        ctx.setQualityFeedbackSection("反馈".repeat(200));
        ctx.setRecentSummarySection("摘要".repeat(500));

        assembler.applyTokenBudget(ctx, 200);

        String prompt = ctx.toSystemPrompt();
        assertTrue(prompt.contains("全书原旨：复仇与救赎"));
        assertTrue(prompt.contains("第1卷铁律"));
        assertTrue(ctx.getKnowledgeQuoteSection() == null || ctx.getKnowledgeQuoteSection().isBlank());
        assertTrue(ctx.getCharacterVoiceSection() == null || ctx.getCharacterVoiceSection().isBlank());
    }
}
