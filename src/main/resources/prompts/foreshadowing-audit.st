你是小说叙事结构分析师。请对以下未回收的伏笔进行重要性评估，并为重要伏笔推荐回收章节。

小说概要：
{{concept}}

全书共 {{totalChapters}} 章，当前已写到第 {{currentChapter}} 章。

待评估伏笔列表（编号 | 描述 | 埋设章节）：
{{foreshadowingList}}

评估标准：
- HIGH：主线关键信物/核心秘密/身份悬念/核心谜团——这种伏笔如果被遗忘会严重影响故事完整性，必须在故事结束前回收
- MEDIUM：支线悬念/次要秘密——丰富了故事但不回收也能勉强接受
- LOW：氛围暗示/环境细节/一次性线索——仅为增加质感，不回收无妨

对每条伏笔，请判断：
1. priority：HIGH / MEDIUM / LOW
2. mustResolve：true（必须回收，HIGH 级别默认 true）/ false
3. expectedResolveChapter：推荐的回收章节号（仅对 HIGH 级别填写，应为 currentChapter 之后、totalChapters 之前或附近）

请严格按以下 JSON 格式输出：
{
  "audits": [
    {"index": 1, "priority": "HIGH", "mustResolve": true, "expectedResolveChapter": 20, "reason": "判断理由"}
  ]
}

注意：index 必须与输入列表中的编号对应。只输出 JSON，不要其他内容。
