package com.writing.novel.service;

import com.writing.novel.domain.GenreProfile;

/**
 * 题材规则提供者——根据题材类型生成特定的写作规则文本。
 * 用于条件化注入 plotting.st 和 chapter-writing.st。
 */
public class GenreRuleProvider {

    /** 大纲规划阶段的题材特定规则 */
    public String getPlottingRules(GenreProfile genre) {
        if (genre == null) return "";
        return switch (genre) {
            case CULTIVATION -> """
                题材规则（玄幻修仙）：
                - 每卷主角只能完成一个境界/阶段的提升
                - 力量体系必须有明确的等级划分
                -爽点绑定世界观独特设定（功法/法宝/阵法）
                - 反套路：避免坠崖得宝、天赋测试打脸、宗门大比碾压""";

            case MYSTERY -> """
                题材规则（悬疑推理）：
                - 每章至少埋设1条线索或揭示1条线索
                - 线索必须公平——读者理论上可以在揭晓前推理出真相
                - 至少设置1个红鲱鱼（误导性线索）
                - 真相揭示必须有逻辑链支撑，不能凭空出现""";

            case ROMANCE -> """
                题材规则（言情都市）：
                - 情感节拍：相遇→冲突→暧昧→误解→和解→确认
                - 每章至少1个情感互动场景（对话/心理/行为）
                - 角色化学反应优先于外部情节
                - 避免强行误会和狗血桥段""";

            case THRILLER -> """
                题材规则（惊悚悬疑）：
                - 危机逐步升级，每章 stakes 提高
                - 时间压力：设置倒计时或截止期限
                - 至少1个意外转折（不一定是反转，可以是新威胁出现）
                - 主角必须主动行动，不能被动等待""";

            case HISTORICAL -> """
                题材规则（历史权谋）：
                - 政治博弈必须有明确的利益冲突
                - 每个势力至少有1个明确的目标
                - 引用历史典故/兵法/权谋术增强深度
                - 角色动机可以多层次（忠义/利益/复仇并存）
                - 至少设计1个有独立弧线的女性角色（不只是符号或工具人）
                - 每卷至少有1个情感场景（非纯政治博弈——可以是信任的建立与崩塌、牺牲、抉择）
                - 角色之间的对话应展现关系张力，不只是传递信息""";

            case SCIFI -> """
                题材规则（科幻）：
                - 科技设定必须有内在逻辑（不能随意突破已建立规则）
                - 每章至少展示1个科技元素的实际应用
                - 社会结构与科技水平匹配
                - 避免用科技包装的玄幻套路""";

            case FANTASY -> """
                题材规则（奇幻）：
                - 魔法/能力体系有明确限制和代价
                - 世界有独特的地理/种族/文化
                - 冒险驱动而非升级驱动
                - 每章至少1个世界观探索元素""";

            case LITERARY -> """
                题材规则（文学）：
                - 人物内心世界优先于外部情节
                - 每章至少1个主题探讨（通过事件折射）
                - 语言风格注重文学性（意象/隐喻/节奏）
                - 避免类型化的爽点结构""";

            case GENERAL -> """
                题材规则（通用）：
                - 每章必须有明确的戏剧目的
                - 节奏：高潮与低谷交替
                - 伏笔每卷至少2个""";
        };
    }

    /** 章节写作阶段的题材特定节奏规则 */
    public String getChapterWritingRules(GenreProfile genre) {
        if (genre == null) return "";
        String pacing = switch (genre) {
            case CULTIVATION -> "节奏：标准四段式（开场钩子15%→冲突升级40%→高潮爽点30%→悬念结尾15%）";
            case MYSTERY -> "节奏：线索铺设优先。开场可慢热（20%），线索分析占核心（50%），转折（20%），新疑问（10%）";
            case ROMANCE -> "节奏：情感场景为主。情感互动占60%，外部事件30%，内心独白10%。避免过多动作描写";
            case THRILLER -> "节奏：紧凑高压。开场即冲突（10%），危机升级（50%），高潮对抗（30%），新威胁（10%）";
            case HISTORICAL -> "节奏：对话和博弈为主。政治对话/权谋交锋占50%，行动30%，背景铺陈20%。注意：不要每章都是厅堂博弈，适当穿插行动/情感/仪式场景";
            case SCIFI -> "节奏：设定展示与情节并重。科技展示20%，情节推进50%，社会影响20%，悬念10%";
            case FANTASY -> "节奏：冒险探索为主。世界观探索30%，冒险行动40%，角色互动20%，悬念10%";
            case LITERARY -> "节奏：自由节奏。注重内心世界和主题深度，不强制四段式结构";
            case GENERAL -> "节奏：标准四段式（开场钩子→冲突升级→高潮→悬念结尾）";
        };
        // p10-b2: 追加场景分布指导
        String sceneDist = getRecommendedSceneDistribution(genre);
        return pacing + "\n" + sceneDist;
    }

    /** 题材适用的爽点类型建议 */
    public String getClimaxSuggestions(GenreProfile genre) {
        if (genre == null) return "";
        return switch (genre) {
            case CULTIVATION -> "信息差/反预期/智斗/规则利用";
            case MYSTERY -> "信息差（线索揭示）/反预期（真相不同于推测）/智斗（推理对决）";
            case ROMANCE -> "情感反转（敌变友/误解消除）/反预期（告白时机）/信息差（暗恋揭晓）";
            case THRILLER -> "反预期（威胁来源意外）/规则利用（利用环境逃生）/情感反转（背叛者揭示）";
            case HISTORICAL -> "智斗（权谋计策）/信息差（情报优势）/规则利用（制度漏洞）/情感反转（背叛/牺牲）";
            case SCIFI -> "规则利用（科技原理应用）/反预期（设定颠覆）/智斗（技术对决）";
            case FANTASY -> "规则利用（魔法体系）/反预期/智斗/情感反转";
            case LITERARY -> "情感反转/反预期（主题层面的颠覆）";
            case GENERAL -> "信息差/反预期/智斗/情感反转/规则利用";
        };
    }

    /**
     * p10-b2: 题材推荐的场景类型分布。
     * 返回该题材下推荐的主要 SceneType（用于指导场景多样性）。
     */
    public String getRecommendedSceneDistribution(GenreProfile genre) {
        if (genre == null) return "";
        return switch (genre) {
            case CULTIVATION -> "推荐场景分布：ACTION(行动战斗)40% / CONFRONTATION(对抗博弈)30% / REVELATION(揭示转折)20% / TRANSITION(过渡)10%";
            case MYSTERY -> "推荐场景分布：INVESTIGATION(调查推理)50% / REVELATION(揭示转折)20% / CONFRONTATION(对抗博弈)20% / TRANSITION(过渡)10%";
            case ROMANCE -> "推荐场景分布：EMOTIONAL(情感关系)50% / CONFRONTATION(对抗博弈)20% / REVELATION(揭示转折)15% / TRANSITION(过渡)15%";
            case THRILLER -> "推荐场景分布：ACTION(行动战斗)40% / INVESTIGATION(调查推理)25% / REVELATION(揭示转折)20% / CONFRONTATION(对抗博弈)15%";
            case HISTORICAL -> "推荐场景分布：CONFRONTATION(对抗博弈)40% / ACTION(行动战斗)20% / REVELATION(揭示转折)20% / EMOTIONAL(情感关系)10% / CEREMONY(仪式盛典)10%";
            case SCIFI -> "推荐场景分布：INVESTIGATION(调查推理)25% / ACTION(行动战斗)25% / REVELATION(揭示转折)25% / CONFRONTATION(对抗博弈)15% / TRANSITION(过渡)10%";
            case FANTASY -> "推荐场景分布：ACTION(行动战斗)35% / INVESTIGATION(调查推理)20% / EMOTIONAL(情感关系)20% / REVELATION(揭示转折)15% / CEREMONY(仪式盛典)10%";
            case LITERARY -> "推荐场景分布：EMOTIONAL(情感关系)40% / REVELATION(揭示转折)25% / TRANSITION(过渡)20% / CONFRONTATION(对抗博弈)15%";
            case GENERAL -> "推荐场景分布：各场景类型均衡分布，避免任何单一类型连续超过3章";
        };
    }
}
