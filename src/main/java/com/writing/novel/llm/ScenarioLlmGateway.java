package com.writing.novel.llm;

import com.writing.novel.llm.client.StreamHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * p23-01: 确定性场景网关——零网络、零成本、按任务类型产出语法合法的响应，
 * 供可靠性 soak / 业务生命周期 E2E 驱动完整编排器（不证明文学质量）。
 *
 * <p>确定性约定：同一 (taskType, 请求序号) 永远返回同一文本；正文中镜像 prompt 里
 * 检测到的章节号，使不同章节的响应可区分且逐字可复现。
 */
public class ScenarioLlmGateway implements LlmGateway {

    private static final Pattern CHAPTER_REF = Pattern.compile("第(\\d{1,4})章");

    /** 每任务类型调用计数——响应随序号轻度变化但完全可复现 */
    private final Map<String, Integer> callSeq = new ConcurrentHashMap<>();

    private int chapterOf(LlmRequest req) {
        int chapter = 1;
        Matcher m = CHAPTER_REF.matcher(req.getSystemPrompt() + "\n" + req.getUserMessage());
        while (m.find()) {
            chapter = Math.max(chapter, Integer.parseInt(m.group(1)));
        }
        return chapter;
    }

    private int seq(String taskType) {
        return callSeq.merge(taskType, 1, Integer::sum);
    }

    @Override
    public LlmResponse complete(LlmRequest req) {
        LlmTaskType type = req.getTaskType();
        int chapter = chapterOf(req);
        int n = seq(type.name());
        String content = switch (type) {
            case WORLDBUILDING -> """
                    【地理】场景大陆%03d：故事发生的主大陆，地貌自西向东为山地、平原、海岸。
                    【力量体系】武学体系%03d：修炼体系，境界有明确层级，突破需际遇支撑。
                    【势力】%03d号门派：江湖势力，与主角亦敌亦友。
                    【历史】%03d朝：百年前立国，沿用至今的年号与官制。
                    【规则】世界铁律%03d：武功威力有上限，禁止跨界飞升。
                    【物品】信物%03d：师门玉佩，承载身世线索。
                    """.formatted(chapter, chapter, chapter, chapter, chapter, chapter);
            case CHARACTER_DESIGN -> """
                    【姓名】主角%03d
                    【角色定位】主角
                    【性格】坚韧，重信义
                    【能力】基础剑法
                    【动机】查清师门旧案
                    【成长弧】从孤身复仇到守护同道
                    """.formatted(n);
            case PLOTTING -> """
                    【总纲】主角%03d在江湖中查清师门旧案、成长为支柱的历程。

                    【卷1】初入江湖
                    拜入门派，结识同伴，初遇强敌。
                    第1章 入门：%03d号场景，主角入门试炼。
                    第2章 修行：基本功法初成，结识同伴。
                    第3章 冲突：与%03d号门派弟子冲突。
                    """.formatted(n, chapter, chapter);
            case CHAPTER_WRITING -> {
                String base = "第%d章正文。晨雾未散，主角沿着山道而上，心中的疑团比雾更浓。"
                        .formatted(chapter);
                StringBuilder sb = new StringBuilder(base);
                while (sb.length() < 2600) {
                    sb.append("他回想起第").append(chapter).append("章之前的种种线索，")
                      .append("每一步都像棋子落下，无法回头。山风掠过，剑鞘微凉，")
                      .append("他知道这一章的抉择将决定后续所有人的命运。\n");
                }
                yield sb.toString();
            }
            case CONTINUITY_CHECK -> "[]";
            case STYLE_GUARDIAN -> "风格一致性评分：90/100。本章与基线风格保持一致。";
            case MEMORY_SUMMARY -> "章节摘要：第%d章确立的事件与人物变化已按序记录。".formatted(chapter);
            case GENERAL -> "[]";
        };
        return new LlmResponse(content, Math.max(10, content.length() / 3), Math.max(10, content.length() / 6));
    }

    @Override
    public void streamComplete(LlmRequest req, StreamHandler handler) {
        // 场景网关不支持流式——聚合为完整响应一次性回调
        try {
            String content = complete(req).getContent();
            handler.onToken(content);
            handler.onComplete();
        } catch (Exception e) {
            handler.onError(e);
        }
    }
}
