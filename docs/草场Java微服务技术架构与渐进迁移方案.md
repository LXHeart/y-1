# 草场 Java 微服务架构与渐进迁移蓝图

## Context

用户已明确决定将后端从 Express + TypeScript 迁移为 Java，并将“草场”作为长期项目按微服务架构建设。当前仓库已有可运行的 Vue 3 前端和 TypeScript 后端，前端强依赖现有 `/api/**` 路径、`y1.sid` Cookie、JSON 响应格式、POST SSE、Multipart 字段、签名媒体 URL 和 Range 视频流语义，因此不能采用一次性重写。对应产品需求见[《草场产品需求文档》](./草场产品需求文档.md)。

本方案采用**绞杀者迁移**：先建立 Java 网关/BFF，让 Vue 始终访问同一个 `/api` 入口；旧请求继续转发到 Express，新领域和已迁移能力进入 Java 服务。初始只建立粗粒度服务，避免把每个实体拆成一个服务而形成分布式单体。跨服务一致性使用本地事务、Transactional Outbox、Kafka、幂等 Inbox 和 Temporal Saga，不使用 XA/2PC。

---

## 1. 技术栈决策

- **JDK 25 LTS**；若关键依赖认证存在阻碍，可暂用 JDK 21 LTS，但所有服务统一版本。
- **Spring Boot 4 当前受支持小版本**，配套兼容的 Spring Cloud Release Train。
- **Gradle Kotlin DSL 多模块工程**，统一 Version Catalog 和内部 BOM。
- **Spring Cloud Gateway WebFlux**：统一入口、绞杀路由、Cookie/BFF、SSE 和二进制流代理。
- **Spring Security**：Web BFF Session；后续 APP/小程序使用 OAuth 2.1/OIDC Token。
- **jOOQ + HikariCP + PostgreSQL**：SQL-first、显式事务和锁；金融核心不使用隐式 JPA Cascade/Flush。
- **Flyway**：每个服务独立 migration 和历史表。
- **Kafka + Protobuf + Apicurio Registry**：领域事件和 Schema 演进。
- **Transactional Outbox + Debezium Outbox Event Router**：避免数据库与 Kafka 双写。
- **Temporal Java SDK**：T+2、争议窗口、审判投票、支付补偿、结算和退款等长流程。
- **Redis**：BFF Session、验证码、分布式限流、短期幂等响应和缓存；不能存金融事实。
- **S3 兼容对象存储**：凭证、截图、视频、生成图片和临时媒体；本地开发使用 MinIO。
- **OpenAPI 3.1**：Java 原生 `/api/v2`；现有 `/api` 使用冻结的兼容契约。
- **JUnit 5、AssertJ、Testcontainers、ArchUnit、Pact/Spring Cloud Contract、Playwright**。
- **Micrometer + OpenTelemetry + Prometheus/Grafana + Loki/ELK + Tempo/Jaeger + Sentry**。
- **Resilience4j**：仅对明确可重试的外部调用配置 timeout、bulkhead、circuit breaker 和有限重试。

不引入：分布式 XA、共享 ORM Entity、跨服务数据库 JOIN、每个请求经过大量同步服务调用、首期 Service Mesh。

---

## 2. 初始服务拓扑

初始控制在 6 个粗粒度部署单元：

```text
Vue / 后续 APP / 微信小程序
              │
          edge-bff
     ┌────────┼──────────┐
 identity  marketplace  finance
     │           │          │
   trust     intelligence  Kafka/Temporal
              │
        legacy-express（迁移期）
```

### 2.1 edge-bff

职责：

- 对外唯一 `/api/**` 入口和未来 `/api/v2/**`。
- 按 route manifest 将请求路由到 Java 服务或旧 Express。
- 保留现有 Vue 的 Cookie、错误格式、SSE、Multipart、媒体流和限流 Header。
- 解析用户会话，向内部服务签发短时身份断言。
- 公共限流、CSRF、CORS、安全 Header、Request/Trace ID。
- 对少量低扇出的页面并行聚合数据。

禁止：保存任务、资金、争议等业务事实；直接访问业务服务数据库。

### 2.2 identity-service

职责：

- 用户、凭据、账号状态和平台级角色。
- 商家组织、门店成员关系和权限。
- 推荐官身份档案、消费者身份绑定。
- 邮箱验证、未来手机号/微信绑定、MFA。
- Web Session 迁移、APP/小程序 Refresh Token Family。
- 登录、权限变更和设备审计。

推荐官等级由 trust-service 计算，identity 仅消费当前等级投影供授权和 UI 使用。

### 2.3 marketplace-service

职责：

- 商家任务、不可变任务版本、可见性和截止规则。
- 报名、审核、接受和履约快照。
- 凭证集合和 append-only 补交记录。
- 自动核实编排、人工复核结果、商家确认。
- 消费推广二维码、订单业务关联、核销码和核销动作。
- 任务大厅、商家工作台和推荐官工作台读模型。

首期把任务、报名、履约、凭证、核实和商家确认放在同一服务，因为这些是一个强一致状态机。平台数据采集 Adapter 可以异步独立扩容，但不拥有业务状态。

### 2.4 finance-service

职责：

- 三种金融产品的版本化 Policy。
- 支付意图、供应商回调、托管、退款和付款。
- 不可变双录总账。
- 平台补贴快照和费用入账。
- T+2 结算、核销分账、失败补偿。
- 每日支付与总账对账、异常队列和财务审计。

支付、账本、结算和对账初期保持一个部署边界，但内部按模块隔离。金融操作只能通过 finance-service Command 执行，其他服务不能直接改余额或总账。

### 2.5 trust-service

职责：

- 48 小时异议窗口、争议案件和证据引用。
- 7 名审判官选择、利益冲突排除、24 小时投票。
- 平票重开、上诉和客服最终裁决。
- 推荐官统计、等级、降级、徽章、审判资格和信任历史。
- 风险信号和人工审核建议。

trust-service 只能发布冻结/解冻/裁决事实，不得写 finance 数据库或自行过账。

### 2.6 intelligence-service

职责：

- AI Provider、模型配置、文案/图片/视频工具、配额和任务上下文。
- 抖音/Bilibili 提取编排、热点、媒体任务和生成素材。
- 第三方平台核实 Adapter、OCR/视觉分析。
- 飞书导出和外部 AI/媒体供应商接入。

迁移早期由 Java 作为控制平面，已有 Node 能力作为内部 legacy worker 继续执行 Playwright、FFmpeg、抖音/Bilibili 提取和部分 SSE AI 流；稳定后再逐项重写。AI/OCR 结果只能作为核实建议，不能直接触发不可逆资金动作。

### 后续按真实压力拆分

只有出现独立团队、容量、合规或发布节奏需求时，才从上述服务拆出：

- media-service / ai-tools-service
- ledger-service / payment-connector-service
- dispute-service / reputation-service
- notification-service
- analytics/search projection service

---

## 3. 数据所有权

初期可以共用一个托管 PostgreSQL Cluster，但使用独立逻辑数据库和独立账号：

```text
identity_db
marketplace_db
finance_db
trust_db
intelligence_db
legacy_db
```

强制规则：

- 每个服务仅能读写自己的数据库。
- 不做跨库 JOIN、跨服务 FK 或共享 Repository/Entity。
- 跨服务 ID 使用 UUID/ULID，不依赖对方数据库约束。
- 每个服务有独立 Flyway 历史。
- 每张迁移中的旧表始终只有一个权威写入方。
- BFF 不允许连接业务数据库。
- 跨服务页面使用 BFF 小规模聚合或 Kafka 驱动的本地读模型。

现有表迁移归属：

- `app_users`、邮箱验证码 → identity-service。
- `session` → 迁移期 legacy；最终会话进入 Redis。
- `user_settings` 中 AI/首页/图片风格设置 → intelligence-service。
- 热点缓存 → intelligence-service。
- 新的任务、财务、争议数据从第一天即写入各自数据库，不先落旧库。

读模型保存 `source_event_id`、aggregate/version 和 projectedAt，重复或旧版本事件直接忽略。

---

## 4. API Gateway/BFF 兼容策略

建立版本化 route manifest，每条路由声明：method、path、upstream、认证、限流、超时、Body 上限、JSON/SVG/SSE/Binary 模式和回滚开关。

现有 `/api/**` 必须保留：

- `{success:true,data}` 和 `{success:false,error:string}`。
- 现有中文错误、状态码和字段名。
- `y1.sid`、HttpOnly、SameSite=Lax、Secure、滚动 7 天。
- `/api/auth/captcha` 原始 `image/svg+xml`。
- Multipart 字段 `images` 及当前数量/大小限制。
- POST + fetch SSE：`data: JSON\n\n`、`data: [DONE]\n\n`。
- `X-Accel-Buffering: no` 和及时 flush。
- 签名代理/下载/音频路径。
- `Range`、`If-Range`、200/206/416、`Content-Range`、`Content-Length`、`Content-Disposition`。
- `RateLimit-Limit/Remaining/Reset`。

BFF 的 SSE 和 Binary Route 禁止聚合 Response Body，也禁止对非幂等 POST 自动重试。所有内部身份 Header 必须在边缘先清除，再由 BFF 重新签发。

未来 APP/小程序使用 `/api/v2/**`，采用结构化错误、Cursor Pagination、Idempotency-Key 和 OAuth Token，不把旧 Vue 契约永久扩展给所有客户端。

---

## 5. 登录与会话迁移

### 阶段 1：Express 仍为会话权威

- BFF 原样转发 `y1.sid`。
- BFF 通过受限的 Session Introspection 或兼容桥读取旧 Session。
- Java 服务不直接解析浏览器 Cookie，只接受 BFF 的短时内部 JWT/mTLS 身份。

### 阶段 2：双读会话桥

- BFF 优先读取 Redis Session。
- 未命中时验证旧 Cookie 和 `connect-pg-simple` Session。
- 成功后迁移为 Redis Session，继续返回同名 Cookie。
- Express 在迁移期也可通过内部 Introspection 接受新会话。

### 阶段 3：identity-service 成为权威

- 登录、注册、验证码、`/me`、退出全部切入 Java。
- 停止旧 Session 写入，等待剩余 TTL 自然过期后删除桥接。

密码迁移必须先核对真实数据库 Hash 格式。代码与文档可能存在 bcrypt/scrypt 差异，因此：

- Java 登录同时支持实际存在的 bcrypt 和 legacy scrypt 编码。
- 用户成功登录后 rehash 为 Argon2id。
- 不做无法验证的批量转换。
- 使用合成 Golden Fixture 测试，不复制真实密码 Hash 到代码库。

未来：Web 继续 BFF Cookie；APP 使用短期 Access Token + Rotation Refresh Token；微信小程序使用 code 换取平台身份。高风险财务和后台操作增加重新认证/MFA。

---

## 6. Kafka、Outbox、Inbox 与 Saga

### 事件规范

Protobuf Envelope 至少包含：

- `event_id`
- `event_type`
- `schema_version`
- `aggregate_type`
- `aggregate_id`
- `aggregate_version`
- `occurred_at`
- `correlation_id`
- `causation_id`
- `tenant_id/organization_id`（适用时）
- payload

Topic 按稳定业务域组织，不按每个实体建 Topic。消息 Key 使用 aggregate ID 保证同一聚合有序；不依赖不同 Key 的全局顺序。

每个服务：

- 业务事务同时写 Domain 表与 Outbox。
- Debezium 发布 Kafka。
- Consumer 先写 Inbox/Processed Event，再执行本地事务。
- 重复事件无副作用。
- 失败进入有限重试和 DLQ，Replay 必须受审计。
- Schema Registry 强制向后兼容；破坏性变化发布新事件版本。

### Saga/Workflow

选择 **Temporal**，用于：

- T+2 结算。
- 48 小时异议窗口。
- 24 小时审判投票和重开。
- 支付结果不确定、查询恢复和补偿。
- 退款、分账和 Payout 重试。
- 长时间核实与人工复核。

原则：Temporal 保存流程进度，PostgreSQL 仍是业务和金融事实来源。Activity 必须幂等；Workflow 不直接写数据库；每一步执行前重验本地事实。

不使用 XA。典型“接受报名并预留资金”：

1. marketplace 本地事务接受申请、创建 pending engagement、写 Outbox。
2. Saga 请求 finance 预留。
3. finance 本地事务幂等创建预留并发布结果。
4. 成功后 marketplace 激活履约；失败则执行补偿并释放名额/标记失败。

---

## 7. 金融架构

金额统一使用最小货币单位和 ISO Currency。Java 使用封装后的 `long`/`BigInteger` Money Value Object；数据库使用 `BIGINT`，API 使用字符串避免跨端精度问题。首期仅 CNY。

核心模块：

- Payment Intent / Attempt / Provider Webhook Inbox
- Escrow Case / Allocation
- Ledger Account / Journal / Posting
- Settlement Instruction
- Refund / Payout
- Reconciliation Run / Exception
- Idempotency Record

双录规则：

- 每个 Journal 同币种借贷合计为零。
- Journal finalized 后不可 UPDATE/DELETE。
- 更正只能追加 Reversal。
- 每个业务 Command、Provider Transaction 和 Webhook Event 唯一。
- 缓存余额只是 Projection，可由 Posting 重建。
- 平台等级加成单独记录为 Subsidy Expense 和推荐官应付。

支付 Webhook：保留 Raw Body → 验签/时间戳/nonce → Event ID 去重 → 持久化 → 更新 Payment Attempt → 写 Ledger/Outbox → 快速 ACK。超时但供应商可能已成功时进入 `UNKNOWN`，由主动查询和对账恢复，不能盲目重试扣款。

Settlement Policy 必须检查：商家确认、最终核实、争议窗口、开放争议 Hold、资金余额、收款资格、T+1/T+2 快照和命令幂等。Scheduler/Temporal 到期只触发 Command，不直接放款。

真实资金上线前必须确认持牌支付平台及分账/退款能力；系统不保存 PAN/CVV。

---

## 8. 媒体、提取和 AI 迁移

不要优先重写最脆弱的 Playwright/FFmpeg/平台解析代码。

迁移期：

- intelligence-service 创建任务、授权、配额和审计。
- legacy Express/Node worker 执行已有抖音/Bilibili、浏览器登录、FFmpeg 和部分 AI Stream。
- 通过内部 API/Kafka Job 调用，不暴露 Node 给前端。
- 媒体逐步迁移到 S3/MinIO，BFF/媒体组件保持签名 URL 和 Range 流。
- Java WebFlux/WebClient 负责 AI Provider SSE 编排和取消传播；Provider URL 继续实行 SSRF allowlist、DNS/IP 复核和重定向限制。

建议迁移顺序：热点和只读 Provider → AI 设置/模型验证 → 文章/图片/脱口秀 SSE → 视频分析/改编 → 媒体代理 → Bilibili → 抖音 Playwright Session。每一族路由达到契约和真实流量验证后再关闭 Node 实现。

---

## 9. 部署与运维

### 环境

- 本地：Docker Compose，包括 PostgreSQL、Kafka/KRaft、Apicurio、Redis、MinIO、Temporal、OTel Collector 和必要服务。
- 测试/生产：Kubernetes，使用托管 PostgreSQL、Kafka、Redis 和对象存储优先。
- 每个服务独立 Deployment、ServiceAccount、NetworkPolicy、PDB、HPA 和资源限制。
- edge-bff 是唯一公共入口；内部服务默认不可公网访问。
- 服务发现优先 Kubernetes DNS，不额外引入 Eureka。
- 初期不引入 Service Mesh；需要 mTLS 时再评估平台能力或 Mesh。

### 配置和密钥

- 非敏感配置使用 Spring Config Data + Kubernetes ConfigMap。
- 密钥使用 Vault 或云 Secret Manager + External Secrets Operator。
- 不建立一个包含所有服务密钥的中央配置库。
- 每个服务仅能读取自己的数据库和供应商凭据。
- BYOK 使用 Envelope Encryption，数据库只存 Ciphertext、Key Version 和 Masked Hint。

### CI/CD

- Monorepo Path-aware Pipeline，只构建受影响服务和共享模块。
- 阶段：format/static analysis → unit → ArchUnit → integration/Testcontainers → contract → image/SBOM/sign → deploy test → migration job → canary → smoke/E2E → promotion。
- Flyway 使用独立 Release Job；应用启动不竞争生产迁移。
- 使用 additive migration 和 expand/backfill/switch/contract。
- 镜像使用最小 JRE、非 root、只读文件系统，生成 SBOM 并签名。
- 金融服务必须人工审批后生产发布。

### 可观测性

所有 HTTP、Kafka、Temporal、支付、账本和 AI 调用传播 W3C Trace Context，并记录 request/correlation/causation/user/organization/aggregate/provider ID。敏感字段必须脱敏。

重点 SLO/告警：BFF 兼容错误率、Kafka consumer lag、Outbox age、DLQ、Temporal stuck workflow、支付未知状态、账本不平衡、对账差异、托管停留时长、争议积压、核实 inconclusive 比例、SSE 首包时间、媒体 206 错误率和 AI 成本。

---

## 10. 目标仓库布局

保留 Vue 和旧 Node，新增 Java Platform：

```text
/apps
  /web-vue                  # 迁移现有 src/
  /legacy-node              # 迁移期保留现有 server/
/platform-java
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  /build-logic
  /shared
    /observability
    /security
    /event-envelope
    /test-support
  /services
    /edge-bff
    /identity-service
    /marketplace-service
    /finance-service
    /trust-service
    /intelligence-service
  /contracts
    /openapi-legacy
    /openapi-v2
    /protobuf
  /deploy
    /compose
    /k8s
    /observability
/docs
  /adr
  /architecture
```

共享模块只能包含横切基础和协议，不共享业务 Entity、Repository 或数据库模型。

首批关键修改位置：

- `nginx.conf`：将 `/api` 指向 edge-bff，并保留 SSE/Range 设置。
- `docker-compose.yml`：加入 Java 基础设施和服务，保留 legacy Express。
- `src/composables/**`：首期不改行为；后续集中到 typed API client。
- `server/src/app.ts`：迁移期增加内部健康/会话/legacy worker 接口，逐步缩减公开路由。
- `server/src/lib/session.ts`、`server/src/lib/password.ts`：为会话和密码兼容提供事实依据。
- `server/sql/**`：旧库只做迁移兼容，不再承载新 Grassland 领域。
- 新增 `platform-java/**` 和契约测试夹具。

---

## 11. 渐进迁移阶段

### Epic 0：ADR、契约冻结和生产事实核对

- 建立 ADR 目录和决策记录。
- 导出现有 API Contract Matrix。
- 将现有 Supertest/Vitest 行为转为 Golden Wire Fixture。
- 核实生产密码 Hash、Session JSON、Cookie、数据库和媒体 Token 格式。
- 建立迁移 Dashboard 和每路由开关。

**退出条件**：兼容契约可自动执行；无未确认的登录/媒体格式。

### Epic 1：Java 平台骨架和 edge-bff

- Gradle/BOM/Boot/Cloud 基础。
- Gateway Route Manifest、Trace、JSON Log、Health、限流框架。
- `/api/**` 全量透明代理旧 Express。
- SSE、Multipart、SVG、Range、Cookie 契约测试。

**回滚**：Nginx 一键重新直连 Express。

### Epic 2：身份与 Session 绞杀

- identity 数据库和服务。
- 旧 Session Introspection、Redis Session 双读迁移。
- bcrypt/scrypt 验证和 Argon2id rehash。
- 逐路由迁移 captcha、send-code、register、login、me、logout。

**回滚**：单路由切回 Express；Redis Session 保持兼容，不删除旧 Session。

### Epic 3：事件、工作流和平台基础

- Kafka/Registry、Outbox/Debezium、Inbox、DLQ。
- Temporal Cluster/Namespace 和 Java Worker。
- S3/MinIO、统一 Idempotency、审计和通知骨架。
- Protobuf/Event Compatibility CI。

### Epic 4：marketplace-service MVP

- 组织/门店引用、任务不可变版本、发布和大厅。
- 报名、接受、履约快照、名额并发控制。
- 凭证上传、核实编排、人工复核和商家确认。
- 暂不接真实资金，只使用 finance sandbox reservation。

### Epic 5：finance-service Sandbox

- 三种 Product Policy。
- 双录总账、Escrow、Settlement Instruction、Refund/Payout 状态。
- Temporal T+2 和争议 Hold。
- Sandbox Payment Provider、对账框架和财务后台基础。

### Epic 6：trust-service

- 48 小时争议、客服人工裁决。
- 等级与统计投影。
- 稳定后增加 7 人审判、24 小时投票、平票重开和上诉。

### Epic 7：消费者核销和真实支付

- 推广二维码、消费订单关联、一次性核销。
- 核销与退款竞态测试。
- 合规确认后只接一个真实支付渠道。
- Webhook、退款、分账和每日对账生产演练。

### Epic 8：intelligence-service 和旧 Node 缩减

- 依次迁移热点、设置、AI 流、媒体和平台解析。
- 每组路由 Shadow/Canary，对比结果、Header、SSE 和性能。
- Node 最后只剩难迁移 Worker，随后下线或保留为受控专用组件。

### Epic 9：APP/小程序 `/api/v2`

- OAuth/OIDC Token、微信身份绑定、推送、扫码和支付跳转。
- 共享 OpenAPI Client 和 Capability Matrix。

每个 Epic 必须具备 Route-level rollback；数据库迁移只能向前修复，金融账本不能回滚删除。

---

## 12. 前 90 天范围

### 第 1–30 天

- 完成 Epic 0。
- 建立 `platform-java`、统一 BOM、CI 和 edge-bff。
- 全部旧 `/api` 经 BFF 透明转发。
- SSE、Cookie、Multipart、Range 和错误 Envelope 兼容测试通过。
- 建立本地 PostgreSQL/Kafka/Redis/Temporal/MinIO/OTel 环境。

### 第 31–60 天

- identity-service 基础、Session Bridge 和密码多格式验证。
- 分批迁移 Auth Routes。
- 完成 Kafka Outbox/Inbox、Temporal 和对象存储基础。
- 建立 marketplace/finance/trust 服务骨架和 ArchUnit 约束。

### 第 61–90 天

- marketplace MVP：组织引用、任务版本、发布、任务大厅、报名和接受。
- finance Sandbox：Reservation 接口和最小双录账本。
- 第一条 Saga：接受报名 → 资金预留 → 激活履约/补偿。
- Vue 通过原 `/api` 使用首个 Java 草场流程。

90 天明确不做：真实支付、所有社交平台自动核实、完整争议审判、APP/小程序、全量 AI 重写、一次性关闭 Express。

---

## 13. 测试和验收

### 兼容性

- 现有 Vitest/Supertest 全部继续运行。
- Java Golden Contract 覆盖 JSON、201/400/401/403/429、Cookie、Captcha SVG、Multipart 限制、SSE 帧和取消、Range 200/206/416、下载 Header 和签名 URL。
- Gateway Route 切换前进行 Shadow Read；有副作用命令不做双写，只在 Sandbox/Replay 环境验证。

### 服务内部

- 单元：Money、状态机、权限、Policy、Event Mapping。
- PostgreSQL Testcontainers：事务、行锁、唯一约束、Flyway、并发竞争。
- Kafka：Outbox 原子性、重复/乱序、Inbox 幂等、Schema 兼容。
- Temporal：Timer、Retry、Compensation、Worker 重启和 Workflow Replay。
- ArchUnit：禁止 Domain 依赖 Spring/HTTP/数据库，禁止跨服务共享业务模型。

### 金融

- 双录平衡 Property Test。
- 三种产品 Golden Lifecycle。
- Webhook 重放、乱序、超时未知状态。
- 开放争议禁止结算。
- 核销与退款并发只能一个成功。
- Provider Sandbox Contract 和每日对账演练。

### E2E

- 商家发布 → 推荐官报名 → 接受 → Sandbox Reservation → 履约。
- 提交凭证 → 核实 → 商家确认 → 争议 Hold → 最终 Settlement Instruction。
- 消费下单 → 核销 → 分账。
- 旧视频/AI 页面在 Java BFF 前后行为一致。

---

## 14. 必须记录的 ADR

在实际写入 `docs/adr` 前逐份提交用户确认：

1. Java/Spring Boot 微服务与绞杀迁移。
2. 六个初始服务边界。
3. Database-per-service 与禁止跨库访问。
4. Kafka + Protobuf + Apicurio。
5. Outbox/Debezium + Inbox，不使用 XA。
6. Temporal 作为长流程引擎。
7. jOOQ 而非 JPA 作为核心持久化方案。
8. Finance 内部不可变双录账本。
9. BFF Cookie 与 `/api` 兼容策略。
10. Legacy Node Worker 的临时保留和退出标准。
11. Web Cookie、APP/小程序 OAuth 的双认证策略。
12. S3/MinIO 作为证据与媒体事实存储。

---

## 15. 实施原则

- 这是 Java 微服务目标，但迁移过程允许 Node 作为受控 Legacy Worker；不能为了语言纯度牺牲业务连续性。
- 服务按业务一致性和团队边界拆分，不按数据库表或名词拆分。
- 先兼容、再迁移、后优化；每一族路由均可单独切回旧实现。
- 新草场领域从第一天进入 Java 服务，旧营销工具按价值和风险逐步迁移。
- 新旧系统不长期双写同一业务表。
- 金融事实、证据和审计不可通过回滚删除。
- 任何 AI、OCR、爬虫或平台 Adapter 都不能独立执行最终资金裁决。
