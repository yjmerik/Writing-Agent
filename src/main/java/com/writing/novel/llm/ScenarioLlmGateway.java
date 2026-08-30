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

    /** p23-02: harsh 档——第 1 章注入 HIGH 连续性矛盾，驱动人工复核暂停→处理→续跑场景 */
    private final boolean harsh;

    public ScenarioLlmGateway() {
        this(false);
    }

    public ScenarioLlmGateway(boolean harsh) {
        this.harsh = harsh;
    }
    /** p23-03: 构思 prompt 中的目标章数（预计总章数：N）——PLOTTING 按此生成大纲 */
    private static final Pattern TOTAL_CHAPTERS = Pattern.compile("预计总章数：(\\d{1,4})");
    /** p23-03: 卷大纲 prompt 的章节范围（章节范围：第X章到第Y章）——planVolume 按此生成该卷章节行 */
    private static final Pattern VOLUME_RANGE = Pattern.compile("章节范围：第(\\d{1,4})章到第(\\d{1,4})章");

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

    /** 解析构思请求的目标章数（缺省 3） */
    private int totalChaptersOf(LlmRequest req) {
        Matcher m = TOTAL_CHAPTERS.matcher(req.getUserMessage());
        return m.find() ? Math.max(1, Integer.parseInt(m.group(1))) : 3;
    }

    /** 解析卷大纲请求的章节范围；非卷大纲请求返回 null */
    private int[] volumeRangeOf(LlmRequest req) {
        Matcher m = VOLUME_RANGE.matcher(req.getSystemPrompt() + "\n" + req.getUserMessage());
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return null;
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
            case PLOTTING -> {
                // p23-03: 卷大纲请求——按「章节范围：第X章到第Y章」生成该卷的逐章大纲行
                int[] range = volumeRangeOf(req);
                if (range != null) {
                    StringBuilder vol = new StringBuilder();
                    for (int c = range[0]; c <= range[1]; c++) {
                        vol.append("第").append(c).append("章 事件").append(c)
                           .append("：具体场景节拍A→场景节拍B→场景节拍C。爽点：核心爽感。")
                           .append("人物：主角。目的：推进主线。张力：6。伏笔：无。\n");
                    }
                    yield vol.toString();
                }
                // 总纲请求——按 prompt 中的预计总章数生成等量章节行（每卷 8 章）
                int total = totalChaptersOf(req);
                StringBuilder sb = new StringBuilder();
                sb.append("【总纲】主角在江湖中查清师门旧案、成长为支柱的历程（场景模式，共")
                  .append(total).append("章）。\n\n");
                int volumes = (total + 7) / 8;
                for (int v = 1, c = 1; v <= volumes; v++) {
                    sb.append("【卷").append(v).append("】征途第").append(v).append("卷\n");
                    sb.append("第").append(v).append("卷梗概：冲突逐步升级，主角在这一卷完成阶段性成长。\n");
                    int end = Math.min(total, v * 8);
                    for (; c <= end; c++) {
                        sb.append("第").append(c).append("章 事件").append(c)
                          .append("：第").append(c).append("章的场景梗概，遭遇与抉择推动主线。\n");
                    }
                }
                yield sb.toString();
            }
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
            case CONTINUITY_CHECK -> {
                if (harsh && chapter == 1) {
                    yield "[{\"type\":\"CHARACTER\",\"severity\":\"HIGH\","
                          + "\"description\":\"主角在第1章的行为超出能力设定（harsh 注入）\"}]";
                }
                yield "[]";
            }
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
