package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.KnowledgeBaseService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterDesignerAgentTest {

    @Test
    void 从需求生成主角人物卡() {
        // LlmGateway 有2个方法，用匿名类
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("""
                    【姓名】张三
                    【角色定位】主角
                    【性格】火爆冲动，重情重义，宁折不弯。
                    【能力】先天剑骨，修炼速度常人十倍，擅长剑意斩击。
                    【动机】为被灭门的师门复仇，追寻真相。
                    【外貌】黑发黑瞳，身形修长，常穿青衫，眉宇间有剑纹。
                    【人物关系】与青云宗宗主有师徒之缘；与反派血煞门门主有血海深仇。
                    【成长弧】从冲动少年成长为沉稳剑道宗师。
                    """, 100, 300);
            }
            @Override
            public void streamComplete(LlmRequest request, StreamHandler handler) {}
        };

        KnowledgeBaseService mockKb = new KnowledgeBaseService(null, null) {
            @Override public void saveCharacter(Character c) {}
            @Override public List<Character> findAllCharacters(String pid) { return List.of(); }
        };

        CharacterDesignerAgent agent = new CharacterDesignerAgent(mockGateway, mockKb, TestPromptHelper.create());
        Character c = agent.design("proj-1", "主角，复仇者", List.of());

        assertNotNull(c);
        assertEquals("张三", c.getName());
        assertEquals("主角", c.getRole());
        assertEquals("火爆冲动，重情重义，宁折不弯。", c.getPersonality());
        assertTrue(c.getAbility().contains("剑骨"));
        assertTrue(c.getMotivation().contains("复仇"));
    }

    /** NCS-152（O-2）：注入既有卡性格母题摘要 + 去重硬约束——新卡核心特质不与 ≥2 既有卡重叠。 */
    @Test
    void 有既有卡时注入母题摘要与去重硬约束() {
        final java.util.List<String> prompts = new java.util.ArrayList<>();
        LlmGateway mockGateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest request) {
                prompts.add(request.getSystemPrompt());
                return new LlmResponse("""
                    【姓名】李四
                    【角色定位】配角
                    【性格】沉默寡言，观察入微。
                    【能力】账房算学，过目不忘。
                    【动机】为兄弟讨回公道。
                    """, 100, 300);
            }
            @Override public void streamComplete(LlmRequest request, StreamHandler handler) {}
        };
        KnowledgeBaseService mockKb = new KnowledgeBaseService(null, null) {
            @Override public void saveCharacter(Character c) {}
        };

        Character existing = new Character("proj-1", "张三", "主角");
        existing.setPersonality("火爆冲动，重情重义，宁折不弯");
        existing.setMotivation("为师门复仇");
        CharacterDesignerAgent agent = new CharacterDesignerAgent(mockGateway, mockKb, TestPromptHelper.create());
        agent.design("proj-1", "新配角", List.of(existing));

        String prompt = prompts.get(0);
        assertTrue(prompt.contains("性格母题摘要"), "必须注入既有卡母题摘要段: " + prompt);
        assertTrue(prompt.contains("张三"), "摘要须含既有卡姓名");
        assertTrue(prompt.contains("火爆冲动"), "摘要须含既有卡性格关键词");
        assertTrue(prompt.contains("为师门复仇"), "摘要须含既有卡动机");
        assertTrue(prompt.contains("≥2 张既有卡") && prompt.contains("重叠"),
                "必须含 ≥2 卡重叠去重硬约束: " + prompt);
    }

    @Test
    void 无既有卡时不注入母题摘要块() {
        final java.util.List<String> prompts = new java.util.ArrayList<>();
        LlmGateway mockGateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest request) {
                prompts.add(request.getSystemPrompt());
                return new LlmResponse("""
                    【姓名】王五
                    【角色定位】主角
                    【性格】沉稳。
                    """, 100, 300);
            }
            @Override public void streamComplete(LlmRequest request, StreamHandler handler) {}
        };
        KnowledgeBaseService mockKb = new KnowledgeBaseService(null, null) {
            @Override public void saveCharacter(Character c) {}
        };

        CharacterDesignerAgent agent = new CharacterDesignerAgent(mockGateway, mockKb, TestPromptHelper.create());
        agent.design("proj-1", "主角", List.of());

        assertFalse(prompts.get(0).contains("性格母题摘要"),
                "无既有卡时不得出现空的母题摘要段");
    }
}
