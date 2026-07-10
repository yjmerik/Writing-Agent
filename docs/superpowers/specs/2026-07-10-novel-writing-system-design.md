# 长篇小说自动写作系统 — 设计文档

- **日期**：2026-07-10
- **状态**：已确认，待生成实现计划
- **作者**：用户 × ZCode 协作设计

---

## 1. 项目概述

一个基于多 Agent 图（Multi-Agent Graph）的长篇小说自动写作系统，面向**中文网络小说**（百万字级、章回制、强设定）。

### 核心能力

根据用户的初始构思，自动生成世界观、风格、人物关系、分层大纲，并产出**内部一致**的完整长篇小说。

### 两种运行模式

| 模式 | 行为 |
|------|------|
| **Solo 模式** | 用户提供高层构思后，系统自动走完全流程，中间几乎不停顿 |
| **交互模式** | 每个关键环节（世界观/人物/大纲/章节）生成后暂停，用户审核、修改、确认后再进入下一阶段 |

两种模式均需支持，用户在创建项目时选择，且**运行中可随时切换**。

### 交付形态

- **BS 架构**：Java（Spring Boot）后端 + 浏览器前端
- **后端先行**：先完成后端完整功能（含可独立验证的 REST + SSE API），前端后续搭建
- **持久化抽象（SPI）**：关系库与向量库均走接口，MVP 用本地实现，云端部署可换实现

### 四大核心痛点（设计重点）

1. **长程一致性**：人物性格/能力不漂移、世界观不矛盾、伏笔能呼应
2. **叙事质量**：网文节奏、爽点、避免流水账
3. **风格统一**：避免不同章节像不同人写的
4. **结构规划**：大纲→卷→章节的多层拆解

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        浏览器前端 (React)                      │
│   项目列表 · 世界观面板 · 人物关系图 · 大纲树 · 章节阅读器        │
│   模式切换(Solo/交互) · 审核 checkpoint · 运行日志              │
└────────────────────────────┬────────────────────────────────┘
                             │ REST + SSE(流式生成/进度推送)
┌────────────────────────────┴────────────────────────────────┐
│                     Spring Boot 后端                          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Orchestrator (编排器/调度核心)             │   │
│  │   · 维护小说生命周期状态机(构思→创作→修订)              │   │
│  │   · 根据状态+事件决定调度哪个 Agent                    │   │
│  │   · 管理 Solo/交互模式与 checkpoint                    │   │
│  └──┬──────────┬──────────┬──────────┬──────────┬────────┘  │
│     ▼          ▼          ▼          ▼          ▼           │
│  WorldBuild  Character   Plotter   Chapter   Continuity     │
│  Agent       Agent       Agent     Writer    Checker        │
│  (Style Guardian, Memory Manager 按需触发)                   │
│     │          │          │          │          │            │
│     ▼          ▼          ▼          ▼          ▼            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              LLM Gateway (多模型路由层)                │   │
│  │   按任务类型路由: 创意用强模型/正文用快模型/校对用小模型 │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │         Memory Layer (三层记忆，一致性核心)            │   │
│  │  ① 结构化知识库(关系库)  ② 滚动摘要   ③ 向量检索       │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
        │                          │
   关系库 SPI                   向量库 SPI
   (SQLite/MySQL/Pg)          (嵌入式/Qdrant/Milvus)
```

### 核心思想

- **Orchestrator 是大脑**：不是固定流水线，而是根据「当前小说处于什么阶段 + 发生了什么事件」动态决定下一步调用谁。例如写到第 50 章时 ContinuityChecker 报告"张三的剑名前后矛盾"，Orchestrator 可回头触发 CharacterDesigner 修正设定。
- **Agent 是技能**：每个 Agent 是无状态的技能单元，接收上下文包 + 产出结构化产物，写入记忆层。Agent 之间不直接调用，全部经 Orchestrator 调度。
- **三层记忆是脊柱**：所有 Agent 读写同一个记忆层，这是百万字一致性的根本。

---

## 3. 持久化抽象层（SPI）

业务/服务层只依赖接口，不知道底层是本地还是云端。通过配置切换实现，业务层零感知。

```
┌─────────────────────────────────────────────────────┐
│              Domain / Service 层（业务逻辑）          │
│         只依赖接口，不知道底层是本地还是云端           │
└──────────┬───────────────────────┬──────────────────┘
           │                       │
   ┌───────▼────────┐      ┌──────▼─────────┐
   │ NovelRepository│      │ VectorStore     │  ← SPI 接口
   │  (关系数据)     │      │  (向量检索)     │
   └───┬────────┬───┘      └──┬──────────┬──┘
       │        │              │          │
  ┌────▼───┐ ┌──▼─────┐  ┌────▼───┐ ┌────▼─────┐
  │ SQLite │ │ MySQL/ │  │ 嵌入式 │ │ Qdrant/  │
  │ (MVP)  │ │ Pg实现 │  │本地(MVP)│ │ Milvus   │
  └────────┘ └────────┘  └────────┘ └──────────┘
   本地开发    云端部署     本地开发    云端部署
```

### 三个核心 SPI 接口

| 接口 | MVP 实现 | 云端实现 | 职责 |
|------|---------|---------|------|
| `NovelRepository` | `SqliteNovelRepository` (JDBC) | `MysqlNovelRepository` / `Postgres...` | 项目/世界观/人物/大纲/章节/摘要的 CRUD |
| `VectorStore` | `EmbeddedVectorStore` (本地文件存向量 + 近邻检索) | `QdrantVectorStore` / `MilvusVectorStore` | 文本块向量化存储、语义相似度检索 |
| `EmbeddingProvider` | 调云 API（如通义/DeepSeek embedding） | 同左或本地模型 | 文本 → 向量 |

### 切换方式

`application.yml` 配置：
```yaml
persistence:
  relational: sqlite      # sqlite | mysql | postgres
  vector: embedded        # embedded | qdrant | milvus
  embedding: cloud-api    # cloud-api | local
```

启动时按配置加载对应 Bean（Spring `@Profile` / `@ConditionalOnProperty`）。

---

## 4. Agent 角色定义（多 Agent 图）

每个 Agent 是无状态技能单元：吃「上下文包」+ 产出「结构化产物」。Orchestrator 调度它们。

| Agent | 触发时机 | 输入 | 产出 | 写入记忆 |
|-------|---------|------|------|---------|
| **WorldBuilder** | 项目初始化 / 用户要求扩展设定 | 构思简介、类型标签 | 世界观条目（地理/势力/力量体系/历史/规则） | 知识库 + 向量 |
| **CharacterDesigner** | 初始化 / 出现新角色 / 设定需修订 | 世界观、剧情需求、已有人物 | 人物卡（性格/能力/动机/外貌/关系/成长弧） | 知识库 + 向量 |
| **Plotter** | 初始化大纲 / 卷规划 / 伏笔追踪 | 世界观、人物、全局主题 | 分层大纲（总纲→卷→章节梗概）、伏笔表 | 大纲库 + 向量 |
| **ChapterWriter** | 写每一章 | 章节梗概、相关人物卡、相关世界观、近期摘要、风格样本 | 章节正文 | 章节库 + 向量 + 触发摘要更新 |
| **ContinuityChecker** | 每写完一章 / 定时审计 | 新章节 + 检索到的相关旧设定 | 矛盾报告（人物/世界观/时间线/伏笔） | 问题日志 |
| **StyleGuardian** | 写作前注入风格锚点 / 写作后检测漂移 | 风格定义、近期章节风格样本 | 风格一致性评分 + 修正建议 | 风格基线 |
| **MemoryManager** | 每写 N 章自动 / 冲突时 | 近期章节全文 | 滚动摘要（事件/状态变化/伏笔进度） | 摘要库 |

### 设计原则

- **Agent 之间不直接调用，全部经 Orchestrator 调度。** 避免循环依赖；Orchestrator 能在每步之间插入 checkpoint；便于加新 Agent（注册到 Orchestrator 即可）。
- **事件驱动的动态调度**（体现方案 B 的"自愈"能力）：
  ```
  ChapterWriter 写完第 50 章
    → ContinuityChecker 检测：发现"张三的佩剑叫霜寒，但第 12 章叫断水"
    → 产出矛盾报告，上报 Orchestrator
    → Orchestrator 决策：调度 CharacterDesigner 修订人物卡（统一为"断水"）
                       + 标记第 12 章需修正
    → (交互模式) 暂停等用户确认；(Solo 模式) 自动执行修正
  ```

---

## 5. 三层记忆策略（一致性核心）

分层解决不同距离的一致性问题：

```
距离当前章节      记忆层                 解决什么
─────────────────────────────────────────────────────────
 近(1~3章)    │ 章节全文(原文缓存)    │ 细节衔接、对话连贯、近期事件
 ↓            │                       │
 中(当前卷)    │ 滚动摘要(每N章压缩)   │ 剧情推进、状态变化、伏笔进度
 ↓            │                       │
 远(全书)      │ 结构化知识库          │ 人设不漂移、世界观不矛盾
              │ + 向量检索(RAG)       │ 按需调取相关设定/旧情节
```

### 写作一章时的上下文组装（Context Assembly）

这是决定质量的关键环节。给 ChapterWriter 的 Prompt 由以下部分拼装：

```
[全局] 风格锚点(StyleGuardian 提供) + 世界观核心规则(精简版)
+ [本卷] 当前卷大纲 + 本卷主线摘要
+ [近期] 前 1~2 章摘要 + 当前章前 ~2000 字原文(衔接)
+ [检索] 向量检索 top-K 相关：本章出场人物卡 + 涉及地点/物品设定 + 相关伏笔
+ [任务] 本章梗概 + 写作要求(字数/爽点/节奏)
```

### 分层理由

- **结构化知识库**：保证"张三是火爆性格"这种硬设定永远不漂——但不是全塞进 prompt（太长），而是按需检索相关条目。
- **滚动摘要**：解决"第 100 章不需要记得第 3 章每个细节，但要记得主角已经升到金丹期"——压缩历史，控制 token。
- **向量检索**：解决"写到某个旧地点时，自动找回当时描写过什么"——语义相关性召回，而非全量加载。

### 摘要更新时机

- ChapterWriter 每写完 1 章，MemoryManager 更新当前卷的滚动摘要（增量）。
- 每写完 5~10 章做一次更深的全卷摘要。

---

## 6. 小说生命周期状态机

Orchestrator 驱动小说在这个状态机上流转，状态决定哪些 Agent 可被触发：

```
                    ┌──────────────┐
         创建项目    │   CONCEIVING  │  用户提供构思简介、类型标签
        ─────────▶ │    (构思)     │  WorldBuilder / CharacterDesigner
                    │              │  / Plotter 产出初始设定
                    └──────┬───────┘
                           │ 设定就绪(三件套:世界观/人物/大纲)
                           ▼
                    ┌──────────────┐
                    │   PLANNING    │  大纲细化:总纲→卷→章节梗概
                    │   (规划)     │  Plotter 主导,生成伏笔表
                    └──────┬───────┘
                           │ 至少 1 卷有完整章节梗概
                           ▼
         ┌─────────────────────────────────┐
         │        WRITING (创作中)          │  ← 核心循环,占 90% 时间
         │  ChapterWriter → ContinuityCheck │
         │  → (发现问题)→ MemoryManager/    │
         │     CharacterDesigner 修正        │
         │  → MemoryManager 更新摘要         │
         └──────────────┬──────────────────┘
                        │ 当前卷章节写完
                        ▼
                 ┌──────────────┐
                 │  VOLUME_DONE │  单卷完结:全卷深摘要、伏笔核对
                 │  (卷完结)    │  → 回 WRITING 写下一卷,或 → FINAL
                 └──────┬───────┘
                        │ 全部卷写完
                        ▼
                 ┌──────────────┐
                 │    FINAL      │  全书完成:终校、生成完整目录
                 │   (完结)     │
                 └──────────────┘
```

### WRITING 循环内部（每写一章的子状态机）

```
准备上下文 → 写正文 → 连续性校验 → 风格检测 → 更新摘要
   │           │          │            │           │
 MemoryMgr   Chapter    Continuity  Style      MemoryMgr
 组装Context  Writer    Checker     Guardian    写摘要
            (流式输出)   └─矛盾─▶ Orchestrator 决策修正
```

### 关键规则

- 状态持久化到关系库（重启可恢复）
- 每个状态转移都可挂 checkpoint（交互模式在此暂停）
- WRITING 是个长循环，每章是一个原子单位，可中断/续写

---

## 7. Solo / 交互模式与 Checkpoint

两种模式共用同一套状态机，区别只在「状态转移时是否暂停等用户」。

### 4 个 Checkpoint 位置

| 检查点 | Solo 模式 | 交互模式 |
|--------|----------|---------|
| 初始设定(世界观/人物/大纲)生成后 | 自动采纳 | 暂停，用户审核可改 |
| 每卷大纲细化后 | 自动采纳 | 暂停，用户可调整章节梗概 |
| 每章写完 | 自动进入下一章 | 暂停，用户可改写/插入情节 |
| 发现矛盾需修正 | 自动修正 | 暂停，用户确认修正方案 |

### 模式可在运行中切换

存为项目属性 `mode`：
- Solo → 交互：下一个 checkpoint 立即生效
- 交互 → Solo：跳过等待直接推进

### Checkpoint 实现

- 每个状态转移前，Orchestrator 查询 `mode`；若交互模式则把"待审核产物"存库，状态置为 `PAUSED_AT_CHECKPOINT`
- 前端轮询或 SSE 推送 checkpoint 事件
- 用户 approve / modify / reject 后，Orchestrator 继续

### Resume（断点续写）

因为状态机持久化，进程可随时重启从断点继续——这对百万字生成（必然要跑很多天）至关重要。

---

## 8. API 设计

REST 为主，SSE 处理流式生成与进度推送。**后端先行：所有 API 可脱离前端独立验证（curl/Postman）。**

### 8.1 项目与运行控制

```
POST   /api/projects                  创建项目(构思简介+类型标签+模式)
GET    /api/projects                  项目列表
GET    /api/projects/{id}             项目详情(含当前状态/进度)
PUT    /api/projects/{id}/mode        切换 Solo/交互模式
DELETE /api/projects/{id}             删除项目

POST   /api/projects/{id}/run         启动/继续编排(从当前状态推进)
POST   /api/projects/{id}/pause       暂停
GET    /api/projects/{id}/status      当前状态+下一个checkpoint(若有)
```

### 8.2 设定产物（世界观/人物/大纲）

```
GET    /api/projects/{id}/worldbuilding          世界观全量
PUT    /api/projects/{id}/worldbuilding          手动修订(交互模式)

GET    /api/projects/{id}/characters             人物列表
GET    /api/projects/{id}/characters/{charId}    人物卡详情
PUT    /api/projects/{id}/characters/{charId}    修订人物卡

GET    /api/projects/{id}/outline                大纲树(总纲→卷→章)
PUT    /api/projects/{id}/outline/volumes/{vId}  修订卷/章节梗概
```

### 8.3 章节与阅读

```
GET    /api/projects/{id}/chapters               章节列表(分页)
GET    /api/projects/{id}/chapters/{cId}         章节正文
PUT    /api/projects/{id}/chapters/{cId}         手动改写(交互模式)
POST   /api/projects/{id}/chapters/{cId}/regenerate  重新生成某章
```

### 8.4 Checkpoint 审核（交互模式）

```
GET    /api/projects/{id}/checkpoints            待审核列表
POST   /api/projects/{id}/checkpoints/{cpId}/resolve   approve/modify/reject
```

### 8.5 流式与进度（SSE）

```
GET    /api/projects/{id}/stream                 SSE 推送:
                                                 - chapter_token(逐字流)
                                                 - agent_event(Agent起止)
                                                 - checkpoint_reached
                                                 - progress(状态变化)
                                                 - error
```

### 8.6 记忆与诊断（辅助/调试）

```
GET    /api/projects/{id}/memory/summary         当前滚动摘要
POST   /api/projects/{id}/memory/search          语义检索(向量)调试用
GET    /api/projects/{id}/continuity/issues      连续性矛盾报告
GET    /api/projects/{id}/style/profile          风格基线
```

### 设计原则

- 每个产物有版本——`PUT` 修订时保留历史版本，支持回滚
- SSE 单向推送；用户操作走 REST，不双向

---

## 9. MVP 范围

后端先行 + MVP 优先。MVP 目标：跑通**「输入构思 → 生成设定三件套 → 连续写出若干章，且人物/世界观不矛盾」**的核心闭环。

### MVP 必做（本次交付）

| 层 | MVP 内容 |
|----|---------|
| **持久化 SPI** | `NovelRepository`(SQLite实现) + `VectorStore`(嵌入式本地实现) + `EmbeddingProvider`(调云API) |
| **Agent** | 7 个全部实现：WorldBuilder / CharacterDesigner / Plotter / ChapterWriter / ContinuityChecker / StyleGuardian / MemoryManager |
| **记忆三层** | 知识库 CRUD + 滚动摘要 + 向量检索，全部实现（一致性核心，不可砍） |
| **Orchestrator** | 状态机 + 事件驱动调度 + Solo/交互模式 + checkpoint + 断点续写 |
| **API** | 项目/设定/章节/checkpoint/SSE 全部实现，可脱离前端验证 |
| **LLM Gateway** | 多模型可配置，按任务路由 |

### MVP 后（明确标记，不在本次实现）

- 前端页面（后端完成后再搭建）
- 章节级「重新生成/改写」的高级编辑流
- 多用户/权限/云端部署配置
- Agent 自愈的复杂冲突仲裁（MVP 的 ContinuityChecker 报告 + 简单自动修正）

### MVP 验收标准

1. 创建项目（构思 + 标签 + Solo 模式）→ 自动生成世界观/人物/大纲
2. 启动 run → 连续写出 ≥5 章，每章 ~2000-3000 字
3. 人物性格、世界观设定、伏笔在 5 章内**无矛盾**（ContinuityChecker 报告 0 严重问题）
4. 切换到交互模式 → 在 checkpoint 处暂停、可 approve 后继续
5. 进程重启后可从断点续写
6. 所有功能可通过 REST API + SSE 验证（不依赖前端）

---

## 10. 技术选型汇总

| 层 | 选型 | 备注 |
|----|------|------|
| 后端框架 | Spring Boot 3 + Java 17 | |
| 构建 | Maven 或 Gradle | |
| 关系库 SPI | `NovelRepository` 接口 | |
| 关系库 MVP | SQLite (JDBC) | 本地文件，零配置 |
| 向量库 SPI | `VectorStore` 接口 | |
| 向量库 MVP | 嵌入式本地实现（本地文件 + 近邻检索） | |
| Embedding SPI | `EmbeddingProvider` 接口 | |
| Embedding MVP | 调云 API | |
| LLM Gateway | 多模型可配置，按任务路由 | 创意用强模型/正文用快模型/校对用小模型 |
| 前端 | React（后端完成后搭建） | |
| 通信 | REST + SSE | |

---

## 11. 演进路径（设计预留，不在 MVP 实现）

- **Agent 架构演进**：当前多 Agent 图已具备 Orchestrator 动态调度能力。后续可加入更复杂的冲突仲裁 Agent、读者反馈模拟 Agent。
- **云端部署**：替换 SPI 实现（MySQL/Postgres + Qdrant/Milvus），业务层零改动。
- **前端**：后端 API 稳定后搭建 React 前端。
