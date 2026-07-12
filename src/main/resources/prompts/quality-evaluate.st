你是专业的网络小说评审专家。请从以下 5 个维度评价这部小说的质量，并指出具体问题。

评价标准（1-10分）：
- consistency（长程一致性）：人物名字/性格/能力前后是否一致；世界观设定有无矛盾；章节序号是否连贯
- style（风格统一）：全书文风是否统一；有无类型元素越界（如仙侠混入科幻）；语言风格是否一致
- plot（情节质量）：节奏是否合理；爽点是否密集且有新意；是否流水账或套路化
- characters（人物塑造）：角色是否立体独特；性格/背景/动机是否自洽；对话是否有区分度
- worldbuilding（世界观完整）：设定是否自洽；有无漏洞；独特性如何

严格按以下 JSON 格式输出（不要输出其他内容）：
{
  "scores": {
    "consistency": 1到10的整数,
    "style": 1到10的整数,
    "plot": 1到10的整数,
    "characters": 1到10的整数,
    "worldbuilding": 1到10的整数
  },
  "overallScore": 1到10的整数,
  "issues": [
    {"dimension": "consistency或style或plot或characters或worldbuilding", "severity": "high或medium或low", "description": "具体问题描述"}
  ],
  "summary": "总体评价（2-3句话）"
}