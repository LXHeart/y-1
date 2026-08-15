# 推荐官自动匹配 / 智能排序 v1 实现计划

**Goal:** 用 marketplace 现有事实生成可解释的确定性 Top N，并完成商家邀请 → 通知中心 → 指定任务报名闭环。

**Architecture:** marketplace 新建 `matching/` 包承载纯评分器、批量候选查询和邀请仓储/控制器；声誉复用 `ReputationService.snapshots`。邀请写 `task_recommender_invitation` 并与 `TaskRecommenderInvited` outbox 原子提交。identity 仅扩展事件模板/收件人映射。前端在商家选中任务时加载推荐列表，通知专用落点携带 taskId 并驱动推荐官任务大厅。

**Tech Stack:** Java 25 / Spring Boot 4 WebFlux + R2DBC / PostgreSQL Flyway / Vue 3 + TypeScript / JUnit + Testcontainers / Vitest

**Spec:** `docs/superpowers/specs/2026-08-15-recommender-matching-design.md`

## Global Constraints

- 只使用 marketplace 现有报名、任务、交付、评分、声誉事实；不使用 LLM，不新增同步跨服务数据调用。
- 候选、平台经验、声誉必须批量读取，禁止 N+1。
- 邀请行 + outbox 同事务；重复邀请幂等且不重复通知。
- 评分器是纯函数，时间由 `Clock` 注入；缺失样本不伪造中性值。
- 每个端点资源级校验 `TaskResourceAuthorization.requireManager`。
- 前端不绕过既有报名规则。
- 每片先补失败测试再实现；完成后只 commit，不 push。

---

### Task 1: 确定性评分与候选查询

**Files:**
- Create: `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/matching/MatchScore*.java`
- Create: `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/matching/RecommenderMatchingRepository.java`
- Create: `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/matching/RecommenderMatchingService.java`
- Test: `.../matching/DeterministicMatchScorerTest.java`
- Test: `.../matching/RecommenderMatchingRepositoryIT.java`（可由 controller IT 覆盖）

**Steps:**

- [x] 1.1 为六维边界、缺样本、总分与稳定并列排序写失败单测。
- [x] 1.2 实现 `DeterministicMatchScorer`，常量化 `deterministic-v1`、权重和分段。
- [x] 1.3 仓储一次查询返回全站历史候选及目标平台已形成履约次数，同时排除当前任务报名者/owner。
- [x] 1.4 service 批量调用 `ReputationService.snapshots`，过滤最低等级，评分、排序后截断 1-100。

### Task 2: 邀请持久化、端点与报名闭环

**Files:**
- Create: `platform-java/services/marketplace-service/src/main/resources/db/migration/V30__task_recommender_invitation.sql`
- Create: `.../matching/TaskRecommenderInvitation.java`
- Create: `.../matching/TaskRecommenderInvitationRepository.java`
- Create: `.../matching/RecommenderMatchingController.java`
- Modify: `.../taskcatalog/ApplicationController.java`
- Test: `.../matching/RecommenderMatchingControllerIT.java`

**Steps:**

- [x] 2.1 写 V30：唯一邀请、JSON 对象约束、`applied_at`、候选/任务索引。
- [x] 2.2 写失败 IT：manager 列表、非 manager 403、六维响应、最低等级/已报名排除、重复邀请幂等、outbox payload。
- [x] 2.3 实现 GET/POST；POST 在同事务内复算候选、冻结快照、append `TaskRecommenderInvited`。
- [x] 2.4 报名成功事务中调用 `markApplied(taskId, accountId)`；IT 断言 `applied_at`。
- [x] 2.5 跑 marketplace 定向测试、全量 test 与 bootJar。

### Task 3: identity 通知消费

**Files:**
- Modify: `platform-java/services/identity-service/src/main/java/com/grassland/identity/notification/NotificationTemplates.java`
- Modify: `platform-java/services/identity-service/src/main/java/com/grassland/identity/notification/NotificationRecipientResolver.java`
- Modify: 对应 notification tests

**Steps:**

- [x] 3.1 先写模板/路由失败测试：`TaskRecommenderInvited` → engagement + `/me/task-invitations` + task payload。
- [x] 3.2 收件人只取 `recommenderAccountId`，不反查 marketplace。
- [x] 3.3 确认 inbox 幂等和既有邮件白名单语义不变；跑 identity notification 定向与全量测试。

### Task 4: 商家推荐列表与邀请操作

**Files:**
- Modify: `src/types/grassland/recommender.ts`
- Modify: `src/composables/useGrasslandMarketplace.ts`
- Create: `src/views/grassland/components/RecommenderRecommendations.vue`
- Modify: `src/views/grassland/GrasslandWorkbench.vue`
- Test: composable/component/workbench tests

**Steps:**

- [x] 4.1 失败测试覆盖 GET/POST 路径、总分/六维/理由渲染、邀请中/已邀请/错误态。
- [x] 4.2 选中 published 任务时并行加载推荐列表；展示紧凑表格与可展开六维证据。
- [x] 4.3 邀请成功就地更新状态，不重载整页；已报名后刷新列表自然移除。

### Task 5: 通知直达指定任务并报名

**Files:**
- Modify: `src/types/notification.ts`
- Modify: `src/stores/notifications.ts`
- Modify: `src/components/NotificationPanel.vue`
- Modify: `src/layouts/DefaultLayout.vue`
- Modify: `src/views/grassland/GrasslandWorkbench.vue`
- Modify: `src/composables/useGrasslandMarketplace.ts`（复用 GET task detail）
- Test: notification store/panel + workbench tests

**Steps:**

- [x] 5.1 `resolveLinkTarget` 接收 payload，为专用 path 生成 `taskId` + recommender side。
- [x] 5.2 layout provide 结构化 grassland 导航目标；旧 anchor 跳转继续兼容。
- [x] 5.3 workbench 收到目标后激活推荐官身份、GET 指定任务、放入 feed/选中、滚到任务大厅。
- [x] 5.4 点击报名仍调用原端点；成功后刷新 applications 和推荐列表状态。

### Task 6: 验证、文档与提交

- [x] 6.1 `:services:marketplace-service:test :bootJar`。
- [x] 6.2 `:services:identity-service:test :bootJar`。
- [x] 6.3 前端定向 Vitest，再跑 `npm run test && npm run typecheck && npm run build`。
- [x] 6.4 更新 `CLAUDE.md`、`项目速览.md`、`docs/草场开发进度与续接指南.md`，只调整本功能状态。
- [x] 6.5 检查 `git diff`、`git status`、提交；不 push。
