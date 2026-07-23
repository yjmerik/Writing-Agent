package com.writing.novel.service;

import com.writing.novel.domain.GenreProfile;

/**
 * 题材检测器——从 Project.tags + concept 推断小说题材。
 * 用于条件化选择 prompt 规则和爽点类型集。
 */
public class GenreDetector {

    /**
     * 从标签和构思推断题材类型。
     * @param tags 逗号分隔的标签（如"玄幻,修仙,逆袭"）
     * @param concept 故事构思文本
     * @return 推断的题材类型
     */
    public GenreProfile detect(String tags, String concept) {
        String text = ((tags != null ? tags : "") + " " + (concept != null ? concept : "")).toLowerCase();

        // 玄幻/修仙
        if (containsAny(text, "玄幻", "修仙", "修真", "仙侠", "境界", "灵气", "丹药", "宗门", "渡劫", "元婴", "炼气")) {
            return GenreProfile.CULTIVATION;
        }
        // 悬疑/推理
        if (containsAny(text, "悬疑", "推理", "侦探", "破案", "凶杀", "谜案", "线索", "真凶", "密室")) {
            return GenreProfile.MYSTERY;
        }
        // 言情/都市
        if (containsAny(text, "言情", "都市", "爱情", "恋爱", "甜宠", "总裁", "豪门", "校园", "青梅竹马")) {
            return GenreProfile.ROMANCE;
        }
        // 惊悚
        if (containsAny(text, "惊悚", "恐怖", "末日", "丧尸", "逃生", "生存", "诡异", "怪谈")) {
            return GenreProfile.THRILLER;
        }
        // 历史/权谋
        if (containsAny(text, "历史", "权谋", "朝堂", "宫斗", "帝王", "将军", "王朝", "乱世", "争霸")) {
            return GenreProfile.HISTORICAL;
        }
        // 科幻
        if (containsAny(text, "科幻", "未来", "星际", "机甲", "赛博", "人工智能", "太空", "外星")) {
            return GenreProfile.SCIFI;
        }
        // 奇幻（非修仙）
        if (containsAny(text, "奇幻", "魔法", "精灵", "龙", "冒险", "勇者", "异世界", "地下城")) {
            return GenreProfile.FANTASY;
        }
        // 文学
        if (containsAny(text, "文学", "现实主义", "乡土", "年代", "家族", "回忆录")) {
            return GenreProfile.LITERARY;
        }

        return GenreProfile.GENERAL;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
