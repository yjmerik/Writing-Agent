package com.writing.novel.service;

import com.writing.novel.domain.Character;
import com.writing.novel.service.CharacterRevisionPreviewService.PendingRevisions;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-154（review 整改，修 NCS-144 批准制缺口）：人物卡修订必须走预览-确认链——
 * 候选卡先阶段化为待确认预览（逐字段 before/after diff），作者 confirm 才落库、
 * discard 即清空；候选空白字段保留原值（copyIfPresent 语义下沉到本服务）。
 */
class CharacterRevisionPreviewTest {

    @TempDir Path tempDir;
    private NovelRepository repo;
    private CharacterRevisionPreviewService svc;
    private String pid;
    private Character shen;

    @BeforeEach
    void setUp() {
        com.writing.novel.persistence.sqlite.ConnectionProvider cp =
                new com.writing.novel.persistence.sqlite.ConnectionProvider(
                        tempDir.resolve("char-preview.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        svc = new CharacterRevisionPreviewService(repo);

        var p = new com.writing.novel.domain.Project("预览确认链", "测试", "武侠");
        repo.saveProject(p);
        pid = p.getId();

        shen = new Character(pid, "沈铁衣", "主角");
        shen.setPersonality("旧性格：守时如命");
        shen.setAbility(null);
        repo.saveCharacter(shen);
    }

    private Character candidateWith(String personality, String ability, String appearance) {
        Character c = new Character(pid, "沈铁衣", "主角");
        c.setPersonality(personality);
        c.setAbility(ability);
        c.setAppearance(appearance);
        return c;
    }

    @Test
    void 阶段化生成逐字段diff_空白候选不产生变更项_库中未变() {
        PendingRevisions staged = svc.stage(pid,
                List.of(shen), List.of(candidateWith("新性格：寡言而烈", "  ", null)));

        assertEquals(1, staged.cards().size());
        var changes = staged.cards().get(0).changes();
        assertEquals(1, changes.size(), "空白/缺字段的候选不得产生变更项: " + changes);
        assertEquals("personality", changes.get(0).field());
        assertEquals("旧性格：守时如命", changes.get(0).before());
        assertEquals("新性格：寡言而烈", changes.get(0).after());

        Character inRepo = findShen();
        assertEquals("旧性格：守时如命", inRepo.getPersonality(), "确认前卡不得落库");
    }

    @Test
    void 确认后保留原卡id落库_未点名卡零变动() {
        Character pu = new Character(pid, "蒲慎川", "配角");
        pu.setPersonality("旧性格：谨慎过人");
        repo.saveCharacter(pu);

        PendingRevisions staged = svc.stage(pid,
                List.of(shen), List.of(candidateWith("新性格：寡言而烈", "听雨辨位", null)));
        String summary = svc.confirm(pid, staged.previewId());

        Character revised = findShen();
        assertEquals(shen.getId(), revised.getId(), "必须保留原卡 id（按 id upsert）");
        assertEquals("新性格：寡言而烈", revised.getPersonality());
        assertEquals("听雨辨位", revised.getAbility());
        Character untouched = repo.findCharactersByProject(pid).stream()
                .filter(c -> c.getName().equals("蒲慎川")).findFirst().orElseThrow();
        assertEquals("旧性格：谨慎过人", untouched.getPersonality(), "未点名卡逐字不变");
        assertTrue(summary.contains("沈铁衣"), "确认摘要应点名已应用卡");
        assertTrue(svc.find(pid).isEmpty(), "确认后待确认预览清空");
    }

    @Test
    void 放弃后清空且库中不变() {
        PendingRevisions staged = svc.stage(pid,
                List.of(shen), List.of(candidateWith("新性格：寡言而烈", null, null)));
        svc.discard(pid, staged.previewId());

        assertTrue(svc.find(pid).isEmpty(), "放弃后待确认预览清空");
        assertEquals("旧性格：守时如命", findShen().getPersonality(), "放弃后库中不变");
    }

    @Test
    void 未知previewId或项目不符报错() {
        PendingRevisions staged = svc.stage(pid,
                List.of(shen), List.of(candidateWith("新性格：寡言而烈", null, null)));

        assertThrows(IllegalArgumentException.class, () -> svc.confirm(pid, "不存在的id"));
        assertThrows(IllegalArgumentException.class, () -> svc.discard(pid, "不存在的id"));
        assertThrows(IllegalArgumentException.class, () -> svc.confirm("别的项目", staged.previewId()));
        assertNotNull(svc.find(pid).orElse(null), "失败的确认不得清掉待确认预览");
    }

    @Test
    void 同项目再次阶段化覆盖旧预览() {
        svc.stage(pid, List.of(shen), List.of(candidateWith("新性格一", null, null)));
        PendingRevisions second = svc.stage(pid, List.of(shen), List.of(candidateWith("新性格二", null, null)));

        assertNotEquals(second.previewId(), "");
        assertTrue(svc.find(pid).isPresent(), "同项目再次阶段化后仍有待确认预览");
        assertEquals("新性格二", svc.find(pid).orElseThrow().cards().get(0).changes().get(0).after(),
                "同项目只保留最新待确认预览");
    }

    private Character findShen() {
        return repo.findCharactersByProject(pid).stream()
                .filter(c -> c.getName().equals("沈铁衣")).findFirst().orElseThrow();
    }
}
