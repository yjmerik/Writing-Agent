package com.writing.novel.agent;

import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大纲规划 Agent。生成全书总纲 + 分卷 + 章节梗概。
 */
public class PlotterAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;

    public PlotterAgent(LlmGateway gateway, NovelRepository repository,
                        PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    public Outline plan(String projectId, String concept, String theme, int totalChapters) {
        String systemPrompt = promptService.render("plotting", Map.of(
                "concept", concept, "theme", theme,
                "totalChapters", totalChapters, "styleGuide", ""));

        String userMessage = "构思：" + concept + "\n主题：" + theme
                + "\n预计总章数：" + totalChapters
                + "\n请规划足够多的卷和章节以达到这个章节数。每卷 5-8 章。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.PLOTTING)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.8)
                .maxTokens(8000)
                .build();

        var resp = gateway.complete(req);
        Outline outline = parseOutline(resp.getContent(), projectId);

        // 输出校验：0 卷或 0 章时重试 1 次
        int parsedChapters = outline.getVolumes().stream().mapToInt(v -> v.getSynopses().size()).sum();
        if (outline.getVolumes().isEmpty() || parsedChapters == 0) {
            resp = gateway.complete(req);
            outline = parseOutline(resp.getContent(), projectId);
            parsedChapters = outline.getVolumes().stream().mapToInt(v -> v.getSynopses().size()).sum();
        }

        // 仍为空（0 卷或 0 章）的 fallback：创建 1 卷 3 章的最小大纲
        if (outline.getVolumes().isEmpty() || parsedChapters == 0) {
            outline = new Outline(projectId, concept);
            Volume v = new Volume(projectId, 1, "第一卷", theme);
            v.addSynopsis(new ChapterSynopsis(projectId, 1, "第一章 开端", concept + " 故事开始"));
            v.addSynopsis(new ChapterSynopsis(projectId, 2, "第二章 发展", "情节推进"));
            v.addSynopsis(new ChapterSynopsis(projectId, 3, "第三章 高潮", "第一次冲突"));
            outline.addVolume(v);
        }

        repository.saveOutline(outline);
        return outline;
    }

    private Outline parseOutline(String text, String projectId) {
        // 提取总纲
        String premise = "";
        Matcher premM = Pattern.compile("【总纲】(.+?)(?=【卷|$)", Pattern.DOTALL).matcher(text);
        if (premM.find()) premise = premM.group(1).trim();

        Outline outline = new Outline(projectId, premise);

        // 提取卷：【卷N】或【卷一】卷名\n 后面直到下一个【卷或结尾
        // 支持阿拉伯数字和中文数字（卷1/卷一/卷壹）；兼容 \r\n 和 \n 换行
        Matcher volM = Pattern.compile("【卷([0-9一二三四五六七八九十百]+)】(.+?)\\r?\\n(.+?)(?=【卷|【伏笔|$)", Pattern.DOTALL).matcher(text);
        while (volM.find()) {
            int volIndex = parseChineseOrArabic(volM.group(1));
            String volTitle = volM.group(2).trim();
            String volBody = volM.group(3).trim();
            // 卷梗概是第一行（不含"第X章"的行）
            String volSummary = "";
            String[] lines = volBody.split("\\n");
            for (String line : lines) {
                String lt = line.trim();
                if (!lt.isEmpty() && !lt.startsWith("第")) {
                    volSummary = lt;
                    break;
                }
            }
            Volume volume = new Volume(projectId, volIndex, volTitle, volSummary);

            // 提取章节：第X章，X 支持中文数字和阿拉伯数字
            Matcher chM = Pattern.compile("第([0-9一二三四五六七八九十百]+)章\\s*(.+?)：(.+)").matcher(volBody);
            while (chM.find()) {
                int idx = parseChineseOrArabic(chM.group(1));
                String title = chM.group(2).trim();
                String synopsis = chM.group(3).trim();
                ChapterSynopsis cs = new ChapterSynopsis(projectId, idx, title, synopsis);
                volume.addSynopsis(cs);
            }
            outline.addVolume(volume);
        }
        return outline;
    }

    /** 解析中文数字或阿拉伯数字（一→1，二→2...十→10，二十→20） */
    private int parseChineseOrArabic(String s) {
        if (s.matches("\\d+")) return Integer.parseInt(s);
        // 简单中文数字转换（支持 0-99）
        String[] digits = {"零","一","二","三","四","五","六","七","八","九"};
        if (s.equals("十")) return 10;
        if (s.startsWith("十")) return 10 + parseChineseOrArabic(s.substring(1));
        if (s.endsWith("十")) return parseChineseOrArabic(s.substring(0, 1)) * 10;
        if (s.contains("十")) {
            String[] parts = s.split("十");
            return parseChineseOrArabic(parts[0]) * 10 + (parts.length > 1 ? parseChineseOrArabic(parts[1]) : 0);
        }
        for (int i = 0; i < digits.length; i++) {
            if (digits[i].equals(s)) return i;
        }
        return 1; // 兜底
    }
}
