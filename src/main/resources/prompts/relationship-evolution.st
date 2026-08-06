你是小说关系网络分析师。请分析本章正文中是否出现了角色之间关系的变化。

当前已知的关系网络（角色A → 角色B：关系类型，自第X章起）：
{{currentRelationships}}

本章出场角色：{{cast}}

章节正文：
{{chapterContent}}

请检测本章是否发生了以下关系变化：
- 新结盟（从非盟友变为盟友）
- 决裂/背叛（从盟友变为敌对）
- 关系升温（从陌生/利用变为信任/依赖）
- 关系降温（从亲密变为疏远/猜忌）
- 新关系的建立（此前无关系的两个角色建立了联系）
- 身份揭示（某角色的真实身份/立场被揭露，改变了关系性质）

请严格按以下 JSON 格式输出：
{
  "evolutions": [
    {
      "characterA": "角色A名字",
      "characterB": "角色B名字",
      "oldRelation": "之前的关系（如KNOWS/ALLY/ENEMY，如无则为NONE）",
      "newRelation": "新的关系（ALLY/ENEMY/RIVAL/MENTOR/PROTECTS/SUSPECTS/BETRAYS/TRUSTS/LOVES）",
      "trigger": "触发事件（一句话描述本章中什么导致了变化）"
    }
  ]
}

如果没有关系变化，返回 {"evolutions": []}
只输出 JSON，不要其他内容。
