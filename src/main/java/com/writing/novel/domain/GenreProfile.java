package com.writing.novel.domain;

/**
 * 小说题材类型——用于条件化 prompt 和规则选择。
 */
public enum GenreProfile {
    WUXIA,         // 武侠：江湖恩仇、招式比拼、侠义精神
    CULTIVATION,   // 玄幻/修仙：境界升级、爽点驱动
    MYSTERY,       // 悬疑/推理：线索管理、逻辑严密
    ROMANCE,       // 言情/都市：情感节拍、关系发展
    THRILLER,      // 惊悚/悬疑：危机升级、高压节奏
    HISTORICAL,    // 历史/权谋：政治博弈、史实引用
    SCIFI,         // 科幻：科技设定、未来想象
    FANTASY,       // 奇幻（非修仙）：魔法体系、冒险
    LITERARY,      // 文学：人物内心、主题深度
    GENERAL;       // 通用（默认）

    /** 中文显示名 */
    public String getChineseName() {
        return switch (this) {
            case WUXIA -> "武侠江湖";
            case CULTIVATION -> "玄幻修仙";
            case MYSTERY -> "悬疑推理";
            case ROMANCE -> "言情都市";
            case THRILLER -> "惊悚悬疑";
            case HISTORICAL -> "历史权谋";
            case SCIFI -> "科幻";
            case FANTASY -> "奇幻";
            case LITERARY -> "文学";
            case GENERAL -> "通用";
        };
    }

    /**
     * p17-02: 快节奏题材——保留网文「爽点/四段式/悬念钩子」硬约束与开篇高压。
     * p21-01: WUXIA 移出快节奏集合——传统武侠（非玄幻）节奏舒缓，不强制爽点/四段式；
     * 超自然修炼流属于 CULTIVATION。
     */
    public boolean isFastPaced() {
        return this == CULTIVATION || this == THRILLER || this == MYSTERY || this == GENERAL;
    }
}
