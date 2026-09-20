# 创作约定运行时读取 Implementation Plan

> **For agentic workers:** 使用executing-plans逐项执行；用户已授权实现原方案，每项完成本地main闭环再开始下一项。

**Goal:** NCS-031通过业务SPI读取作者保存的同一份版本化约定，为后续显式注入提供持久来源。

**Architecture:** NovelRepository声明读取合同，SQLite复用AuthorIntentRepository现有append-only数据。无记录返回Optional.empty，不创建伪造默认修订。

**Tech Stack:** Java17/Spring Boot3/SQLite；本机验证Java21。

## Global Constraints

- 每特性独立分支，先失败测试后实现。
- 业务层只依赖NovelRepository接口，不直接依赖SQLite类。
- 本项只增加读取，不改变生成prompt；实际注入在NCS-032～035交付。

### Task 1: 持久读取合同

**Files:** NovelRepository.java、SqliteNovelRepository.java、service/IntentRuntimeReadTest.java。

**Interfaces:** `Optional<AuthorIntent> findAuthorIntent(String projectId)`；返回最新修订，不缓存，不跨项目。

- [x] RED：真实临时SQLite通过AuthorIntentRepository.append存两版；经NovelRepository调用读取，断言第2版视角；重建仓储后仍第2版，另一作品empty。接口尚无方法时用assertDoesNotThrow反射证明缺口，不因测试编译错误伪造RED。
- [x] Run: `mvn test -Dtest=IntentRuntimeReadTest`，预期读取合同缺失断言失败。
- [x] GREEN：接口默认返回Optional.empty兼容测试适配器；SQLite覆写`return new AuthorIntentRepository(cp).findLatest(projectId);`。
- [x] 改为直接类型化测试调用，运行专属测试及`./init.sh`。
- [ ] 显式暂存本项三文件和必要spec/backlog/evidence；审阅差异，提交NCS-031并ff合main。
- [ ] 从main导出干净目录，前端build后compile/test；检查提交祖先关系，登记verified记录和状态提交。

## Subsequent execution

NCS-032外置模板及预算约定渲染；NCS-033写作；NCS-034规划；NCS-035修订核查。每项在开始时依据当时main补充对应实际测试/接口计划，不跨特性堆积业务修改。

## Branch verification

2026-09-20：基线1093/0F/0E/1S；RED IntentRuntimeReadTest 1项1失败（SPI方法不存在）；GREEN 1项通过；分支init 1094/0F/0E/1S，编译及前端build通过。日志 /tmp/ncs031-red.log、/tmp/ncs031-green.log、/tmp/ncs031-final-init.log。
