package com.writing.novel.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private static final Pattern REVISION_TARGET = Pattern.compile(
            "目标类型：\\s*(WORLD|CHARACTER|VOLUME|CHAPTER|PASSAGE)");
    private static final String REVISION_SOURCE_START = "当前选中对象（JSON）：";
    private static final String REVISION_SOURCE_END = "用户修改要求：";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
            case WORLDBUILDING -> req.getUserMessage().contains("【用户修改意见】")
                    ? """
                    【地理】北境三州%03d：故事发生在严寒边镇，驿路与关城决定信息流动。
                    【历史】永熙年间%03d：王朝末年，旧案与边军粮册牵动朝局。
                    【势力】巡检司%03d：依律查案的地方衙门，与豪强彼此牵制。
                    【文化】乡约礼俗%03d：宗族与保甲共同维持地方秩序。
                    【规则】方术限制%03d：只能辨迹寻物，不能飞升或凭空杀人。
                    """.formatted(n, n, n, n, n)
                    : """
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
                // p23-02: 正文不含任何「第N章」字样——章号由系统注入，场景文本
                // 不污染后续 prompt 的章号探测，也不触发章号一致性告警
                String base = "场景正文。晨雾未散，主角沿着山道而上，心中的疑团比雾更浓。";
                StringBuilder sb = new StringBuilder(base);
                while (sb.length() < 2600) {
                    sb.append("他回想起之前的种种线索，每一步都像棋子落下，无法回头。")
                      .append("山风掠过，剑鞘微凉，他知道这一章的抉择将决定后续所有人的命运。\n");
                }
                yield sb.toString();
            }
            case CONTINUITY_CHECK -> {
                // p23-02: harsh 档按检查调用序触发——前 2 次（第1章初检 + 重写后复检）
                // 恒报 HIGH，驱动「人工复核暂停→处理→续跑」场景；与 prompt 文本解耦
                if (harsh && n <= 2) {
                    yield "【人物|高】主角的行为超出能力设定（harsh 注入）";
                }
                yield "[]";
            }
            case STYLE_GUARDIAN -> "风格一致性评分：90/100。本章与基线风格保持一致。";
            case MEMORY_SUMMARY -> "章节摘要：第%d章确立的事件与人物变化已按序记录。".formatted(chapter);
            case GENERAL -> contentRevisionCandidate(req);
        };
        return new LlmResponse(content, Math.max(10, content.length() / 3), Math.max(10, content.length() / 6));
    }

    private String contentRevisionCandidate(LlmRequest request) {
        String prompt = request.getSystemPrompt();
        if (prompt == null || !prompt.contains("你是内容修订 Agent")) return "[]";

        Matcher targetMatcher = REVISION_TARGET.matcher(prompt);
        int sourceStart = prompt.indexOf(REVISION_SOURCE_START);
        int sourceEnd = prompt.indexOf(REVISION_SOURCE_END);
        if (!targetMatcher.find() || sourceStart < 0 || sourceEnd <= sourceStart) return "[]";

        String targetType = targetMatcher.group(1);
        String currentJson = prompt.substring(sourceStart + REVISION_SOURCE_START.length(), sourceEnd).trim();
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(currentJson);
            if (!(parsed instanceof ObjectNode after)) return "[]";

            String content = switch (targetType) {
                case "WORLD" -> {
                    String revised = appendMarker(after.path("content").asText());
                    after.put("content", revised);
                    yield revised;
                }
                case "CHARACTER" -> {
                    String revised = appendMarker(after.path("motivation").asText());
                    after.put("motivation", revised);
                    yield revised;
                }
                case "VOLUME" -> {
                    String revised = appendMarker(after.path("summary").asText());
                    after.put("summary", revised);
                    yield revised;
                }
                case "CHAPTER" -> {
                    String revised = appendMarker(after.path("title").asText());
                    after.put("title", revised);
                    yield after.path("content").asText();
                }
                case "PASSAGE" -> {
                    String revised = appendMarker(after.path("content").asText());
                    after.put("content", revised);
                    yield revised;
                }
                default -> throw new IllegalStateException("未知修订目标: " + targetType);
            };

            ObjectNode candidate = OBJECT_MAPPER.createObjectNode()
                    .put("targetType", targetType)
                    .put("title", targetType + " 修订候选")
                    .put("content", content)
                    .put("structuredJson", OBJECT_MAPPER.writeValueAsString(after))
                    .put("summary", "仅修改当前选中的 " + targetType + " 对象");
            return OBJECT_MAPPER.writeValueAsString(candidate);
        } catch (JsonProcessingException | IllegalStateException e) {
            return "[]";
        }
    }

    private static String appendMarker(String value) {
        String base = value == null ? "" : value.trim();
        return base.isEmpty() ? "【Agent 修订】" : base + "【Agent 修订】";
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
