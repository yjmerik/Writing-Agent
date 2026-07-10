package com.writing.novel.agent;

import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.spi.NovelRepository;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大纲规划 Agent。生成全书总纲 + 分卷 + 章节梗概。
 */
public class PlotterAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;

    public PlotterAgent(LlmGateway gateway, NovelRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    public Outline plan(String projectId, String concept, String theme, int totalChapters) {
        String systemPrompt = """
                你是网络小说大纲规划专家。根据构思生成分层大纲：总纲 + 多卷 + 每卷的章节梗概。
                严格按以下格式输出：

                【总纲】全书主线一句话概括。

                【卷N】卷名
                本卷梗概描述。
                第X章 章节名：本章内容梗概。
                第Y章 章节名：本章内容梗概。

                【卷M】卷名
                ...
                """;

        String userMessage = "构思：" + concept + "\n主题：" + theme
                + "\n预计总章数：" + totalChapters
                + "\n请规划 2-3 卷，每卷 3-5 章。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.PLOTTING)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.8)
                .maxTokens(3000)
                .build();

        var resp = gateway.complete(req);
        Outline outline = parseOutline(resp.getContent(), projectId);
        repository.saveOutline(outline);
        return outline;
    }

    private Outline parseOutline(String text, String projectId) {
        // 提取总纲
        String premise = "";
        Matcher premM = Pattern.compile("【总纲】(.+?)(?=【卷|$)", Pattern.DOTALL).matcher(text);
        if (premM.find()) premise = premM.group(1).trim();

        Outline outline = new Outline(projectId, premise);

        // 提取卷：【卷N】卷名\n 后面直到下一个【卷或结尾
        Matcher volM = Pattern.compile("【卷(\\d+)】(.+?)\\n(.+?)(?=【卷|【伏笔|$)", Pattern.DOTALL).matcher(text);
        while (volM.find()) {
            int volIndex = Integer.parseInt(volM.group(1));
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

            // 提取章节
            Matcher chM = Pattern.compile("第(\\d+)章\\s*(.+?)：(.+)").matcher(volBody);
            while (chM.find()) {
                int idx = Integer.parseInt(chM.group(1));
                String title = chM.group(2).trim();
                String synopsis = chM.group(3).trim();
                ChapterSynopsis cs = new ChapterSynopsis(projectId, idx, title, synopsis);
                volume.addSynopsis(cs);
            }
            outline.addVolume(volume);
        }
        return outline;
    }
}
