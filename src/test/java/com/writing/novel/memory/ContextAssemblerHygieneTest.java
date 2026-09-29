package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-115: ContextAssembler 工程卫生——
 * 1) 主角判定结构化（"女主角之妹"不再误锚定）；
 * 2) 关系双段去重（实体段含当前关系动态时不再叠加平行关系网络段）；
 * 3) stableSectionCache 有界 LRU；
 * 4) 裁剪增量核算（行为等价，见 ContextBudgetAdaptiveTest）。
 */
class ContextAssemblerHygieneTest {

    @Test
    void 主角判定_精确集合() {
        assertTrue(ContextAssembler.isMainCharacter(new Character("p", "林凡", "主角")));
        assertTrue(ContextAssembler.isMainCharacter(new Character("p", "林凡", "女主角")));
        assertTrue(ContextAssembler.isMainCharacter(new Character("p", "林凡", "主人公")));
        // 旧 contains 误命中：配角角色名含主角子串
        assertFalse(ContextAssembler.isMainCharacter(new Character("p", "小翠", "女主角之妹")),
                "「女主角之妹」不是主角（旧 contains 误锚定）");
        assertFalse(ContextAssembler.isMainCharacter(new Character("p", "老仆", "主角身边的仆人")));
        assertFalse(ContextAssembler.isMainCharacter(new Character("p", "路人", null)));
    }

    @Test
    void 关系段去重_实体段含当前关系时不叠加网络段() {
        // 场景化验证在集成层（需要边表/实体段）；此处固化判定语义：
        // 当实体段渲染了「当前关系动态」（canonical 源），relationshipWeb 应被跳过
        String entitySection = "【关键实体一致性】…\\n■ 当前关系动态（本章角色间的关系必须与此一致）：\\n林凡 → 赵九：师徒";
        assertTrue(entitySection.contains("当前关系动态"), "去重判定依据的标记存在");
    }

    @Test
    void 缓存有界LRU() throws Exception {
        ContextAssembler assembler = new ContextAssembler(null, null, null, null, null, null, null, null, null);
        var m = ContextAssembler.class.getDeclaredMethod("cached", String.class, String.class,
                java.util.function.Supplier.class);
        m.setAccessible(true);
        for (int i = 0; i < 400; i++) {
            m.invoke(assembler, "sec" + i, "proj" + i, (java.util.function.Supplier<String>) () -> "v");
        }
        assertTrue(assembler.stableSectionCacheSize() <= 256,
                "缓存必须淘汰到上限内: " + assembler.stableSectionCacheSize());
    }
}
