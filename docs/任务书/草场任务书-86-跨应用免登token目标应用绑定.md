# 开发规格：跨应用免登 token 目标应用绑定（audience + origin 校验 + URL 停留收敛）

> 模板版本：2.4.0 ｜ 最近修订日期：2026-09-06
> 任务编号：86 ｜ 任务书版本：v1.0 ｜ 创建/更新日期：2026-09-06
> 规划模型/负责人：ZCode（规划与验收）／用户（最终验收与发布授权） ｜ 目标仓库：y-1（/Users/LXH/claude/y-1） ｜ 当前分支：main（仅记录，不自动创建）
> 代码基线：d39ae2653fe0b2190b642aa6f867bb52e88d5563 ｜ 未提交改动：仅 `docs/任务书/草场任务书-85-用户协议与隐私政策占位页.md`（未跟踪，与本任务无文件重叠） ｜ 本次事实核验日期：2026-09-06
> 文档状态：READY_FOR_IMPLEMENTATION ｜ 目标执行者：能力较弱的编码模型 ｜ 任务卡总数：3
> 执行顺序：C-01 → C-02 → C-03（C-01/C-02 无共享文件、理论上可并行；AUTO_CHAIN 默认串行。C-03 依赖 C-01+C-02 的代码交付物，必须最后）
> 执行模式：AUTO_CHAIN

---

## 0. 执行协议（受更高优先级指令与仓库硬约束约束）

按模板 §0 原文执行，本任务无豁免。补充两点本任务专属强调：

1. 本任务涉及**安全语义**（认证 token 的校验顺序与烧毁规则）。§5.3 的判断顺序与 §6.6 的不变量是红线，执行模型 MUST NOT 调整校验顺序「优化代码」，也不得把 401 统一文案拆成多个可区分错误的响应（防止探测面）。
2. Redis nonce 载荷格式是内部实现（` accountId|source|audience` 管道分隔），MUST NOT 因此引入 JSON 序列化器或新建数据表。

### 0.3 完成定义（DoD）

按模板 §0.3 原文执行。本任务无 UI 视觉改动（两壳只改一行调用与注释），§8.8 双主题截图义务 N/A（理由见 §8），但 C-03 的浏览器冒烟截图仍是必需证据。

---

## 1. 目标与范围

### 1.1 一句话目标

跨应用免登的一次性 token 绑定目标应用（audience）与目标 origin：只有「签发时指定的目标应用、来自允许 origin 的核销请求」才能兑换建会话，同时把 token 在 URL query 中的暴露窗口收敛到脚本首拍，并用场景测试锁死 referrer/错误日志/浏览器历史三类泄漏面。

### 1.2 背景与价值

当前 token（任务书 #76 卡 A 引入）有 TTL 5 分钟 + GETDEL 原子单次核销，但**不绑定目标应用**（`CrossAppTokenController.java:28` 类注释明示）：一旦 token 经浏览器历史、代理/监控日志或错误上报（URL 常被整体记录）泄露，有效期内任何知道 exchange 端点的调用方都可兑换得到目标应用会话。本次给 token 加 audience 绑定与 origin 门禁、缩短 URL 停留窗口，把「泄露即可用」收紧为「泄露且必须在允许 origin 的目标应用上下文、且抢在本人之前兑换」。受益方：草场用户端 ⇄ AI 创作中心的全部免登跳转用户。

### 1.3 范围内（明确交付）

| 需求编号 | 必须交付的可观察行为 | 负责卡号 | 对应验收编号 |
|---|---|---|---|
| REQ-001 | `POST /api/auth/cross-app-tokens` 请求体必填 `audience ∈ {grassland, ai}`（缺失/空白/未知值/错误大小写 → 400）；签发的 Redis 载荷为 `accountId\|source\|audience`，source 由服务端按 Origin 头与配置推导（命中→对应应用，否则 `unknown`），仅作溯源不入校验 | C-01 | AC-001、AC-002 |
| REQ-002 | `POST /api/auth/cross-app-tokens/exchange` 请求体必填 `audience`；audience 与 token 载荷不匹配 → 401（统一文案）且 token 已烧毁（后续正确 audience 重试也 401）；载荷无法解析（含旧格式裸 accountId）→ 401 | C-01 | AC-003、AC-004 |
| REQ-003 | exchange 在 GETDEL 之前校验 Origin：该 audience 配置了非空 origin 列表且请求携带 Origin 头时，必须与列表精确匹配（scheme+host+port 字符串相等），否则 401 且**不烧毁** token（换正确 Origin 重试可成功）；Origin 头缺失放行；audience 无配置（dev 同源形态）跳过该校验 | C-01 | AC-005 |
| REQ-004 | 前端 `consumeCrossAppTokenFromUrl(audience)` 先 `history.replaceState` 清掉 `xat` 再发 exchange 请求（URL 停留窗口收敛到脚本首拍）；跳转签发请求体带 `{audience: 目标应用}`；草场壳传 `grassland`、AI 壳传 `ai` | C-02 | AC-006、AC-007 |
| REQ-005 | 场景测试与部署接线：①单测断言 exchange 请求发出时 URL 已无 `xat`（referrer 机制）；②单测断言失败路径 console 无 token 明文（错误日志）；③e2e 浏览器历史 back/forward 不回溯 `?xat=`；④e2e API 级断言错 audience 401+烧毁；⑤compose 给 identity-service 注入 `GRASSLAND_ORIGIN`/`AI_APP_ORIGIN`，`.env.docker.example` 注释同步，e2e 既有签发调用补 body | C-03 | AC-008、AC-009、AC-010 |

### 1.4 范围外（明确不做，遇到也不处理）

- 不给治理台（ops.html）接入免登：`ops` 不是 audience 枚举值，`useCrossAppJump` 不出现在治理台。未来接入需另立任务书扩展枚举+配置。
- 不改 Edge 路由登记（路径、method、flag 均不变），不动 `edge-bff` 任何文件。
- 不改 Redis 存储形态（仍是 nonce key + TTL，不建表、不加 Flyway 迁移），不改 TTL 默认 300s，不改审计表结构与审计动作。
- 不改会话机制（`SessionWriter`、cookie 策略、`y1.sid` 语义），不改 `loginFor` 建会话链路与 403 停用文案。
- 不做旧载荷双读兼容（部署窗口 ≤TTL 的存量裸值 nonce 自然失效，见 §7.3）。
- 不新增 `Referrer-Policy` 响应头（浏览器默认 `strict-origin-when-cross-origin` 已限制跨源 Referer 泄漏；本任务以「先清参再请求」从机制上消除，加头属另一项部署面变更）。
- 不写任何 UI 样式/模板改动（两壳仅一行函数调用签名 + 注释）。

### 1.5 不许顺手修

- `src/ai/AiAppLayout.vue` 中 `stripUrlParams(['entry','org','store','title','platform'])` 的既有清参时序（门店深链参数在 entry 组装后清理）——保持原样。
- `useCrossAppToken.ts` 签发失败「不带 token 跳转」的降级路径（`useCrossAppToken.ts:66-67`）——保留。
- `npm run lint` 全量退出码 1 的既有基线失败（`scripts/verify-task78.mjs` no-undef ×10 + any 警告 ×2，2026-09-06 记录在案待拍板）——只记录不修，见 §2.7。
- e2e spec 中与本任务无关的用例（游客错 token、门店深链锁定等只按需补 audience 字段，不重写）。

### 1.6 用户、入口与已知限制

- 用户/调用方：草场用户端（`index.html`）已登录用户 ↔ AI 创作中心（`ai.html` 独立 origin）的双向跳转；后端调用方仅 `identity-service` 两个端点。
- 使用前置条件：源应用已有登录会话（签发）；exchange 无需既有会话（token 即凭证）。
- 已知且允许保留的限制：
  - 非浏览器攻击者可伪造/省略 Origin 头，origin 门禁对其无效——audience 绑定与 origin 门禁防的是「错误的第一方应用上下文兑换」与配置面收紧，一次性 GETDEL + TTL 300s 仍是抗重放主体。威胁模型说明见 D-04。
  - 本地 compose 栈默认 origin 为 `http://127.0.0.1:8080/8084`，用 `localhost` 域名访问会因 Origin 不匹配被 401——属预期（冒烟步骤统一用 `127.0.0.1`，需多域名时配置逗号分隔列表）。
  - 滚动部署窗口内（≤300s）存量旧格式 nonce 一律 401，用户重新从源应用跳转即可。
- 验收例外：无。

---

## 2. 仓库上下文

### 2.1 目标端（勾选）

| 勾 | 端 | HTML 入口 | 相关目录 |
|---|---|---|---|
| [x] | 用户端 | `index.html` | `src/layouts/DefaultLayout.vue`（消费点之一） |
| [ ] | 治理台 | `ops.html` | 不涉及（见 §1.4） |
| [x] | AI 创作中心 | `ai.html` | `src/ai/AiAppLayout.vue`、`src/ai/main.ts` |
| [ ] | 共享组件 | 双端引用 | 不涉及新组件 |
| [x] | 后端 | — | `platform-java/services/identity-service/src/main/java/com/grassland/identity/auth/` |
| [x] | 构建/脚本 | — | `docker-compose.yml`、`.env.docker.example`、`tests/e2e/` |
| [ ] | 文档/契约 | — | PRD 不描述 token 内部机制，无文档同步（§2.9） |

### 2.2 设计规范路由

本任务无任何视觉/样式改动（§8 N/A），不触发 DESIGN.md 义务；若执行中发现必须改样式，属范围外，按 §13 报告。

| 范围 | 规范文件 | 本任务关系 |
|---|---|---|
| 用户端/AI 壳 | 根 `DESIGN.md` | 不涉及（无样式改动） |
| 治理台 | `src/ops/DESIGN.md` | 不涉及 |

### 2.3 入口位置

- 前端消费入口：`src/layouts/DefaultLayout.vue:275`（`onMounted` 内 `await consumeCrossAppTokenFromUrl()`，草场壳）；`src/ai/AiAppLayout.vue:187`（同构，AI 壳）。
- 前端签发/跳转入口：`src/composables/useCrossAppToken.ts` — `useCrossAppJump()` 返回 `jumpToAiApp`/`jumpToGrassland`；调用方 `src/views/ai-center/AiCreationCenter.vue:367`、`src/views/ai-center/AiCenterExternalRedirect.vue:29`、`src/components/StoreMediaManager.vue:28`、`src/ai/AiAppLayout.vue:159`、`src/layouts/DefaultLayout.vue:247`（全部经 composable，本任务不改这些调用点）。
- Java 路由链：`edge-bff` application.yml 路由（`/api/auth/cross-app-tokens`、`/api/auth/cross-app-tokens/exchange`，均 POST、exact、upstream=identity、flag 默认 true）→ `CrossAppTokenController.issue/exchange` → `CrossAppTokenStore`（Redis）→ `SessionWriter.createSession`。Edge 链零改动。
- 数据/测试入口：Redis（无表）；`CrossAppTokenControllerIT`（自备 PG+Redis 容器）；`src/composables/useCrossAppToken.test.ts`；`tests/e2e/ai-creation-center.spec.ts`。

### 2.4 相关现有文件

| 文件 | 相关符号 | 当前职责 | 本次关系 |
|---|---|---|---|
| `platform-java/services/identity-service/src/main/java/com/grassland/identity/auth/CrossAppTokenController.java` | `issue`、`exchange`、`loginFor`、`CrossAppTokenExchangeRequest` | 签发/核销端点 | 必须修改（C-01） |
| `platform-java/services/identity-service/src/main/java/com/grassland/identity/auth/CrossAppTokenStore.java` | `issue(String)`、`exchange(String)`、`wellFormed`、`generate` | Redis nonce 存取 | 必须修改（C-01，载荷化） |
| `platform-java/services/identity-service/src/main/java/com/grassland/identity/auth/CrossAppAudienceOrigins.java` | —（不存在） | — | 新建（C-01，配置+匹配器） |
| `platform-java/services/identity-service/src/main/resources/application.yml` | `identity.cross-app-token` 块（约 124-126 行） | TTL/key-prefix 配置 | 必须修改（C-01，加 audience-origins） |
| `platform-java/services/identity-service/src/test/java/com/grassland/identity/auth/CrossAppTokenControllerIT.java` | 全部用例 + `seedNonce`/`signCookie`/`issue` 辅助 | IT | 必须修改（C-01） |
| `platform-java/services/identity-service/src/test/java/com/grassland/identity/auth/CrossAppTokenStoreTest.java` | —（不存在） | — | 新建（C-01，载荷编解码单元） |
| `platform-java/services/identity-service/src/test/java/com/grassland/identity/auth/CrossAppAudienceOriginsTest.java` | —（不存在） | — | 新建（C-01，匹配器单元） |
| `src/composables/useCrossAppToken.ts` | `consumeCrossAppTokenFromUrl`、`useCrossAppJump`、`stripUrlParams` | 免登前端全部逻辑 | 必须修改（C-02） |
| `src/composables/useCrossAppToken.test.ts` | 三个 describe | 前端单测 | 必须修改（C-02） |
| `src/layouts/DefaultLayout.vue` | `onMounted` 第 275 行 | 草场壳消费 | 必须修改（C-02，一行+注释） |
| `src/ai/AiAppLayout.vue` | `onMounted` 第 187 行 | AI 壳消费 | 必须修改（C-02，一行+注释） |
| `docker-compose.yml` | `identity-service.environment`（66-88 行块） | 部署 env | 必须修改（C-03，+2 env） |
| `.env.docker.example` | 12-15 行 origin 注释 | env 模板 | 必须修改（C-03，注释扩写） |
| `tests/e2e/ai-creation-center.spec.ts` | `跨应用免登与门店深链` describe | e2e | 必须修改（C-03） |
| `src/lib/app-config.ts` | `aiAppHref`、`grasslandAppHref` | origin 运行时配置 | 只读参考（不改） |
| `src/composables/grassland-http.ts` | `request` | 站内请求信封 | 只读参考（不改） |
| `src/ai/AiAppLayout.test.ts` | `stubFetch`、跳转用例（142 行） | 壳单测 | 只读参考（签名变更经核实不连坐：断言仅 `POST /api/auth/cross-app-tokens` 方法+URL，不检 body；挂载 URL 无 `xat` 则 consume no-op。若实测仍失败按 §13 报告，不得自行改断言） |
| `platform-java/services/edge-bff/src/main/resources/application.yml` | 119-127 行两条路由 | Edge 登记 | 只读参考（零改动） |
| `platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/{JavaRouteManifestGateTest,RouteOwnershipContractTest}.java` | 路由门禁 | 只读参考（零改动） |
| `docs/任务书/草场任务书-76-AI创作中心独立应用.md` | 卡 A 决策 | 原始设计 | 只读背景（历史文档，以当前代码为准） |

### 2.5 当前行为（已对照源码核实）

1. 已登录用户在源应用点跳转（如草场头部「AI 创作」）→ `useCrossAppJump.jump()` 调 `POST /api/auth/cross-app-tokens`（无 body）→ 后端 `accounts.resolve` 鉴权后 `store.issue(accountId)`：Redis `SETNX key=前缀+43位随机串, value=accountId, TTL=300s` → 响应 `{token, expiresInSeconds}`。
2. 前端拼目标 URL `?xat=<token>`（参数名定死）→ `window.location.href` 整页跳转。
3. 目标壳 `onMounted` 调 `consumeCrossAppTokenFromUrl()`：读 `location.search` 的 `xat` → `POST /api/auth/cross-app-tokens/exchange` body `{token}` → 后端 `wellFormed` 形态门禁 → `GETDEL` 原子取值 → 返回 accountId → `loginFor`：查用户、停用 403、`SessionWriter.createSession` 建 `y1.sid` cookie、落两条审计（签发+核销，无 token 明文）→ 响应 `{user}`。
4. 前端成功/失败**都**在请求返回后 `history.replaceState` 清掉 `xat`；失败落游客态由登录入口接住。
5. 未登录或签发失败：不带 token 直接跳，目标方登录页/游客态兜底。

### 2.6 当前问题

- 现状：token 只绑 accountId，不绑目标应用/origin（`CrossAppTokenController.java:28` 注释自证）；核销前 `xat` 全程停留在 URL。
- 问题：token 若经浏览器历史、代理/监控日志、错误上报（记录完整 URL）泄露，有效期内任何调用方可在任一应用兑换。
- 影响：草场⇄AI 全体免登用户（会话被第三方在 TTL 窗口内抢占建立）。
- 根因：#76 设计时按「两 origin 打同一后端、cookie jar 天然隔离」省略了 audience 维度；URL 清参发生在核销请求之后而非之前。

### 2.7 基线与来源核验

| 项目 | 已核实内容/证据 |
|---|---|
| 指令与设计 | `AGENTS.md`（UI 规则——本任务无 UI 改动不触发）；本任务书模板 v2.4.0 |
| 版本与构建 | `package.json` scripts（test/typecheck/lint/e2e/e2e:ci/seed）；`platform-java/` Gradle wrapper + `services:identity-service` 模块（基线命令实跑验证） |
| 架构与业务决策 | 任务书 #76 卡 A（原始设计，历史文档）；当前代码以本表以下行为准 |
| 工作区 | `git rev-parse HEAD` = d39ae2653fe0b2190b642aa6f867bb52e88d5563；`git status --short` 仅 1 个未跟踪文件（#85 任务书），与本任务零重叠 |
| 测试基线 | ①`npm run test -- src/composables/useCrossAppToken.test.ts`：8/8 PASS（2026-09-06 本机实跑）；②`./gradlew :services:identity-service:test --tests '...CrossAppTokenControllerIT'`：BUILD SUCCESSFUL（2026-09-06 本机实跑，Docker/Testcontainers 可用）。全量门禁本次未跑（NOT_RUN），最近全量绿证据为 #84 批次（2026-09-06，1426 测试）。`npm run lint` 全量退出码 1 为既有基线失败（`verify-task78.mjs` no-undef ×10 + any ×2，见 §1.5） |
| 复用检查 | 站内请求复用 `grassland-http.request`（不新建 fetch 封装）；origin 运行时配置复用部署变量 `GRASSLAND_ORIGIN`/`AI_APP_ORIGIN`（nginx `app-config.js` 同源变量，`docker-compose.yml:28-29`、`scripts/ci-e2e.sh:24-25`）；载荷用管道分隔字符串而非 JSON——避免 ObjectMapper bean 依赖不确定性（intelligence-service 无该 bean 的教训） |

### 2.8 事实、决策与示例的区分

按模板 §2.8 标记表执行。本文所有 `FACT` 均有 §2.4/§2.5/§2.7 的源码或实跑证据；`DECISION` 集中在 §3 与 §5；示例 token 均为合成串（`Smp1e…` 形态 40-60 位），不是真实凭据。

### 2.9 影响面与兼容面

| 影响面 | 是否受影响 | 具体对象 | 兼容要求 | 验证方式 |
|---|---:|---|---|---|
| 页面/路由 | 否 | 两壳 URL 消费顺序变化 | 刷新/返回行为不变（清参仍 replaceState） | C-02 单测 + C-03 e2e |
| 公共 HTTP 契约 | 是 | issue 加必填 body 字段 `audience`；exchange 加必填 body 字段 `audience`；新增 400「无效的目标应用」 | 混合版本降级见下方矩阵；路径/method/信封不变 | C-01 IT |
| 数据库/缓存 | 是 | Redis nonce value 格式 `accountId` → `accountId\|source\|audience` | 存量裸值 ≤300s 窗口内自然失效（401），无回填 | C-01 IT（legacy 用例） |
| 权限/身份 | 是 | exchange 增加 audience+origin 门禁 | 越权兑换 401 且烧毁/不烧毁语义见 §5.3 | C-01 IT |
| 计费/积分/资金 | 否 | — | — | N/A |
| 部署/配置 | 是 | compose identity 块 +2 env；application.yml +2 配置键；`.env.docker.example` 注释 | 前后端同仓同批部署（项目惯例）；env 缺省回落 yml 默认值 | C-03 V-007/V-008 |
| 文档/状态 | 否 | PRD 不描述 token 内部机制；`docs/status.yaml` 无对应条目 | — | N/A |

混合版本兼容矩阵（FACT，按源码与 Spring Boot 默认行为推演，未实测混合部署）：

| 组合 | 行为 | 依据 |
|---|---|---|
| 新前端 + 旧后端 | 完全可用（退化为无绑定）：旧 issue 不读 body；旧 exchange 的 Jackson record 忽略未知字段 `audience`（Spring Boot 默认关闭 FAIL_ON_UNKNOWN_PROPERTIES） | `CrossAppTokenController.java`（旧）无 body 参数 |
| 旧前端 + 新后端 | 可用但无免登：issue 400（缺 audience）→ `useCrossAppToken.ts:66-67` 既有 catch → 不带 token 跳 → 目标方登录页/游客态接住 | 既有降级路径不变 |

---

## 3. 技术决策（已定案，执行期不得更改）

| 决策项 | 结论 |
|---|---|
| 语言/框架/版本 | 后端 Java 25 + Spring Boot 4（identity-service，WebFlux/R2DBC/Reactive Redis）；前端 Vue 3 + TypeScript + Vitest；不引入新框架 |
| 新增依赖 | 无（MUST NOT 新增任何包） |
| 文件布局 | 见 §9.1 白名单（7 个 Java/yml + 4 个前端 + 3 个部署/e2e，共 14 个写入文件，其中 3 个新建） |
| 错误处理策略 | 后端维持控制器内 `Mono<ResponseEntity<Map>>` 直返 + 既有 `@ExceptionHandler`（IdentityException→status/message、Store 异常→503）；核销一切失败统一 401 文案，不拆分错误码 |
| 命名约定 | 后端：`CrossAppAudienceOrigins`（组件）、`CrossAppTokenStore.CrossAppTokenPayload`（嵌套 record）、`CrossAppTokenIssueRequest`（控制器内 record）；前端：`CrossAppAudience` 类型名、`audience` 参数名。测试类名 = 被测类名 + Test/IT（仓库惯例） |
| 风格参照 | 后端照 `CrossAppTokenStore.java`（@Value 构造注入、javadoc 风格）；前端照 `src/composables/useCrossAppToken.ts`（中文块注释、注释引用任务书编号） |
| 配置变更 | 新增 `identity.cross-app-token.audience-origins.grassland`（env `GRASSLAND_ORIGIN`，默认空）与 `.ai`（env `AI_APP_ORIGIN`，默认空）；逗号分隔多值；空=该 audience 跳过 origin 校验。compose identity 块透传两个 env（默认与 nginx 块一致：`http://127.0.0.1:8080/8084`） |
| 兼容/发布 | 前后端同批部署（同仓镜像批次）；存量 nonce ≤300s 失效窗口接受；回滚=回滚镜像批次即可（Redis key 兼容，旧代码读新 value 会把它当 accountId → 用户查找失败 → 401，安全降级为无免登，不炸会话） |

### 决策记录

#### D-01：token 载荷绑定 audience，格式为管道分隔字符串

- 决策：Redis value 从裸 `accountId` 改为 `accountId|source|audience`；解析按 `split("|")` 三段非空，任何其他形态（含旧裸值、两段、四段、空段）按无效处理 → 401。
- 原因：UUID 不含管道符，格式自证；不引入 JSON 序列化器依赖。
- 放弃方案：JSON 载荷（ObjectMapper bean 可用性有不确定性）；裸值+第二 key 存 audience（两次 Redis 往返，破坏 GETDEL 单命令原子语义）。
- 允许执行模型修改：否

#### D-02：audience 枚举 = `grassland` | `ai`（小写，大小写敏感）

- 决策：合法值写死于 `CrossAppAudienceOrigins.AUDIENCES = List.of("grassland", "ai")`；请求侧先 trim 再匹配；ops 不在列。
- 原因：当前 `useCrossAppJump` 仅存在于草场⇄AI 两侧；治理台有独立登录，无免登需求。
- 放弃方案：把 ops 预置进枚举（无消费方，扩大枚举面无意义）。
- 允许执行模型修改：否

#### D-03：issue 增加 body `{audience}`（反序列化非必填，业务校验必填），source 由服务端从 Origin 推导

- 决策：`@RequestBody(required = false) Mono<CrossAppTokenIssueRequest>`——认证 401 仍优先于 body 校验（保住「无 body 未登录 → 401 请先登录」既有用例）；已登录但 audience 缺失/空白/未知 → 400「无效的目标应用」。source=`audienceOrigins.audienceOf(Origin头)`（命中配置→对应应用，否则 `unknown`），入载荷仅作溯源，不参与校验。
- 原因：required=true 会让空 body 在鉴权前被 Spring 拒 400，破坏既有契约；source 让前端自报（可伪造）没有意义，服务端从配置推导才可信。
- 放弃方案：前端在 issue body 同时自报 source（可伪造、无校验价值）。
- 允许执行模型修改：否

#### D-04：exchange 校验顺序固定；Origin 缺失放行；不匹配不烧毁、audience 错配烧毁

- 决策：顺序为 ①token 形态（401）→ ②audience 枚举（400）→ ③Origin 门禁（401，不触 Redis）→ ④GETDEL+载荷解析+audience 匹配（任一失败 401，token 已烧）。Origin 头存在且该 audience 配置了非空列表时精确匹配（scheme+host+port 字符串相等，大小写敏感）；Origin 缺失放行。
- 原因：①在②前保住既有用例「`{}` 无 token → 401」；③在④前避免合法用户被烧 token（代理剥头等场景可重试）；Origin 缺失放行是因为部分浏览器（Safari 历史版本）同源 POST 不带 Origin，硬拒会误伤合法用户——而伪造 Origin 的非浏览器攻击者本就绕不过「audience 必须与载荷匹配」，硬拒缺失头不增加有效安全边界。威胁模型：audience+origin 门禁防的是错误的第一方上下文兑换；抗非浏览器重放的主体仍是 256 位随机 + GETDEL 原子单次 + TTL 300s。
- 放弃方案：Origin 缺失一律拒绝（误伤合法浏览器，安全增益≈0）；先 GET 后校验再 DELETE（两步非原子，引入并发窗口）。
- 允许执行模型修改：否

#### D-05：origin 配置复用部署变量，compose 默认收紧

- 决策：`identity.cross-app-token.audience-origins.grassland/ai` 分别接 env `GRASSLAND_ORIGIN`/`AI_APP_ORIGIN`（与 nginx `app-config.js` 同名同值，支持逗号分隔多值）；compose identity 块透传且默认值非空（与 nginx 块一致）→ compose/e2e 栈默认启用 origin 校验；本地 vite dev（同源）不配 env → yml 默认空 → 跳过校验，audience 绑定仍生效。
- 原因：单一事实来源（同一对变量既生成前端 app-config 又驱动后端校验）；e2e 栈自动获得收紧形态的覆盖。
- 放弃方案：compose 默认空（本地栈将掩盖配置错误，且 e2e 少一条真路径覆盖）。
- 允许执行模型修改：否

#### D-06：前端先清参再请求；consume 增加必填 audience 参数

- 决策：`consumeCrossAppTokenFromUrl(audience: CrossAppAudience)` 读到 `xat` 后立即 `stripUrlParams(['xat'])`，再发 exchange `{token, audience}`；成功/失败返回值语义不变（`exchanged/failed`）。跳转签发 body 带 `{audience: 目标应用}`（`jumpToAiApp`→`ai`，`jumpToGrassland`→`grassland`）。草场壳传 `grassland`、AI 壳传 `ai`。
- 原因：URL 停留窗口从「请求往返期间」收敛到「脚本首拍前」——Referer、页面截图、错误上报在请求发出那一刻起就不再携带 token；参数只在内存。
- 放弃方案：保持请求后清参（停留窗口大）；改用 URL fragment（`#xat=`，不进服务端日志但进 history 且需改路由处理，收益不抵复杂度）。
- 允许执行模型修改：否

#### D-07：错误与文案——核销失败统一 401，仅请求侧 audience 非法新增 400

- 决策：核销一切失败（形态不过/不存在/过期/载荷解析失败/audience 错配/origin 不匹配）统一 401「登录凭证无效或已过期，请重新从应用内跳转」（既有文案）；请求侧 audience 缺失/非法（含签发与核销两端点）400「无效的目标应用」（本任务唯一新增文案）；503/403 文案不变。
- 原因：401 统一文案防探测（不给攻击者区分「token 存在但 audience 错」与「token 不存在」的信号）；400 只用于「请求本身不合格」，无探测价值。
- 放弃方案：为每种失败细分错误码（扩大探测面）。
- 允许执行模型修改：否

#### D-08：测试面——机制断言而非结果断言

- 决策：referrer 场景=单测在 fetch mock 内断言「请求发起时 `location.search` 已无 `xat`」（Referer 由请求时刻的页面 URL 构造，机制等价）；错误日志场景=spy `console.error/warn` 断言无参数含 token 明文；浏览器历史场景=e2e `goBack()+goForward()` 后 URL 无 `xat` 且会话仍在；烧毁语义=错 audience 401 后正确 audience 重试仍 401。
- 原因：泄漏面测试必须断言机制而非「测试通过」。
- 放弃方案：仅断言最终 URL 干净（无法区分清参发生在请求前还是后）。
- 允许执行模型修改：否

---

## 4. 目标行为

### 4.1 用户流程（免登跳转，用户视角零变化）

1. 用户进入草场（已登录），点头部「AI 创作」（或工作台内 `jumpToAiApp` 各入口）。
2. 前端 `POST /api/auth/cross-app-tokens` body `{"audience":"ai"}`（系统校验：会话有效、audience 合法）。
3. 系统签发 token（载荷绑 `accountId|source|ai`，TTL 300s），前端拼 `?xat=` 整页跳 AI 应用。
4. AI 壳挂载：读到 `xat` → 立即 replaceState 清参 → `POST exchange` body `{"token":…,"audience":"ai"}`（系统校验：形态→枚举→Origin∈AI 配置 origin→GETDEL→载荷 audience=ai）。
5. 成功：Set-Cookie 建会话，用户以登录态落 AI 应用创作面，URL 已无 `xat`。
6. 失败（任一校验不过/过期/已用）：清参落游客态，登录入口接住，统一提示由各入口既有逻辑处理。

### 4.2 行为变化表

| 场景 | 当前行为 | 目标行为 |
|---|---|---|
| 正常跳转（草场→AI / AI→草场） | 签发无 body；核销 body 只有 token；请求返回后清参 | 签发/核销 body 带 audience；清参先于请求；用户体验不变 |
| token 被 API 直接兑换（错 audience） | 成功建会话 | 401，token 烧毁 |
| token 在错误 origin 上下文兑换（配置了 origin 列表） | 成功建会话 | 401，token 未烧（正确 origin 可重试成功） |
| 部署窗口内存量旧 token | — | 401（载荷解析失败），重新跳转即可 |
| 未登录点跳转 | 不签发直接跳（游客态） | 不变 |
| 签发失败（后端 503/网络） | 不带 token 跳，登录页兜底 | 不变（新后端 400 缺 audience 时同路径） |
| 刷新带 `xat` 的页面 | 核销（或 401）后清参 | 不变；清参更早，重放窗口更小 |
| 请求 body 无 audience | 旧后端：正常 | 新后端：issue 400（登录后）/exchange 400（token 形态合法时） |

### 4.3 状态定义

免登消费端（前端 composable）状态机不变：`none`（无 xat）/`exchanged`/`failed`。后端无新增状态。

### 4.4 状态迁移规则

```text
目标壳挂载
  -> none：URL 无 xat（直接进常规会话引导）
  -> 请求前：strip xat（内存持有 token）
  -> exchanged：exchange 200（loadCurrentUser 走新会话）
  -> failed：exchange 401/403/5xx/网络异常（游客态，登录入口接住）

任意结果
  -> URL 已无 xat（先清参保证）；token 不落任何 storage
```

并发/乱序：exchange 每壳每次整页加载至多一次（onMounted 单次调用）；同 token 并发核销仅一个 200（GETDEL 原子，FACT 既有）。

---

## 5. 业务规则

### 5.1 输入规则

| 字段 | 类型 | 必填 | 默认值 | 允许范围 | 空值处理 | 示例 |
|---|---|---|---|---|---|---|
| `audience`（issue/exchange body） | string | 是 | 无 | 精确等于 `grassland` 或 `ai`（先 trim 再比较，大小写敏感） | null/缺失/空白/未知 → issue 400、exchange 400（形态门禁通过后） | `"ai"` |
| `token`（exchange body） | string | 是 | 无 | `[A-Za-z0-9_-]{40,60}`（既有形态门禁） | null/形态不过 → 401 | `"Smp1eT0ken0123456789abcdef0123456789abcdef012345"`（合成串） |
| Origin（HTTP 头，仅校验用） | string | 否 | 无 | 绝对 origin 串（scheme+host+port），须与该 audience 配置列表精确相等 | 缺失 → 放行（D-04） | `http://127.0.0.1:8084` |

### 5.2 校验规则

1. issue：请求须携带有效会话（否则 401「请先登录」，优先于 body 校验）；`audience` 按表 5.1。
2. exchange：按 §5.3 顺序执行，任何一步失败即返回，不继续。
3. 校验失败时 MUST NOT 触发 Redis 写（③之前的失败路径）、MUST NOT 建会话、MUST NOT 落审计（审计只在成功签发/成功核销时各一条，与现状一致）。
4. 前端不做 audience/token 格式校验（编译期类型 + 后端职责）。

### 5.3 业务判断规则（exchange，顺序红线）

```text
IF token == null OR NOT wellFormed(token)
THEN 401（统一文案）                                    # 既有用例 "{}" 命中此处

ELSE IF NOT validAudience(trim(audience))
THEN 400 {"success":false,"error":"无效的目标应用"}

ELSE IF Origin 头存在
     AND origins(audience) 非空
     AND Origin NOT IN origins(audience)
THEN 401（统一文案，不触 Redis，token 未烧毁）

ELSE payload = GETDEL(token)
  IF payload 为空 OR 解析失败 OR payload.audience != audience
  THEN 401（统一文案；GETDEL 已发生，token 已烧毁）
  ELSE loginFor(payload.accountId)（既有链路：停用 403 / 建会话 200）
```

MUST NOT 改写为其他顺序或合并分支（例如先 GETDEL 再校验枚举——会把 400 场景变成烧毁场景）。

### 5.4 权限与业务不变量

| 主体/角色 | 组织/门店/资源关系 | 允许操作 | 禁止操作及服务端拒绝结果 | 测试编号 |
|---|---|---|---|---|
| 已登录用户（源应用） | 无组织上下文要求 | 签发任意合法 audience 的 token | 未登录签发 → 401「请先登录」 | TC-C01-001 |
| 匿名调用方 | 持有有效 token | 在目标应用上下文核销一次 | 错 audience → 401 且烧毁；错 origin（配置后）→ 401 不烧 | TC-C01-008/009/010 |
| 任意调用方 | — | — | body 无/坏 audience → 400；token 形态非法 → 401 | TC-C01-004/005/009 |

- 不变量：同 token 全局至多一次成功核销（GETDEL）；成功核销不吊销来源会话（既有）；token 明文永不落日志/审计/错误响应；audience 错配必烧毁、origin 不匹配必不烧毁（§5.3）。
- 校验失败副作用：不写 Redis（③前）、不建会话、不落审计、不产生任何计费。

---

## 6. 接口契约

### 类型与调用签名

后端（identity-service，`CrossAppTokenController` / `CrossAppTokenStore` / `CrossAppAudienceOrigins`）：

```java
// CrossAppTokenController 内（既有 record 扩展 + 新增 record；Jackson record 陷阱规避：可选字段一律包装/引用类型）
public record CrossAppTokenExchangeRequest(String token, String audience) {}
public record CrossAppTokenIssueRequest(String audience) {}

// CrossAppTokenStore 内（嵌套 record，纯数据）
public record CrossAppTokenPayload(String accountId, String source, String audience) {}

// CrossAppTokenStore 新签名（旧 issue(String)/exchange(String)->Mono<String> 废弃替换）
public Mono<String> issue(String accountId, String source, String audience)
public Mono<CrossAppTokenPayload> exchange(String token)
static String encodePayload(String accountId, String source, String audience)
static java.util.Optional<CrossAppTokenPayload> parsePayload(String value)

// CrossAppAudienceOrigins（新建 @Component）
public static final List<String> AUDIENCES = List.of("grassland", "ai");
public static boolean validAudience(String audience)   // null 安全：null → false；调用方先 trim
public List<String> origins(String audience)           // 逗号分隔配置 → trim、去空后的列表；未知 audience → 空列表
public boolean allows(String audience, String originHeader) // originHeader 空 → true；origins(audience) 空 → true；否则精确包含
public String audienceOf(String originHeader)          // 命中某 audience 的 origins → 该 audience；否则 "unknown"（含 null）
```

前端（`src/composables/useCrossAppToken.ts`）：

```ts
export type CrossAppAudience = 'grassland' | 'ai'
export async function consumeCrossAppTokenFromUrl(audience: CrossAppAudience): Promise<'none' | 'exchanged' | 'failed'>
// useCrossAppJump 返回签名不变；内部签发请求体变为 {"audience": 目标应用}
```

### 6.1 请求信息（两端点，Edge 登记均不变）

**POST /api/auth/cross-app-tokens（签发）**

- 是否需要登录：是（`CurrentAccountResolver`，401 优先于 body 校验）
- 请求载体：JSON body `{"audience":"ai"}`
- Edge 登记：`docker-compose`/`application.yml` 既有路由 `POST /api/auth/cross-app-tokens` → identity（exact、flag true），零改动
- 超时/重试：前端签发失败不重试（既有降级路径）

**POST /api/auth/cross-app-tokens/exchange（核销）**

- 是否需要登录：否（token 即凭证）
- 请求载体：JSON body `{"token":"…","audience":"ai"}`
- 身份传递：无 Cookie 依赖；Origin 头按 §5.3 参与
- 幂等：天然单次（GETDEL）；前端不重试（失败即 failed）

### 6.2 请求参数

见 §5.1。完整示例（合成数据）：

```json
{"token":"Smp1eT0ken0123456789abcdef0123456789abcdef012345","audience":"ai"}
```

### 6.3 成功响应

issue（不变）：

```json
{"success":true,"data":{"token":"Smp1eT0ken0123456789abcdef0123456789abcdef012345","expiresInSeconds":300}}
```

exchange（不变，既有结构）：

```json
{"success":true,"data":{"user":{"id":"0f0e0d0c-1111-2222-3333-444455556666","email":"smoke86@example.com","username":"shop1-owner","hasEmail":true,"displayName":"烟测账号","role":"user","mustChangePassword":false}}}
```

- HTTP 状态码：200；`data` 非空；成功后前端动作：清参已在请求前完成，走常规会话引导。
- 契约参照：`CrossAppTokenController.buildExchange200`；前端 `useCrossAppToken.consumeCrossAppTokenFromUrl`。

### 6.4 错误契约

```json
{"success":false,"error":"登录凭证无效或已过期，请重新从应用内跳转"}
```

| HTTP 状态/无响应 | 实际错误字段与值 | 触发条件 | 用户文案 | 调用方动作/可重试性 |
|---|---|---|---|---|
| 401 | `error`=统一文案 | token 形态非法/不存在/过期/载荷解析失败/audience 错配/origin 不匹配 | 统一文案 | 前端 failed 清参，落登录入口；同 token 不可重试（错配已烧） |
| 400 | `error`=`"无效的目标应用"` | issue（登录后）或 exchange 的 audience 缺失/空白/未知/大小写错误 | 如左 | 编码错误，不可重试 |
| 403 | `error`=停用文案（既有三态） | 核销时账号 pending_review/suspended | 既有文案 | 前端 failed（既有） |
| 401 | `error`=`"请先登录"` | 签发无会话 | 既有文案 | 既有 |
| 503 | `error`=`"免登服务暂不可用，请稍后重试"` | Redis 故障（fail-closed） | 既有文案 | 签发侧降级直跳；核销侧 failed |
| 无响应 | 无服务端错误码 | 断网/超时 | — | 前端 failed 清参 |

### 6.5 错误处理原则

- 未知/意外错误沿用既有 `@ExceptionHandler(IdentityException)` 透传与其余默认行为；MUST NOT 新增异常类型或错误码。
- 请求进行中重复触发：前端 onMounted 单次调用；后端 GETDEL 原子，天然幂等到「至多一次成功」。

### 6.6 契约不变量与观测要求

- **不变量**：同 token 至多一次成功核销（TC-C01-007）；audience 错配烧毁（TC-C01-008）；origin 不匹配不烧（TC-C01-010）；成功核销不吊销来源会话（TC-C01-006 既有断言保留）。
- **状态码不变量**：200/400/401/403/503 集合不变；400 仅 audience 请求侧非法。
- **字段不变量**：exchange 200 响应字段集合不变（不加 audience 回显）；issue 响应不变。
- **日志/指标**：token 明文、完整 URL（含 `xat`）MUST NOT 出现在任何日志/审计/错误响应（既有红线延续；前端 console 同样，TC-C02-004）。
- **追踪字段**：N/A——本任务不新增 trace 透传（既有观测体系不动）。

### 6.7 流式、上传与异步任务契约

N/A——两个端点均为一次性 JSON POST。

---

## 7. 数据模型与迁移

### 7.1 数据结构

Redis（无数据库表，无 Flyway）：

```text
key:   {key-prefix}{token}          # key-prefix 默认 grassland:auth:cross-app-token:，不变
value: "{accountId}|{source}|{audience}"   # 新格式；accountId 为 UUID（不含 '|'）
       例："0f0e0d0c-1111-2222-3333-444455556666|grassland|ai"
TTL:   300s（不变）
旧值:  "{accountId}"（裸值）→ parsePayload 失败 → 401
```

### 7.2 字段规则

| 字段 | 类型 | 可空 | 唯一 | 可修改 | 创建时生成 |
|---|---|---:|---:|---:|---:|
| `accountId` | UUID 字符串 | 否 | 否（token 全局唯一） | 否 | 是（签发时） |
| `source` | `grassland`/`ai`/`unknown` | 否 | 否 | 否 | 是（签发时按 Origin 推导） |
| `audience` | `grassland`/`ai` | 否 | 否 | 否 | 是（签发时请求指定） |

### 7.3 兼容与迁移

- 数据库迁移：否（Redis nonce，无表）。
- 旧数据：部署窗口（≤TTL 300s）内存量裸值 nonce 一律 401，用户重跳即恢复；无回填、无双读。
- 回滚：回滚镜像批次后，旧代码把新格式 value 当 accountId 查用户失败 → 401 → 前端游客态接住；安全降级为无免登，不影响既有会话。key 与 TTL 兼容。
- 前后端发布顺序：同批部署（项目惯例，前后端镜像同仓同批构建）；混合版本行为见 §2.9 矩阵。

### 7.4/7.5 事务、并发、迁移执行

- 并发兜底：GETDEL 单命令原子（不变）；签发 SETNX 重试上限 3 次（不变）。
- 事件/Outbox：N/A。
- 数据生命周期：TTL 过期自动清理（不变）；无归档/加密需求。
- 迁移执行证据：N/A（无迁移）。

---

## 8. UI 实现规格

N/A：本任务零 UI 视觉改动——两个壳各改一行函数调用（传参）+注释，无模板/样式/交互变化。`src/ai/AiAppLayout.vue`、`src/layouts/DefaultLayout.vue` 的改动不触发 DESIGN.md 与双主题截图义务；C-03 的浏览器冒烟截图属流程证据（会话建立与 URL 状态），不属 §8.8 视觉自查。

---

## 9. 全局约束（每张卡适用）

### 9.1 文件白名单 / 黑名单

| 精确路径 | 权限 | 本次操作 | 允许修改的符号/段落 | 原因与完成标准 | 所属任务卡 |
|---|---|---|---|---|---|
| `platform-java/services/identity-service/src/main/java/com/grassland/identity/auth/CrossAppTokenStore.java` | 写入 | 修改 | `issue`/`exchange` 签名与实现、新增嵌套 `CrossAppTokenPayload`、`encodePayload`/`parsePayload`；保留 `wellFormed`/`generate`/`ttlSeconds` | 载荷化；IT+单元过 | C-01 |
| `platform-java/services/identity-service/src/main/java/com/grassland/identity/auth/CrossAppAudienceOrigins.java` | 写入 | 新建 | 全文件（§6 签名） | audience 配置/匹配器；单元过 | C-01 |
| `platform-java/services/identity-service/src/main/java/com/grassland/identity/auth/CrossAppTokenController.java` | 写入 | 修改 | `issue`/`exchange` 方法体、新增 `CrossAppTokenIssueRequest` 与 `build400InvalidAudience`、扩展 `CrossAppTokenExchangeRequest`；`loginFor` 及以下全部保留 | 双端点契约；IT 过 | C-01 |
| `platform-java/services/identity-service/src/main/resources/application.yml` | 写入 | 修改 | `identity.cross-app-token` 块（新增 `audience-origins.grassland/ai` 两键+注释） | 配置接线；`docker compose config` 语义不受影响 | C-01 |
| `platform-java/services/identity-service/src/test/java/com/grassland/identity/auth/CrossAppTokenStoreTest.java` | 写入 | 新建 | 全文件 | 载荷编解码单元 | C-01 |
| `platform-java/services/identity-service/src/test/java/com/grassland/identity/auth/CrossAppAudienceOriginsTest.java` | 写入 | 新建 | 全文件 | 匹配器单元 | C-01 |
| `platform-java/services/identity-service/src/test/java/com/grassland/identity/auth/CrossAppTokenControllerIT.java` | 写入 | 修改 | 全文件（按 TC-C01 清单改写/新增用例、`seedNonce` 写新载荷、`@DynamicPropertySource` 加两行 origins） | IT 全绿 | C-01 |
| `src/composables/useCrossAppToken.ts` | 写入 | 修改 | `CrossAppAudience` 类型、`consumeCrossAppTokenFromUrl`、`jump`/`jumpToAiApp`/`jumpToGrassland`；`stripUrlParams` 保留 | 前端契约；单测过 | C-02 |
| `src/composables/useCrossAppToken.test.ts` | 写入 | 修改 | 全文件（按 TC-C02 清单） | 单测全绿 | C-02 |
| `src/layouts/DefaultLayout.vue` | 写入 | 修改 | `onMounted` 内 `consumeCrossAppTokenFromUrl()` 调用行（275 行附近）+相邻注释 | 传 `grassland`；typecheck 过 | C-02 |
| `src/ai/AiAppLayout.vue` | 写入 | 修改 | `onMounted` 内 `consumeCrossAppTokenFromUrl()` 调用行（187 行附近）+相邻注释 | 传 `ai`；typecheck 过 | C-02 |
| `docker-compose.yml` | 写入 | 修改 | `identity-service.environment` 块尾（REDIS_URL 行后）新增 2 个 env+注释 | 透传 origin 变量；V-008 过 | C-03 |
| `.env.docker.example` | 写入 | 修改 | 12-15 行注释区（AI_APP_ORIGIN/GRASSLAND_ORIGIN 用途说明加「identity 校验」一句）；变量本身不动 | 文档同步 | C-03 |
| `tests/e2e/ai-creation-center.spec.ts` | 写入 | 修改 | `跨应用免登与门店深链` describe（既有用例补 audience/history 断言 + 新增 API 级烧毁用例） | lint+评审过 | C-03 |
| `src/ai/AiAppLayout.test.ts` | 只读参考 | 读取 | — | 核实签名变更不连坐（已核实：断言仅方法+URL）；若失败按 §13 报告 | C-02 |
| `src/lib/app-config.ts`、`src/composables/grassland-http.ts`、`src/composables/useAuth.ts` | 只读参考 | 读取 | — | 复用依据 | C-02 |
| `platform-java/services/edge-bff/**` 全部 | 禁止修改 | 无 | 全部 | 路由零改动 | 全部 |
| `nginx.conf`、`vite.config.ts`、`src/style.css`、`DESIGN.md`、`src/ops/DESIGN.md` | 禁止修改 | 无 | 全部 | 无 UI/入口变更 | 全部 |
| `platform-java/services/identity-service/src/main/java/com/grassland/identity/session/**`、`security/**`、`identityprofile/**` | 禁止修改 | 无 | 全部 | 会话/审计机制不动 | 全部 |
| 任何 Flyway migration 目录 | 禁止修改 | 无 | 全部 | 无迁移 | 全部 |

- 生成物：`test-artifacts/taskbook-86/`（C-03 冒烟截图与记录；保留，不提交源码化文件）。Gradle/Vitest 报告走各自 build 目录，不登记提交。
- 任务前已有改动：仅未跟踪的 #85 任务书，无重叠，保留不动。

### 9.2 项目铁律速查

按模板 §9.2 全文适用，本任务命中项见 §9.5 矩阵。特别强调：R-JAVA「Reactor `switchIfEmpty` 副作用包 `Mono.defer`」——`CrossAppTokenController.loginFor` 第 101-102 行既有注释与写法 MUST 原样保留；本任务在 `issue` 新增的 body `switchIfEmpty` 不含副作用（只补默认 record），无需 defer，但 MUST NOT 在装配期产生副作用。

### 9.3 验证环境事实

| 项目 | 本任务的精确值/检查方式 |
|---|---|
| 仓库根/命令 shell | `/Users/LXH/claude/y-1`；bash（Gradle 命令须在 bash 会话 source java-runtime） |
| Node/npm | 仓库已按 lock 安装（基线 npm test 实跑通过）；`node --version` 以本机为准 |
| Java/Gradle | `cd platform-java && source ../scripts/lib/java-runtime.sh && ensure_java_runtime 25`（2026-09-06 实跑可用）；`./gradlew` wrapper |
| Docker | `docker info` OK（2026-09-06 实测）；IT 自备 PG16+Redis7 Testcontainers；C-03 用本地 compose 栈重建 |
| 入口地址 | 本地 compose：草场 `http://127.0.0.1:8080`、AI `http://127.0.0.1:8084`（**冒烟必须用 127.0.0.1，勿用 localhost**，理由 §1.6）；e2e ci 栈 18080/18082 |
| 测试数据 | C-03 冒烟账号用 `npm run e2e:seed:auth`（需 4 个 E2E_* env，写本地 compose 库 `localhost:55432`）；合成 token 形态 `[A-Za-z0-9_-]{40,60}` |
| 网络/权限 | 全部本地；无远程/生产/真实凭据操作 |
| 产物目录 | `test-artifacts/taskbook-86/`（截图+冒烟记录，保留） |

### 9.4 安全、性能与兼容规格

| 类别 | 必须明确的项目 | 本任务唯一要求/阈值 | 验证用例 |
|---|---|---|---|
| 安全 | 兑换门禁 | §5.3 顺序红线；401 统一文案不拆分 | TC-C01-004~010 |
| 隐私 | 日志/错误脱敏 | token 明文、含 xat 的完整 URL 不落日志/console/审计/错误响应 | TC-C01-001（审计无明文，既有断言保留）、TC-C02-004 |
| 外部输入 | token/audience/Origin 边界 | 表 §5.1；Origin 精确字符串匹配（无通配、无后缀匹配） | TC-C01-005、CrossAppAudienceOriginsTest |
| 性能 | 校验开销 | ③之前失败不触 Redis；无新增往返（exchange 仍一次 GETDEL） | TC-C01-010（不烧毁即证明未过 GETDEL 前置拦截路径语义） |
| 异步资源 | TTL/单次 | TTL 300s 不变；单 token 单次核销 | TC-C01-007 |
| 兼容 | 混合版本/三入口 | §2.9 矩阵；ops 不接入；compose 默认收紧 | V-008、TC-C03-* |

### 9.5 仓库约束适用矩阵

| 约束 | 适用卡号/文件 | 落实动作与验收；或 N/A 原因 |
|---|---|---|
| R-UI | N/A | 零视觉/样式/模板改动（§8）；两壳仅改调用参数 |
| R-ENTRY | C-02、C-03 | 三入口边界不动；跨应用链改的是 `useCrossAppToken.ts` 既有文件；compose/env 变更按 §9.1 登记，`edge-entrypoint.contract.test.ts` 回归（V-007） |
| R-JAVA | C-01 | identity-service 内闭合；WebFlux 无阻塞 I/O 新增；Reactor defer 铁律保留；不跨服务 |
| R-DATA | C-01 | 无 Flyway（Redis nonce）；GETDEL 原子语义保留；无资金 |
| R-AI | N/A | 不触 AI 执行链/预算/内容安全 |
| R-QUALITY | C-01/C-02/C-03 | Vitest+Gradle 既有门禁；WebTestClient 30s responseTimeout 惯例（IT 已带）；不降覆盖率阈值；文档同步 N/A（§2.9） |
| R-SAFE | 全部 | 不动他人改动（仅 #85 未跟踪文件，无重叠）；证据脱敏（截图不含真实 token；合成 token 例外）；不删测试不放宽断言 |

---

## 10. 任务总表

| 卡 | 标题 | 端 | 对应需求 | 主要写入文件 | 依赖及交付物 | 验收编号 | 执行状态 |
|---|---|---|---|---|---|---|---|
| C-01 | 后端：token 载荷绑定 audience/source + 双端点校验 + origin 门禁 | 后端 | REQ-001/002/003 | CrossAppTokenStore/Controller/AudienceOrigins + yml + 3 测试 | 无 | AC-001~005 | NOT_STARTED |
| C-02 | 前端：consume 带受众 + 先清参再请求 + 跳转签发带受众 | 用户端+AI 壳 | REQ-004（+消费 REQ-001/002） | useCrossAppToken.ts/.test.ts + 两壳 | C-01 的契约（§6） | AC-006/007 | NOT_STARTED |
| C-03 | 部署接线 + e2e 场景 + 本地栈冒烟 | 部署/e2e | REQ-005 | docker-compose/.env.docker.example/e2e spec | C-01+C-02 代码（镜像可重建） | AC-008~010 | NOT_STARTED |

### 10.1 任务卡拆分规则 / 10.2 卡间交接协议

按模板原文执行。交接要点：C-02 消费 C-01 的 §6 契约（body 字段名 `audience`、枚举值、400/401 语义）——**C-02 编码可只依任务书契约进行，不要求 C-01 代码在本机可运行**；C-03 依赖 C-01+C-02 实际代码构建镜像冒烟。

---

## 11. 任务卡

### 卡 C-01：后端——token 载荷绑定 + 双端点校验 + origin 门禁

**执行包**：任务书版本 v1.0；对应需求 REQ-001/002/003；执行者 Qoder；负责人/验收人 ZCode+用户。

**背景**：token 现在不绑目标应用（`CrossAppTokenController.java:28` 注释自证）。本卡给它加 audience/source 载荷与核销门禁，契约见 §6，校验顺序见 §5.3（红线）。

**输入与前置交付物**：无前置卡。基线 d39ae265；`CrossAppTokenControllerIT` 当前全绿（§2.7 实跑证据）。

**输出与移交**：§6 全部后端签名与行为；C-02 按 §6 契约编码；C-03 按新 env 键接线。

**必读清单**：§0、§5、§6、§9.1/9.2（R-JAVA/R-DATA）、§13；`CrossAppTokenController.java` 与 `CrossAppTokenStore.java` 全文；IT 全文（容器/schema/辅助方法模式）。

**改动文件**：§9.1 白名单 C-01 的 7 行。

**开始前检查**：按模板 §11 通用清单；跑基线 `./gradlew :services:identity-service:test --tests 'com.grassland.identity.auth.CrossAppTokenControllerIT'`（预期绿，证据 §2.7；重复跑允许 UP-TO-DATE）。

**锚点代码**（当前片段，非目标代码；行号辅助）：

```java
// CrossAppTokenStore.java:50-74（当前）——issue 绑定裸 accountId；exchange 返回 accountId
public Mono<String> issue(String accountId) { return issue(accountId, MAX_ISSUE_ATTEMPTS); }
private Mono<String> issue(String accountId, int attemptsLeft) {
    if (attemptsLeft <= 0) { return Mono.error(new CrossAppTokenStoreException()); }
    return Mono.defer(() -> {
        String token = generate();
        return redis.opsForValue().setIfAbsent(keyPrefix + token, accountId, ttl).defaultIfEmpty(Boolean.FALSE)
                .flatMap(stored -> Boolean.TRUE.equals(stored) ? Mono.just(token) : issue(accountId, attemptsLeft - 1))
                .onErrorResume(e -> Mono.error(new CrossAppTokenStoreException()));
    });
}
public Mono<String> exchange(String token) {
    return redis.opsForValue().getAndDelete(keyPrefix + token)
            .onErrorResume(e -> Mono.error(new CrossAppTokenStoreException()));
}
```

```java
// CrossAppTokenController.java:56-83（当前）——issue 无 body；exchange 只收 token
@PostMapping("/api/auth/cross-app-tokens")
public Mono<ResponseEntity<Map<String, Object>>> issue(ServerHttpRequest request) {
    return accounts.resolve(request).flatMap(user -> {
        DeviceFingerprint fingerprint = DeviceFingerprint.from(request);
        return store.issue(user.id())
                .flatMap(token -> audit.append(...).thenReturn(ResponseEntity.ok()
                        .body(Map.of("success", true, "data", Map.of("token", token, "expiresInSeconds", store.ttlSeconds()))))
                .onErrorResume(CrossAppTokenStoreException.class, e -> Mono.just(build503()));
    });
}
@PostMapping(value = "/api/auth/cross-app-tokens/exchange", consumes = MediaType.APPLICATION_JSON_VALUE)
public Mono<ResponseEntity<Map<String, Object>>> exchange(@RequestBody Mono<CrossAppTokenExchangeRequest> bodyMono,
        ServerHttpRequest request) {
    return bodyMono.flatMap(body -> {
        String token = body.token();
        if (!CrossAppTokenStore.wellFormed(token)) { return Mono.just(buildExchange401()); }
        return store.exchange(token).flatMap(accountId -> loginFor(accountId, request))
                .defaultIfEmpty(buildExchange401());
    });
}
// 第 101-102 行 defer 注释与写法 MUST 原样保留
```

**本卡目标行为**：§4.2 表中「正常跳转/错 audience/错 origin/旧载荷」四行后端侧；§5.3 顺序；§6.4 错误契约。

**函数级要求**：

`CrossAppTokenStore` -
- `issue(String accountId, String source, String audience)`：`setIfAbsent(key, encodePayload(...), ttl)`；重试与错误语义照旧。
- `exchange(String token)` → `Mono<CrossAppTokenPayload>`：`getAndDelete` 后 `parsePayload`，`Optional` 空则 `Mono.empty()`；错误映射照旧。
- `encodePayload`：`accountId + "|" + source + "|" + audience`（调用方保证三参非空；不做防御）。
- `parsePayload(String value)`：null/`split("\\|")` 非 3 段/任一段 blank → `Optional.empty()`；否则 record。
- 副作用：仅 Redis；不变条件：key 前缀/TTL/随机源/形态门禁不动；清理：异常照旧包 `CrossAppTokenStoreException`。

`CrossAppAudienceOrigins`（新建 `@Component`）——签名见 §6；构造器两个 `@Value("${identity.cross-app-token.audience-origins.grassland:}")`/`.ai:`；`origins()` 用 `Arrays.stream(split(",")).map(String::trim).filter(s->!s.isEmpty()).toList()`；`allows`/`audienceOf` 按 §6 javadoc 语义；纯逻辑无副作用。

`CrossAppTokenController` -
- `issue`：加 `@RequestBody(required = false) Mono<CrossAppTokenIssueRequest> bodyMono`（在 `accounts.resolve(request).flatMap(user -> ...)` 链内消费，保证 401 优先）：`bodyMono.switchIfEmpty(Mono.just(new CrossAppTokenIssueRequest(null)))` → trim+`validAudience` 不过 → `build400InvalidAudience()`；过 → `source = audienceOrigins.audienceOf(request.getHeaders().getFirst(HttpHeaders.ORIGIN))` → `store.issue(user.id(), source, audience)` → 审计/响应/503 逻辑照旧。
- `exchange`：`CrossAppTokenExchangeRequest(String token, String audience)`；按 §5.3 四步实现（③用 `audienceOrigins.allows(...)`，④`payload.audience().equals(audience)`）。
- 新增 `build400InvalidAudience()`：`ResponseEntity.status(400).body(Map.of("success", false, "error", "无效的目标应用"))`。
- 构造器注入 `CrossAppAudienceOrigins`。
- MUST 满足：1. §5.3 顺序逐字实现；2. 401 全部走既有 `buildExchange401()`（统一文案）；3. `loginFor`/`buildExchange200`/`buildAccountBlocked`/`build503`/两个 `@ExceptionHandler` 零改动。
- MUST NOT：1. 新增错误码/异常类型；2. 改 `wellFormed` 正则或 TOKEN_BYTES；3. 在 ③ 之前触 Redis；4. 把 source 用于任何校验；5. 记录 token/Origin 之外的请求元数据到日志。

**做法**：

| 步骤 | 文件/符号 | 精确动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | `CrossAppAudienceOrigins.java` | 新建组件（§6 签名） | `CrossAppAudienceOriginsTest` 绿 | 编码错误自修 |
| 2 | `CrossAppTokenStore.java` | 载荷化（函数级要求） | `CrossAppTokenStoreTest` 绿 | 自修 |
| 3 | `CrossAppTokenController.java` | 双端点改造（函数级要求） | 编译过 | 自修 |
| 4 | `application.yml` | `cross-app-token` 块加 `audience-origins` 两键+注释（env 名 `GRASSLAND_ORIGIN`/`AI_APP_ORIGIN`，默认空） | yml 语法过（IT 启动即验证） | 自修 |
| 5 | `CrossAppTokenControllerIT.java` | 按 TC 清单改写+新增；`@DynamicPropertySource` 加 `identity.cross-app-token.audience-origins.grassland=http://gl.test` / `.ai=http://ai.test`（既有用例不带 Origin → 放行，全部仍绿） | V-001 全绿 | 用例本身错自修；语义疑义 BLOCKED |

**边界与异常场景**：

| 编号 | 场景 | 触发条件 | 预期行为 | 必测 |
|---|---|---|---|---|
| E01 | 空输入 | exchange body `{}`；issue 无 body+已登录 | 前者 401（token 先检）；后者 400 | 是 |
| E02 | 超长/形态非法 token | `../../etc/passwd`、>60 字符 | 401，不触 Redis | 是 |
| E03 | 重复核销 | 同 token 二次 exchange | 第二次 401 | 是 |
| E04 | Redis 不可用 | 容器故障 | 503（既有映射，路径未改） | 不适用，原因：本卡未改 Redis 故障路径，映射为既有行为 |
| E05 | 服务端错误 | 意外异常 | 既有 handler 行为 | 不适用，原因：同 E04 |
| E06 | 签发未登录 | 无会话 POST issue（无 body） | 401「请先登录」（优先于 body 校验） | 是 |
| E07 | 无权限 | — | 不适用，原因：无角色差异 | N/A |
| E08 | nonce 不存在 | GETDEL 空 | 401 | 是 |
| E09 | 数据过期 | TTL 到期 | 401 | 是 |
| E10 | 页面刷新 | 前端域 | 不适用，原因：后端无刷新语义（C-02/E10） | N/A |
| E11 | 快速切换 | — | 不适用，原因：无状态切换 | N/A |
| E12 | 卸载请求未完成 | — | 不适用，原因：后端无卸载语义 | N/A |
| E13 | audience null/空白/`"ops"`/`"AI"` | trim 后不在枚举 | issue 400 / exchange 400（形态过后） | 是 |
| E14 | 数值边界 | TTL 300s、token 40-60 字符界 | `expiresInSeconds:300` 不变；边界形态照旧门禁 | 是 |
| E15 | 并发核销 | 两请求同 token | 仅一个 200（GETDEL 原子；语义由 E03 覆盖） | 不适用，原因：原子性实现未改，单次重放用例已锁语义 |
| E16 | 超时但已提交 | — | 不适用，原因：无幂等重试语义变化 | N/A |
| E17 | 跨账号 | 载荷绑 accountId | 只建载荷账号会话（既有断言保留） | 是 |
| E18 | 签发后账号停用 | seedNonce 绑停用账号 | 403（既有三态文案） | 是 |
| E19 | 旧格式裸载荷 | Redis 直写裸 accountId | 401（解析失败） | 是 |
| E20 | 部分成功/补偿 | — | 不适用，原因：单命令操作无中间态 | N/A |
| E21 | 时区/金额 | — | 不适用，原因：无时间计算变更（TTL 原样）与金额 | N/A |
| E22 | 超长列表/文案 | — | 不适用，原因：无列表/文件场景 | N/A |

（origin 匹配的边界——精确串比较、多值列表、缺失放行——由 `CrossAppAudienceOriginsTest` 与 IT 的 origin 用例覆盖，归入 E13/E17 行为族。）

**本卡禁止**：不改 `edge-bff`；不动 `SessionWriter`/审计仓库/迁移；不做旧载荷兼容；不改 401 文案；不加 `audience` 回显到任何响应。

**验收**：

- 测试清单：TC-C01-001~014。
- 命令验收：V-001（见 §12.3），工作目录 `platform-java/`，预期退出码 0。
- 行为验收：
  - AC-001：Given 已登录会话，When `POST /api/auth/cross-app-tokens` body `{"audience":"ai"}`，Then 200 且 `expiresInSeconds:300`，Redis 值为 `{accountId}|{source}|ai`（IT 内经行为断言：该 token 可被 audience=ai 核销成功）。
  - AC-002：Given 已登录，When issue body 缺失/`"ops"`/`"AI"`/空白，Then 400 `无效的目标应用`，Redis 无新 key（签发未发生）。
  - AC-003：Given audience=ai 的 token，When exchange `{"audience":"grassland"}`（Origin 合法），Then 401 统一文案；When 随后 exchange `{"audience":"ai"}`，Then 401（已烧毁）。
  - AC-004：Given Redis 直写裸 accountId 旧格式，When exchange 任意合法 audience，Then 401。
  - AC-005：Given IT 配置 `audience-origins.ai=http://ai.test`，When exchange `{"audience":"ai","token":…}` 带 `Origin: http://evil.test`，Then 401；When 同 token 带 `Origin: http://ai.test` 重试，Then 200（未烧毁）。
- UI 验收：N/A（无 UI）。
- 保留行为回归：`issueRequiresLoginSession`、`exchangeWithoutTokenReturns401`、`exchangeWithMalformedTokenReturns401`、`exchangeSuspendedAccountReturns403`、`exchangeCreatesSessionCookieAndOriginalSessionSurvives`（含审计两行+无明文断言）、`tokenIsSingleUseReplayReturns401`、`expiredTokenReturns401` 全部保持通过（按 TC-C01 编号补 audience 后语义不变）。

**完成后**：按 §14 报告。

---

### 卡 C-02：前端——consume 带受众、先清参再请求、签发带受众

**执行包**：任务书版本 v1.0；对应需求 REQ-004；执行者 Qoder；负责人/验收人 ZCode+用户。

**背景**：核销前端现在「请求返回后才清参」且不带受众。本卡把清参提前到请求前（URL 停留窗口收敛），并给签发/核销请求体带 `audience`。契约见 §6；不依赖 C-01 代码在本机可运行。

**输入与前置交付物**：C-01 的 §6 契约（body 字段名/枚举值/400 语义）。无数据依赖。

**输出与移交**：`useCrossAppToken.ts` 新签名与行为 + 两壳传参；C-03 的 e2e/冒烟直接消费。

**必读清单**：§0、§5.1、§6、§9.1/9.2（R-ENTRY/R-QUALITY）；`src/composables/useCrossAppToken.ts` 与其测试全文；`src/lib/app-config.ts`（只读）。

**改动文件**：§9.1 白名单 C-02 的 4 行（另 `src/ai/AiAppLayout.test.ts` 只读）。

**开始前检查**：模板通用清单；跑基线 `npm run test -- src/composables/useCrossAppToken.test.ts`（预期 8/8 绿，§2.7 证据）与 `npm run test -- src/ai/AiAppLayout.test.ts`（记录结果）。

**锚点代码**（当前片段，非目标代码）：

```ts
// src/composables/useCrossAppToken.ts:32-46（当前）——请求后才清参；body 只有 token
export async function consumeCrossAppTokenFromUrl(): Promise<ConsumeOutcome> {
  const token = new URLSearchParams(window.location.search).get('xat')
  if (!token) return 'none'
  try {
    await request<{ token: string; expiresInSeconds: number }>(
      '/api/auth/cross-app-tokens/exchange',
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) },
    )
    stripUrlParams(['xat'])
    return 'exchanged'
  } catch {
    stripUrlParams(['xat'])
    return 'failed'
  }
}
```

```ts
// src/composables/useCrossAppToken.ts:58-70（当前）——签发无 body
async function jump(build: (params: Record<string, string>) => string, params: Record<string, string> = {}): Promise<void> {
  let withToken = params
  if (isAuthenticated.value) {
    try {
      const issued = await request<{ token: string; expiresInSeconds: number }>('/api/auth/cross-app-tokens', { method: 'POST' })
      withToken = { ...params, xat: issued.token }
    } catch { /* 不带 token 跳，目标方登录页兜底，不阻断跳转 */ }
  }
  window.location.href = build(withToken)
}
```

```ts
// src/layouts/DefaultLayout.vue:273-275 与 src/ai/AiAppLayout.vue:186-187（当前）
await consumeCrossAppTokenFromUrl()
```

**本卡目标行为**：§4.1 步骤 2/4；§4.2「正常跳转/签发失败/刷新」前端行。

**函数级要求**：

`src/composables/useCrossAppToken.ts` -
- 新增 `export type CrossAppAudience = 'grassland' | 'ai'`。
- `consumeCrossAppTokenFromUrl(audience: CrossAppAudience)`：读到 token 后**先** `stripUrlParams(['xat'])` **再** `request('/api/auth/cross-app-tokens/exchange', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ token, audience }) })`；返回值语义不变（try→`exchanged`，catch→`failed`）；文件头注释同步更新（任务书 #86）。
- `jump` 增加目标参数（建议 `jump(target: CrossAppAudience, build, params)` 或等价重构，`jumpToAiApp`→`'ai'`、`jumpToGrassland`→`'grassland'`）：签发请求带 `headers`+`body: JSON.stringify({ audience: target })`。
- 副作用：replaceState+fetch+location.href（既有）；不变条件：降级路径（未登录直跳/签发失败直跳）与返回签名不变；清理：无（纯跳转语义）。
- MUST 满足：1. 清参先于任何网络请求；2. 请求体字段名精确 `audience`；3. `stripUrlParams` 零改动。
- MUST NOT：1. 把 token 写入任何 storage/全局变量；2. 在 catch 中输出 token 到 console；3. 改参数名 `xat`；4. 自行增加重试。

`src/layouts/DefaultLayout.vue` / `src/ai/AiAppLayout.vue`：仅把调用改为 `consumeCrossAppTokenFromUrl('grassland')` / `('ai')` 并更新相邻注释（引用任务书 #86）；其余零改动。

**做法**：

| 步骤 | 文件/符号 | 精确动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | `useCrossAppToken.ts` | 类型+consume+jump 改造（函数级要求） | typecheck 过 | 自修 |
| 2 | 两壳 | 调用传参+注释 | typecheck 过 | 自修 |
| 3 | `useCrossAppToken.test.ts` | 按 TC-C02 清单改写+新增 | V-002/V-004/V-005 绿 | 自修 |

**边界与异常场景**：

| 编号 | 场景 | 触发条件 | 预期行为 | 必测 |
|---|---|---|---|---|
| E01 | 空输入 | URL 无 `xat` | 返回 `none`，零请求 | 是 |
| E02 | 超长 token | URL 带超长 `xat` | 前端不校验，照发→后端 401→`failed` 清参 | 不适用，原因：长度门禁是后端职责（§5.2-4），前端路径与 E04 同构 |
| E03 | 重复消费 | 刷新/二次挂载 | 首次已清参→`none`；若 token 已烧→401→`failed` | 是 |
| E04 | 网络失败 | fetch reject | `failed`，参数已清 | 是 |
| E05 | 服务端 5xx | 503 | `failed`（request 抛 GrasslandHttpError→catch） | 是 |
| E06 | 未登录消费 | 游客带 `xat` | token 本身是凭证：合法则建会话（既有语义） | 是 |
| E07 | 无权限 | — | 不适用，原因：无角色分支 | N/A |
| E08 | 数据为空 | — | 不适用，原因：无列表渲染 | N/A |
| E09 | token 过期 | TTL 后消费 | 401→`failed` | 是 |
| E10 | 页面刷新 | 核销前/后刷新 | 核销前刷新→重消费（同 token 一次）；核销后→`none`；URL 均无 `xat` 残留（先清参） | 是 |
| E11 | 快速切换应用 | 双壳各自消费 | 各 origin 独立 cookie jar，互不影响（既有） | 不适用，原因：既有架构语义，本卡未触碰 |
| E12 | 卸载时请求未完成 | 整页跳转中断 | 无清理需求：一次性 POST，失败即 `failed` | 不适用，原因：无订阅/计时器 |
| E13 | audience 传错值 | 编码错误（如两壳都传 `ai`） | TS 编译期拦截；运行时后端 401 兜底 | 不适用，原因：类型系统+后端双重保障，无可观察前端行为 |
| E14 | 数值边界 | — | 不适用，原因：无数值输入 | N/A |
| E15 | 并发/乱序 | — | 不适用，原因：每次加载单次调用无竞态 | N/A |
| E16 | 超时已提交 | — | 不适用，原因：无重试语义 | N/A |
| E17 | 跨账号 | exchange 建会话 | 会话=token 载荷账号（服务端域，C-01 覆盖） | 不适用，原因：归 C-01/E17 |
| E18 | 权限撤销 | — | 不适用，原因：无资源持有 | N/A |
| E19 | 旧客户端混布 | 旧前端+新后端 | 签发 400→catch→无 token 跳（既有降级） | 是 |
| E20 | 部分成功/补偿 | — | 不适用，原因：无多步事务 | N/A |
| E21 | 时区/金额 | — | 不适用，原因：无时间/金额展示 | N/A |
| E22 | 超长列表/文案 | — | 不适用，原因：无列表/文件 | N/A |

**本卡禁止**：不改 `stripUrlParams`；不改 `xat` 参数名；不改两壳其余任何逻辑；不动 `app-config.ts`/`grassland-http.ts`；不加 loading UI。

**验收**：

- 测试清单：TC-C02-001~008。
- 命令验收：V-002、V-003、V-004、V-005。
- 行为验收：
  - AC-006：Given URL 含 `?xat=<合成token>`，When `consumeCrossAppTokenFromUrl('ai')`，Then fetch mock 执行时 `window.location.search` 不含 `xat`（先清参），请求体为 `{"token":…,"audience":"ai"}`，成功返回 `exchanged`。
  - AC-007：Given 已登录草场壳，When `jumpToAiApp('/')`，Then 签发请求体为 `{"audience":"ai"}`，跳转 URL 带 `xat`（既有断言保留）；`jumpToGrassland` 对称传 `grassland`。
- UI 验收：N/A（无 UI 改动）。
- 保留行为回归：既有 8 个用例中「无 xat no-op」「成功/失败都清参」「未登录不发签发」「签发失败降级」「app-config 拼链」「stripUrlParams」语义全部保留（按新签名改写后仍过）。

**完成后**：按 §14 报告。

---

### 卡 C-03：部署接线 + e2e 场景 + 本地栈冒烟

**执行包**：任务书版本 v1.0；对应需求 REQ-005；执行者 Qoder；负责人/验收人 ZCode+用户。

**背景**：后端默认空配置时 origin 门禁是「跳过」路径；要让 compose/e2e 栈进入收紧形态并获真链路覆盖，需要把部署变量透传进 identity-service；同时把 e2e 更新到新契约并补齐泄漏面场景。

**输入与前置交付物**：C-01+C-02 全部代码已实现并通过卡级验收（镜像可构建）。

**输出与移交**：compose/e2e/env 模板更新；冒烟证据入 `test-artifacts/taskbook-86/`；供负责人做 §12.5 集成。

**必读清单**：§0、§9.1/9.3/9.4、§12；`docker-compose.yml` identity 块与 nginx 块（env 同源性）；`tests/e2e/ai-creation-center.spec.ts` 的 `跨应用免登与门店深链` describe；`scripts/ci-e2e.sh`（24-25 行已 export 两个 origin 变量——透传后 e2e 栈自动收紧）。

**改动文件**：§9.1 白名单 C-03 的 3 行。

**开始前检查**：模板通用清单；确认 C-01/C-02 报告为 IMPLEMENTED 且其 V 命令绿；`docker info` 可用。

**锚点代码**：

```yaml
# docker-compose.yml:86-88（当前，identity-service.environment 块尾）——插入点
      # 任务书 #76 卡 A：跨应用免登 nonce（identity.cross-app-token）走 spring.data.redis。
      REDIS_URL: ${IDENTITY_REDIS_URL:-redis://redis:6379}
```

```ts
// tests/e2e/ai-creation-center.spec.ts:217（当前）——无 body 签发，新契约会 400
const issued = await page.request.post('/api/auth/cross-app-tokens')
```

**本卡目标行为**：§1.3 REQ-005 全部；compose 栈默认启用 origin 校验。

**做法**：

| 步骤 | 文件/符号 | 精确动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | `docker-compose.yml` | identity 块 REDIS_URL 行后新增（含一行注释「任务书 #86」）：`GRASSLAND_ORIGIN: ${GRASSLAND_ORIGIN:-http://127.0.0.1:8080}` 与 `AI_APP_ORIGIN: ${AI_APP_ORIGIN:-http://127.0.0.1:8084}` | V-008 输出两行 | yaml 错自修 |
| 2 | `.env.docker.example` | 12-15 行注释补一句：两变量同时驱动 nginx `/app-config.js` 与 identity-service 免登 token 受众 origin 校验（逗号可分隔多值） | 文字过目 | 自修 |
| 3 | e2e spec | ①217 行 issue 调用改 `page.request.post('/api/auth/cross-app-tokens', { data: { audience: 'ai' } })`；②「主导航外链」用例尾部加 `goBack()+goForward()` 历史断言（AC-009）；③新增 API 级烧毁用例（AC-010）：登录→签发 audience=ai→exchange `audience:'grassland'` 断言 401→exchange `audience:'ai'` 断言 401 | V-006 过；评审通过 | 自修 |
| 4 | 冒烟 | 按 §9.3 配方重建镜像（bootJar 先行）→ `up -d identity-service frontend` → `npm run e2e:seed:auth` 造号 → 浏览器双端免登往返+back/forward+错 token 弹窗 | V-009 步骤全过+截图 | 环境故障排查记录；不伪造通过 |

**边界与异常场景**：

| 编号 | 场景 | 触发条件 | 预期行为 | 必测 |
|---|---|---|---|---|
| E01 | env 缺省 | compose 不带变量 | 默认值注入（127.0.0.1:8080/8084），校验启用 | 是 |
| E02 | 用 localhost 访问 | 浏览器 `localhost:8084` | Origin 不匹配 → 401 → 登录弹窗接住（预期行为，§1.6） | 是（冒烟步骤显式验证 127.0.0.1 通过即可，localhost 行为记录不修） |
| E03 | 重复签发 | 连续两次跳转 | 各自独立 token，互不干扰 | 是（e2e 双向用例） |
| E04 | 网络失败 | exchange 断网 | 登录弹窗接住（既有） | 不适用，原因：前端域已由 C-02/E04 覆盖 |
| E05 | 服务端 503 | Redis 停 | 登录弹窗接住 | 不适用，原因：C-01 未改该路径 |
| E06 | 游客错 token | `xat=forged-…` | 401→弹窗（既有用例保留） | 是 |
| E07~E09 | 权限/空数据/过期 | — | 过期=TC-C01-010；其余不适用（e2e 无角色/空态） | N/A |
| E10 | 浏览器历史回溯 | 免登落 AI 后 back+forward | forward 目标 URL 无 `xat`，会话仍有效 | 是 |
| E11~E18 | — | — | 不适用，原因：部署/e2e 卡无对应交互面（已由 C-01/C-02 场景表覆盖） | N/A |
| E19 | 旧栈滚动窗口 | 部署瞬间存量 nonce | 401→重跳恢复（§7.3，文档行为） | 不适用，原因：一次性窗口不可稳定复现，接受文档化 |
| E20~E22 | — | — | 不适用，原因：同 E11 | N/A |

**本卡禁止**：不动 nginx.conf/ci-e2e.sh（其已 export 所需变量）；不改 e2e 无关用例；不因冒烟失败放宽 compose 默认 origin；不把真实 token 写入截图/报告。

**验收**：

- 测试清单：TC-C03-001~004（e2e 用例，实际执行归 §12.5 集成；本卡级以 lint+契约测试+冒烟代偿，见下）。
- 命令验收：V-006、V-007、V-008、V-009。
- 行为验收：
  - AC-008：Given `docker compose config`，Then identity-service 块含 `AI_APP_ORIGIN`/`GRASSLAND_ORIGIN` 两行（V-008 输出）。
  - AC-009：Given e2e「主导航外链」用例，When 免登落 AI 后 `goBack()+goForward()`，Then 页面回到 AI 应用、URL 无 `xat=`、`auth-pill` 可见（写进 spec 断言）。
  - AC-010：Given e2e 栈内签发 audience=ai 的 token，When 先以 `audience:'grassland'` 核销，Then 401；When 再以 `audience:'ai'` 核销，Then 401（烧毁；写进 spec 断言）。
- UI 验收：N/A（冒烟截图为流程证据非视觉自查，§8）。
- 保留行为回归：e2e describe 内既有四用例（主导航免登/反向免登/门店深链锁定/游客错 token）补 audience 后全部语义保持。

**完成后**：按 §14 报告（冒烟截图路径列入证据）。

---

## 12. 测试、验证命令与集成验收

### 12.1 需求追踪与验收覆盖

| 需求/不变量 | 实现卡 | 业务/契约条款 | 边界场景 | 验收编号 | 测试编号 | 执行命令/手工步骤 | 证据 |
|---|---|---|---|---|---|---|---|
| REQ-001 | C-01 | §5.1/§5.2-1/§6.1 | C-01/E01、E06、E13 | AC-001/002 | TC-C01-001~005 | V-001 | Gradle 报告 |
| REQ-002 | C-01 | §5.3、§6.4 | C-01/E03、E08、E09、E19 | AC-003/004 | TC-C01-006~009、012 | V-001 | Gradle 报告 |
| REQ-003 | C-01 | §5.3-③ | C-01/E13/E17 | AC-005 | TC-C01-010/011、014 | V-001 | Gradle 报告 |
| REQ-004 | C-02 | §6 前端签名、D-06 | C-02/E01、E03~E06、E09、E10、E19 | AC-006/007 | TC-C02-001~008 | V-002~005 | Vitest 报告 |
| REQ-005 | C-03 | D-05、D-08 | C-03/E01、E02、E06、E10 | AC-008/009/010 | TC-C03-001~004 | V-006~009；e2e 真跑归 V-102 | 截图/报告 |

### 12.2 测试用例（逐用例）

#### TC-C01-001：issueRequiresLoginSession（保留，回归）
| 项目 | 内容 |
|---|---|
| 对应条款 | REQ-001、§6.4 |
| 风险/类别 | 高；安全回归 |
| 测试层级 | Controller IT |
| 实现位置 | `CrossAppTokenControllerIT.issueRequiresLoginSession`（保留原样） |
| 前置数据 | 空会话 |
| 输入 | `POST /api/auth/cross-app-tokens`，无 body、无 Cookie |
| 依赖模拟 | 无（自备 PG+Redis 容器） |
| 操作步骤 | 1. 发请求 |
| 预期展示/响应 | 401，`$.error`=`请先登录`（认证优先于 body 校验的证据） |
| 预期副作用 | 零（无 Redis 写、无审计） |
| 最终状态 | 库/Redis 无新行 |
| 清理 | 容器自动 |
| 执行与证据 | V-001 |

#### TC-C01-002：exchangeWithoutTokenReturns401（保留，回归）
`{}` body → 401（证明 ①在②前：无 token 字段时不受 audience 400 影响）。其余同上模式。

#### TC-C01-003：exchangeWithMalformedTokenReturns401（保留，回归）
`../../etc/passwd` → 401，不触 Redis。

#### TC-C01-004：issue 登录后缺 audience → 400
| 项目 | 内容 |
|---|---|
| 对应条款 | REQ-001、§6.4 |
| 风险/类别 | 高；负向 |
| 测试层级 | Controller IT |
| 实现位置 | `CrossAppTokenControllerIT.issueWithoutAudienceReturns400`（新增） |
| 前置数据 | `seedUser`+`signCookie` 合成账号 |
| 输入 | ①无 body ②`{}` ③`{"audience":"  "}` 三种 |
| 依赖模拟 | 无 |
| 操作步骤 | 1. 带 Cookie 依次 POST issue |
| 预期展示/响应 | 三种均 400，`$.error`=`无效的目标应用` |
| 预期副作用 | 无 Redis 写、无审计行 |
| 最终状态 | 同上 |
| 清理 | 容器自动 |
| 执行与证据 | V-001 |

#### TC-C01-005：issue 未知/错误大小写 audience → 400
`{"audience":"ops"}`、`{"audience":"AI"}`、`{"audience":"ai extra"}`（trim 后不等于枚举）→ 400；`{"audience":" ai "}`（trim 后合法）→ 200。新增 `issueAudienceValidation`。

#### TC-C01-006：exchangeCreatesSessionCookieAndOriginalSessionSurvives（改写，主链回归）
issue `{"audience":"ai"}`（带 Cookie）→ exchange `{"token":…,"audience":"ai"}`（不带 Origin）→ 200 全断言（Set-Cookie `y1.sid`/HttpOnly/SameSite=Lax、`/api/auth/me` 新旧会话均 200、审计 2 行 `cross_app_token%` 且无 token 明文——断言逐字保留）。

#### TC-C01-007：tokenIsSingleUseReplayReturns401（改写，回归）
两处 exchange 均带 `"audience":"ai"`；第二次 401 统一文案断言保留。

#### TC-C01-008：错 audience 核销 401 且烧毁（新增）
| 项目 | 内容 |
|---|---|
| 对应条款 | REQ-002、AC-003 |
| 风险/类别 | 高；安全正向（本任务核心） |
| 测试层级 | Controller IT |
| 实现位置 | `CrossAppTokenControllerIT.exchangeWrongAudienceBurnsToken`（新增） |
| 前置数据 | 合成账号+会话 |
| 输入 | issue `{"audience":"ai"}` → exchange `{"token":T,"audience":"grassland"}` → exchange `{"token":T,"audience":"ai"}` |
| 依赖模拟 | 无 |
| 操作步骤 | 1. 签发取 T；2. 错 audience 核销；3. 正确 audience 重试 |
| 预期展示/响应 | 步 2 401 统一文案；步 3 401（已烧毁） |
| 预期副作用 | 仅一次 GETDEL；核销全部失败→无会话、无核销审计（签发审计 1 行） |
| 最终状态 | Redis 无该 token |
| 清理 | 容器自动 |
| 执行与证据 | V-001 |

#### TC-C01-009：exchange 缺 audience/未知值 → 400（新增）
形态合法 token（先直写 nonce 或先签发）+ body `{"token":T}` / `{"audience":"ops"}` → 400；且**未烧毁**（400 在 ②拦截，未到 GETDEL——补一次正确请求 → 200 证明）。

#### TC-C01-010：Origin 不匹配 401 且不烧毁（新增，AC-005 核心）
类级 `@DynamicPropertySource` 配 `audience-origins.grassland=http://gl.test`、`.ai=http://ai.test`。issue ai（带 `Origin: http://gl.test`——issue 不校验 Origin，仅推导 source）→ exchange `{"audience":"ai","token":T}` + `Origin: http://evil.test` → 401 → 同 token + `Origin: http://ai.test` → 200。

#### TC-C01-011：Origin 匹配成功核销（新增）
`Origin: http://ai.test` + audience ai → 200（收紧路径的正向证明；与 TC-C01-010 合并实现亦可）。

#### TC-C01-012：旧格式裸载荷 401（新增）
`seedNonce` 直写裸 accountId（用既有辅助改参数或新辅助写 `accountId` 裸值）→ exchange 任意合法 audience → 401。

#### TC-C01-013：CrossAppTokenStoreTest（新增，单元）
`encodePayload/parsePayload` 往返；裸值/两段/四段/空段/null → `Optional.empty()`；三段正常解析字段正确。纯 JUnit 无容器。

#### TC-C01-014：CrossAppAudienceOriginsTest（新增，单元）
直接 new 组件（构造传配置串）：①`allows`：origin 空→true；audience 无配置（空串构造）→true；配置 `"http://a.test, http://b.test"`（含空格）→`http://a.test`/`http://b.test` true、`http://a.test:80`/`http://evil.test`/`https://a.test` false（精确匹配证据）；未知 audience→true；②`audienceOf`：命中→对应值，未命中/null→`unknown`；③`validAudience`：null/`""`/`"AI"`/`"ops"` false，`"ai"`/`"grassland"` true（注意调用方先 trim，组件不做 trim——决策 D-03/D-07 在控制器 trim）。纯 JUnit。

#### TC-C02-001：无 xat no-op（改写）
`consumeCrossAppTokenFromUrl('ai')`（URL 无 `xat`）→ `none`，fetch 零调用。

#### TC-C02-002：成功/失败都清参+请求体断言（改写）
| 项目 | 内容 |
|---|---|
| 对应条款 | REQ-004、AC-006 |
| 风险/类别 | 高；正向+负向 |
| 测试层级 | 单元（happy-dom） |
| 实现位置 | `useCrossAppToken.test.ts` consume describe |
| 前置数据 | URL 预置 `?xat=<TOKEN>` |
| 输入 | fetch mock：`/exchange`→200 / 401 两种 |
| 依赖模拟 | fetch 全局 stub |
| 操作步骤 | 1. 置 URL；2. 调 `consumeCrossAppTokenFromUrl('ai')`；3. 断言请求体 `JSON.parse(fetchMock.mock.calls[0][1].body)` 为 `{token:TOKEN, audience:'ai'}`；4. 断言 `location.search===''` |
| 预期展示/响应 | `exchanged`/`failed`；URL 干净 |
| 预期副作用 | 恰一次 fetch |
| 最终状态 | 无 token 残留 |
| 清理 | 既有 afterEach |
| 执行与证据 | V-002 |

#### TC-C02-003：请求发起时 URL 已无 xat（新增，referrer 机制）
fetch mock 回调内断言 `window.location.search` 不含 `xat`（请求时刻页面 URL 已清——Referer 由该 URL 构造，机制等价断言，D-08）。

#### TC-C02-004：失败路径 console 无 token（新增，错误日志场景）
`vi.spyOn(console,'error')`+`warn`；fetch 401；断言所有调用参数的字符串化结果不含 TOKEN 明文（当前实现本就不打日志——本用例是防回归哨兵）。

#### TC-C02-005：jumpToAiApp 签发带 audience=ai（改写）
fetch mock 断言 `JSON.parse(init.body)`=`{audience:'ai'}`；跳转 URL 断言保留。

#### TC-C02-006：jumpToGrassland 签发带 audience=grassland（新增）
对称断言。

#### TC-C02-007：未登录不发签发（保留）  #### TC-C02-008：签发失败降级（保留）
原样保留（E19 证据：503→不带 token 直跳）。

#### TC-C03-001：主导航免登+历史回溯（改写，AC-009）
既有用例补：落 AI、`auth-pill` 出现、URL 无 `xat` 后，`await page.goBack()` → `await page.goForward()` → `auth-pill` 再现 + URL 无 `xat=`。

#### TC-C03-002：反向免登（保留）  #### TC-C03-003：门店深链锁定（改写：issue 调用补 `{ data: { audience: 'ai' } }`，其余不动）
原语义保持。

#### TC-C03-004：错 audience 401+烧毁 API 级（新增，AC-010）
| 项目 | 内容 |
|---|---|
| 对应条款 | REQ-002/005、AC-010 |
| 风险/类别 | 高；安全正向 e2e |
| 测试层级 | E2E（API 级） |
| 实现位置 | 同 describe 新用例 |
| 前置数据 | e2e seed 账号（草场登录，浏览器 cookie） |
| 输入 | `page.request.post('/api/auth/cross-app-tokens',{data:{audience:'ai'}})` 取 T → exchange `{data:{token:T,audience:'grassland'}}` → exchange `{data:{token:T,audience:'ai'}}` |
| 依赖模拟 | 无（真实隔离栈） |
| 操作步骤 | 1. UI 登录；2. 三次 API 调用 |
| 预期展示/响应 | 200 / 401 / 401 |
| 预期副作用 | 无会话建立（三次中仅签发成功） |
| 最终状态 | token 烧毁 |
| 清理 | ci-e2e 栈自毁 |
| 执行与证据 | V-102（集成） |

### 12.3 本任务验证清单

| 验证编号 | 适用卡/阶段 | 工作目录与 shell | 精确命令或手工步骤 | 前置环境/副作用 | 必需性 | 通过标准 | 证据路径 |
|---|---|---|---|---|---|---|---|
| V-001 | C-01 | `platform-java/`，bash | `source ../scripts/lib/java-runtime.sh && ensure_java_runtime 25 && ./gradlew :services:identity-service:test --tests 'com.grassland.identity.auth.CrossAppTokenControllerIT' --tests 'com.grassland.identity.auth.CrossAppTokenStoreTest' --tests 'com.grassland.identity.auth.CrossAppAudienceOriginsTest'` | Docker（Testcontainers 自备 PG/Redis）；写 build/ | 必需 | 退出码 0，全部用例过 | `platform-java/services/identity-service/build/reports/tests/test/` |
| V-002 | C-02 | 仓库根 | `npm run test -- src/composables/useCrossAppToken.test.ts` | 无 | 必需 | 退出码 0 | vitest stdout |
| V-003 | C-02 | 仓库根 | `npm run typecheck` | 无 | 必需 | 退出码 0 | stdout |
| V-004 | C-02 | 仓库根 | `npx eslint src/composables/useCrossAppToken.ts src/composables/useCrossAppToken.test.ts src/ai/AiAppLayout.vue src/layouts/DefaultLayout.vue` | 无 | 必需 | 退出码 0（全量 lint 有既有基线失败，§2.7；针对文件门禁不受影响） | stdout |
| V-005 | C-02 回归 | 仓库根 | `npm run test -- src/ai/AiAppLayout.test.ts` | 无 | 必需 | 退出码 0（壳测试零回归） | stdout |
| V-006 | C-03 | 仓库根 | `npx eslint tests/e2e/ai-creation-center.spec.ts` | 无 | 必需 | 退出码 0 | stdout |
| V-007 | C-03 | 仓库根 | `npm run test -- test/deployment/edge-entrypoint.contract.test.ts` | 无 | 必需 | 退出码 0（compose 契约不破） | stdout |
| V-008 | C-03 | 仓库根 | `docker compose config --quiet && docker compose config \| grep -A80 'identity-service:' \| grep -E 'AI_APP_ORIGIN\|GRASSLAND_ORIGIN'` | Docker CLI；`compose config` 只读渲染 | 必需 | 退出码 0 且输出 `AI_APP_ORIGIN`/`GRASSLAND_ORIGIN` 两行 | stdout |
| V-009 | C-03 冒烟 | 手工浏览器（127.0.0.1） | ①`cd platform-java && ./gradlew :services:identity-service:clean :services:identity-service:bootJar`；②仓库根 `docker compose build identity-service frontend && docker compose up -d identity-service frontend`；③`E2E_EMAIL=smoke86@test.local E2E_PASSWORD='Smoke!86xypass' E2E_ADMIN_EMAIL=smoke86-admin@test.local E2E_ADMIN_PASSWORD='Smoke!86adminpw' npm run e2e:seed:auth`；④浏览器 `http://127.0.0.1:8080` 登录 smoke86 → 头部「AI 创作」→ 断言落 8084 已登录、URL 无 `xat` → back+forward 断言无 `xat` 且仍登录 → 「打开草场」反向断言同构；⑤`http://127.0.0.1:8084/?xat=forged-token-0123456789abcdef0123456789ab` 断言登录弹窗接住 | 本地 compose 栈；重建镜像；写 `test-artifacts/taskbook-86/` | 必需 | ④⑤ 全部断言成立；截图 ≥4 张（双端免登各一、back/forward 后一、错 token 弹窗一） | `test-artifacts/taskbook-86/` |
| V-101 | 集成（负责人） | `platform-java/` | `./gradlew :services:identity-service:check` | Docker；全模块门禁 | 集成必需 | 退出码 0 | Gradle 报告 |
| V-102 | 集成（负责人/CI） | 仓库根 | `npm run e2e:ci`（含 TC-C03-001~004 真跑） | 构建隔离 compose 栈、seed、自清理；耗时长 | 集成必需（可由 CI 执行并附 run 链接） | 退出码 0，本 spec 全过 | CI run / 本地日志 |

注：V-009 步骤①先 clean 再 bootJar——Java 镜像为预构建 jar，UP-TO-DATE 指纹可能失灵（2026-08-29 实录）；冒烟疑点先探容器内 jar（`docker exec <容器> unzip -p app.jar | grep -ac` 对照新类名）。

### 12.4 当前仓库命令目录

按模板 §12.4 原文适用；本任务选用命令已全部落入 §12.3，无额外候选。

### 12.5 最终集成验收与完成定义

- 集成验收负责人：ZCode（跑 V-101/V-102 或核验 CI 结果）；用户负责生产发布授权（本任务不自动发布）。
- 前置条件：C-01/02/03 卡级 DoD 全过，无 BLOCKED。
- 集成清单：V-101、V-102。
- 串联流程：草场登录 → AI 免登（含 origin 校验收紧形态）→ back/forward → 反向免登 → 错 audience API 烧毁（TC-C03-001~004）。
- 保留行为：既有 e2e 全套、identity-service 全模块门禁、三入口构建、部署契约测试。
- 证据：CI run 链接/本地日志、Gradle/vitest 报告、`test-artifacts/taskbook-86/` 截图。
- 变更范围核对：新增 diff ⊆ §9.1；#85 任务书保持未跟踪不动。
- 交付状态判定按模板 §12.5 原文（「已实现，未完成验收」不得写成完成）。

### 12.6 发布与回滚

| 阶段 | 精确操作 | 执行人/所需授权 | 成功信号 | 停止/回滚触发条件 |
|---|---|---|---|---|
| 发布前 | 前后端镜像同批构建；确认目标环境 `GRASSLAND_ORIGIN`/`AI_APP_ORIGIN` 与实际访问 origin 一致（多域名逗号并列） | 运维/用户 | `docker compose config` 注入值与访问域名一致 | origin 不一致（用户将全部 401） |
| 发布 | 同批部署 identity-service + 前端镜像 | 用户授权 | 免登冒烟通过 | 冒烟失败 |
| 观察 | 免登成功率、`cross_app_token%` 审计量、401 比例 | 值班 | 401 无异常升高（除部署窗口 ≤300s） | 401 持续异常升高 |
| 回滚 | 回滚镜像批次（Redis key 兼容；旧代码读新 value→401→前端游客态，安全降级） | 用户授权 | 回滚后常规登录正常 | — |

---

## 13. 阻塞规则

按模板 §13 原文执行。本任务预登记的最近阻塞点：

1. B-01（C-02）：`src/ai/AiAppLayout.test.ts` 或其他未列入白名单的测试因签名变化失败且非断言口径问题——报告 BLOCKED，附失败输出；已核实预期不连坐（§2.4），失败即属「本卡实际实现需改白名单外文件」。
2. B-02（C-03）：本地 Docker 无法完成镜像重建（磁盘/网络）——记录 NOT_RUN 与原因，冒烟转由负责人执行；不得以「跳过冒烟」宣告完成。

---

## 14. 完成报告格式

按模板 §14 原文输出。本任务额外必填：每卡的 Redis 载荷示例（脱敏合成串）、冒烟截图绝对路径、混合版本推演未实测的声明（§2.9 矩阵保持「推演」标注，不升格为实测）。

---

## 附 A：返工卡格式

按模板附 A 原文执行。

## 附 B：强模型写作规约与发布前检查

### B.2 发布前检查表（已逐项自查）

- [x] 唯一实现方案，无未决方案二选一（枚举/顺序/文案/配置默认值全部定案 D-01~D-08）
- [x] 范围内/范围外/不许顺手修三段齐全（§1.3/1.4/1.5）
- [x] 文件白名单+黑名单已列（§9.1，14 写入 + 只读 + 禁止）
- [x] §2.5 当前行为如实描述并对照真实代码核实（全部行号 2026-09-06 核验）
- [x] 覆盖成功、失败、空数据、加载状态（本任务无列表/加载态——E 表已逐行 N/A 说明）
- [x] 覆盖重复提交与并发（GETDEL 原子+前端单次调用，E03/E15）
- [x] 接口字段、错误码、错误动作全部定义（§6.4：唯一新增文案 400「无效的目标应用」）
- [x] 数据兼容与迁移策略已定义（§7：无迁移、窗口失效、回滚降级路径）
- [x] UI 入口/组件/交互/响应式/明暗主题（N/A，§8 理由）
- [x] 每卡边界场景表无空行（三卡 E01-E22 逐行填毕）
- [x] 每个关键行为有 Given/When/Then 与自动化或明确手工验证（AC-001~010 ↔ TC ↔ V）
- [x] 所有验收可客观判断（无「体验良好」类措辞）
- [x] 可执行条款无占位符/未决选项/推迟决策
- [x] BLOCKED 条件已定义（§13 预登记 B-01/B-02 + 模板通用）
- [x] 命令、工作目录、工具链、fixture、预期退出码与副作用已核实；作者执行的基线为实跑结果（§2.7），未跑项标 NOT_RUN（全量门禁）；混合版本推演明确标「未实测」
- [x] 每张卡有最小必要锚点与真实路径/符号，签名类型完整（§6+卡内锚点）
- [x] 卡间依赖与执行顺序明确（C-01→C-02→C-03，理由 §10）
- [x] 所有卡写入集合均 ⊆ 全局写入集合；只读/生成物/已有改动/禁止项分开
- [x] 卡之间无未安排共享写入冲突；后置卡前置交付物具体（C-02 只依赖 §6 契约文本，不依赖 C-01 运行时）
- [x] §9.5 每项仓库约束已映射或 N/A
- [x] 三入口现状、设计路由、金额/HTTP/Java/数据/AI 复用点只保留已核实相关事实（§2.1/2.2/9.5）
- [x] 字段、错误、状态、权限、时间、金额、幂等键在需求/契约/卡/测试之间无矛盾（audience/token/Origin 三输入在 §5.1/§6/各 TC 间口径一致；401 统一文案三处引用同一字符串）
- [x] 需求→规则→文件/卡→AC→TC→V→证据无遗漏或悬空编号（§12.1 全链覆盖）
- [x] 集成负责人、最终门禁、上线授权边界和回滚条件已明确（§12.5/12.6）
- [x] 新依赖=无；无迁移；无计费调用；有副作用操作（镜像重建/compose up）已登记

反向审阅（B.3 要求）结论：已检查「满足文字却违反目标」的典型漏洞——①仅前端传 audience 不做服务端校验（§5.3 服务端四步+IT TC-C01-008 拦截）；②origin 校验烧毁合法 token（D-04 顺序+TC-C01-010 不烧断言）；③「先清参」只在成功路径执行（TC-C02-002 失败分支+TC-C02-003 mock 内时序断言）；④把 400 暴露成可探测的细分错误（D-07 统一 401）；⑤e2e 只看 URL 不验会话（TC-C03-001 auth-pill 断言）。未发现遗留漏洞。

### B.3 作者审阅与版本记录

| 修订版本 | 日期 | 变化原因与决策人 | 受影响条款/卡 | 是否重做发布检查 |
|---|---|---|---|---|
| v1.0 | 2026-09-06 | 初稿（ZCode 规划；基线 d39ae265 实核验；需求来自用户 P2 反馈） | 全文 | 是（通过，状态 READY_FOR_IMPLEMENTATION） |

## 附 C：随任务书交给弱模型的执行提示词

```text
任务书路径：docs/任务书/草场任务书-86-跨应用免登token目标应用绑定.md
批准版本：v1.0
本次指定任务卡：C-01 → C-02 → C-03（AUTO_CHAIN，按任务书 §10 顺序）

请按任务书实现指定卡，不负责重新定义需求。

1. 先遵守适用的系统/开发者/用户指令与 AGENTS.md，再按任务书阅读协议读 §0、§1、§5、§6、§9、§13、§14、当前卡及其引用。
2. 确认文档 READY_FOR_IMPLEMENTATION、版本一致、前置交付物满足、代码基线无未声明漂移，记录当前已修改/未跟踪文件。条件不满足先报告，不猜实现。
3. 输出简短计划，逐项对应本卡步骤。只写入本卡写入清单与全局写入白名单的交集；黑名单优先，参考文件不等于写权限。
4. 保留他人已有改动（当前仅未跟踪的 #85 任务书，勿动）；不做无关重构、依赖升级、全仓格式化或清理；可以按本地工作流提交和建分支，但提交不等于验收通过。严禁 git reset、git clean 和删除无关文件。全部使用本地环境/合成数据；不得访问真实账号、生产或支付。
5. 按确定契约实现：§5.3 校验顺序是红线（不得调整顺序或合并分支）；401 统一文案逐字使用任务书给出的字符串；复用 grassland-http.request 与既有测试基建。
6. 只有当前卡实际需要未定义的产品/安全/资金/范围决策，或需要改变已批准契约、写入边界时，才停止受影响卡并按 §13 报告（预登记 B-01/B-02）；仅阅读、搜索、测试或保持既有契约不构成阻塞。普通编码错误自行修复；本地依赖安装、镜像重建、本地容器与浏览器操作可直接执行并记录。
7. 真实执行本卡 TC/V 与必要回归（V-001~V-009 中的卡内必需项）；只选择了某些验证时不得宣称其他门禁已跑；未运行写 NOT_RUN，不写 PASS；不得删测试、放宽断言或降低阈值换绿灯。
8. 本任务无 UI 视觉改动，无明暗截图义务；C-03 冒烟截图按 §9.1 产物目录留存且不得包含真实 token。
9. 按 §14 报告变更、需求完成情况、命令/目录/退出码、脱敏证据、偏差和未完成项。
10. AUTO_CHAIN 模式下，当前卡 DoD、TC、V 全部真实通过后自动进入下一张已登记卡；若下一张卡依赖不满足或触发 §13 阻塞，停止并报告。卡已实现不等于整任务完成；V-101/V-102 集成验收由负责人执行。
```
