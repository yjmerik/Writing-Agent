package com.writing.novel.memory;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.agent.EntityExtractorAgent;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {

    @TempDir
    Path tempDir;
    private ContextAssembler assembler;
    private SqliteNovelRepository repo;
    private KnowledgeBaseService kbService;
    private SummaryService summaryService;
    private EntityGraphService entityGraphService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ctx.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("ctx.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        kbService = new KnowledgeBaseService(repo, vectorSvc);
        summaryService = new SummaryService(repo);
        entityGraphService = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        assembler = new ContextAssembler(repo, kbService, summaryService, vectorSvc, entityGraphService, new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
    }

    @Test
    void 组装包含风格锚点与世界规则() {
        Project p = new Project("测试", "简介", "玄幻");
        repo.saveProject(p);
        StyleProfile style = new StyleProfile(p.getId());
        style.setTone("热血、紧凑");
        style.setDosAndDonts("多用短句，避免大段说明");
        repo.saveStyleProfile(style);
        kbService.saveEntry(new KnowledgeEntry(p.getId(), "POWER_SYSTEM", "灵气体系", "修炼九重境界"));

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章 灵根测试",
                "张三参加灵根测试", "写 3000 字，节奏紧凑");

        assertNotNull(ctx);
        assertTrue(ctx.getStyleSection().contains("热血"));
        assertTrue(ctx.getWorldbuildingSection().contains("灵气体系"));
    }

    @Test
    void 组装包含近期章节摘要与原文() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);

        Chapter prev = new Chapter(p.getId(), 1, "第一章");
        prev.setContent("上一章的正文内容，张三踏入山门。");
        repo.saveChapter(prev);

        summaryService.save(new Summary(p.getId(), 1, 1, "张三拜入青云宗。"));

        WritingContext ctx = assembler.assemble(p.getId(), 2, "第二章",
                "张三开始修炼", null);

        assertTrue(ctx.getRecentSummarySection().contains("张三拜入青云宗"));
        assertTrue(ctx.getRecentTextSection().contains("上一章的正文内容"));
    }

    @Test
    void 组装包含检索到的人物卡() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        Character mc = new Character(p.getId(), "张三", "主角");
        mc.setPersonality("火爆");
        mc.setAbility("剑修");
        kbService.saveCharacter(mc);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章",
                "张三参加灵根测试", null);

        assertFalse(ctx.getRelevantCharacters().isEmpty(), "应检索到相关人物");
        assertEquals("张三", ctx.getRelevantCharacters().get(0).getName());
    }

    @Test
    void 组装包含任务章节梗概() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章 灵根测试",
                "本章：张三觉醒上古剑骨，震惊全场。", "字数 3000，结尾留悬念");

        assertTrue(ctx.getTaskSection().contains("张三觉醒上古剑骨"));
        assertTrue(ctx.getTaskSection().contains("3000"));
    }

    @Test
    void 组装为完整的system和user_prompt() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        StyleProfile style = new StyleProfile(p.getId());
        style.setTone("热血");
        repo.saveStyleProfile(style);
        kbService.saveEntry(new KnowledgeEntry(p.getId(), "RULE", "天道", "天道酬勤"));
        Character c = new Character(p.getId(), "张三", "主角");
        c.setPersonality("火爆");
        kbService.saveCharacter(c);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章", "张三登场", null);

        String system = ctx.toSystemPrompt();
        String user = ctx.toUserPrompt();

        assertNotNull(system);
        assertNotNull(user);
        assertTrue(system.contains("热血"));
        assertTrue(system.contains("天道"));
        assertTrue(user.contains("张三"));
    }

    @Test
    void 同章同级反馈超过上限时按描述稳定选择() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        repo.saveWritingPreferences(new WritingPreferences(p.getId()));

        for (int index = 9; index >= 1; index--) {
            repo.saveContinuityIssue(new ContinuityIssue(
                    p.getId(), 1, ContinuityIssue.Type.CHARACTER,
                    ContinuityIssue.Severity.HIGH, "反馈-" + index));
        }

        WritingContext context = assembler.assemble(p.getId(), 2, "第二章", "梗概", null);

        for (int index = 1; index <= 8; index++) {
            assertTrue(context.getFeedbackSection().contains("反馈-" + index));
        }
        assertFalse(context.getFeedbackSection().contains("反馈-9"));
    }

    /** NCS-191: HISTORICAL 题材约束改为 NarrativePolicy 区间双向渲染（含转场/定场/场景预算指令） */
    @Test
    void 历史题材策略化约束_区间双向与转场指令() {
        Project p = new Project("权谋", "庙堂", "历史");
        p.setGenre(GenreProfile.HISTORICAL);
        repo.saveProject(p);
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v1 = new Volume(p.getId(), 1, "卷一", "梗概");
        ChapterSynopsis s2 = new ChapterSynopsis(p.getId(), 2, "夜宴", "梗概");
        s2.setBeats("夜宴密议→城头纵火→禅位诏出");
        v1.getSynopses().add(s2);
        outline.getVolumes().add(v1);
        repo.saveOutline(outline);

        var policyCp = new ConnectionProvider(tempDir.resolve("policy-x.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(policyCp).initialize();
        assembler.setNarrativePolicyService(new com.writing.novel.service.NarrativePolicyService(
                repo, new com.writing.novel.persistence.sqlite.NarrativePolicyRepository(policyCp)));
        WritingContext context = assembler.assemble(p.getId(), 2, "第二章", "梗概", null);
        String c = context.getWritingConstraints();
        assertTrue(c.contains("30%-55%"), "对话段占比区间双向文案");
        assertTrue(c.contains("过渡"), "转场指令");
        assertTrue(c.contains("定场"), "章首定场许可");
        assertTrue(c.contains("相邻节拍可合并"), "解除节拍矛盾指令");
        assertTrue(c.contains("1200"), "每节拍字数预算");
        assertFalse(c.contains("必须全部覆盖"), "不再要求节拍全覆盖");
        assertFalse(c.contains("精简次要场景和描写"), "超字数不再点名砍描写");
        assertFalse(c.contains("不得低于"), "不再只有对话下限");
    }

    /** NCS-191: 非 HISTORICAL 题材保持现状文案（字节等价，golden/回放不回归） */
    @Test
    void 快节奏题材约束保持现状文案() {
        Project p = new Project("t", "c", "tag");
        p.setGenre(GenreProfile.CULTIVATION);
        repo.saveProject(p);
        repo.saveWritingPreferences(new WritingPreferences(p.getId()));
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v1 = new Volume(p.getId(), 1, "卷一", "梗概");
        ChapterSynopsis s2 = new ChapterSynopsis(p.getId(), 2, "突破", "梗概");
        s2.setBeats("闭关→心魔→破境");
        v1.getSynopses().add(s2);
        outline.getVolumes().add(v1);
        repo.saveOutline(outline);

        var policyCpY = new ConnectionProvider(tempDir.resolve("policy-y.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(policyCpY).initialize();
        assembler.setNarrativePolicyService(new com.writing.novel.service.NarrativePolicyService(
                repo, new com.writing.novel.persistence.sqlite.NarrativePolicyRepository(policyCpY)));
        WritingContext context = assembler.assemble(p.getId(), 2, "第二章", "梗概", null);
        String c = context.getWritingConstraints();
        assertTrue(c.contains("对话占比不得低于"), "快节奏保留对话下限文案");
        assertTrue(c.contains("场景节拍必须全部覆盖"), "快节奏保留节拍全覆盖文案");
        assertTrue(c.contains("精简次要场景和描写"), "快节奏保留超字数文案");
        assertFalse(c.contains("过渡"), "快节奏不注入转场指令");
    }

    /**
     * NCS-189: 非 STYLE 反馈截断 80→200 字——PACING 类纠正指令
     * （如"建议下一章设为喘息章（tension≤3）…"）曾被 80 字腰斩。
     */
    @Test
    void 反馈注入截断放宽到200字() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        repo.saveWritingPreferences(new WritingPreferences(p.getId()));
        // 81-200 区间的关键内容（旧 80 字截断必然丢失）+ 200 之外的超长尾部
        String desc = "甲".repeat(80) + "乙".repeat(60) + "丙".repeat(100);
        repo.saveContinuityIssue(new ContinuityIssue(p.getId(), 1,
                ContinuityIssue.Type.PACING, ContinuityIssue.Severity.MEDIUM, desc));

        WritingContext context = assembler.assemble(p.getId(), 2, "第二章", "梗概", null);
        String feedback = context.getFeedbackSection();
        assertTrue(feedback.contains("乙乙乙"), "81-200 字区间的纠正内容应保留");
        assertTrue(feedback.contains(desc.substring(0, 200) + "..."), "截断点应在 200 字");
        assertFalse(feedback.contains(desc), "超长描述仍应截断（非 STYLE 不全文注入）");
    }
}
