# ADR-D04：各发布平台核实方法与官方数据源接入

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 部分采纳（P1 骨架已落地；逐平台合法性与凭据未决） | 2026-08-21 | D04（HLD §19 D-04 / PRD §9 核实维度 / GL-P2-VERIFY-001） | Marketplace 核验引擎、Intelligence 官方数据适配 | 无（独立）；凭据管理复用 SOPS（GL-P0-SEC-001） |

## 背景

PRD §9 要求图文核实点赞/收藏/评论数、视频核实播放/点赞/评论，并注明「官方 API/第三方工具/截图
AI 识别需按平台开放程度逐一确认」。HLD §12.2 预留了防腐层端口 `VerificationDataAdapter`
（能力=链接、授权 API、指标、截图/OCR 建议），但把「逐平台可用和合法方案」列为 TBD——
这就是 D-04。进度指南 §四把「真实平台履约指标（官方平台指标数据源）」列为 P1 开放项；
评论类互动（缺口清偿之九）的遗留也点名「平台官方评论数据源（P1）」。

触发场景：当前核验证据=链接可达 + 域名一致性 + 推荐官自证截图的 AI 语义判定——全部是
**自证或弱证据**。商家申报的互动指标（D-02 阶梯佣金的 `confirmed_metric_value`）也只有
商家口述，无平台侧核对。

## 当前代码现状

- 核验引擎 `EngagementVerificationService`：检查表 `link_reachability` / `platform_identity`
  （硬编码域名表，未知域名不强判）/ `evidence_completeness` / `ai_visual` /
  `interaction_screenshot`（含评论语义一致判定项）——tri-state 聚合，inconclusive 进
  既有运营人工队列。
- `platform_identity` 源码留有接缝注释：「A generic/public URL remains governed by
  reachability **until an official platform adapter is configured**」。
- 平台对接现状：抖音/B站的**公开网页解析**（无凭据）+ Qwen 多模态 + 60s/ALAPI 热点聚合；
  全仓无任何开放平台（open.douyin / open.bilibili）客户端雏形。
- 凭据管理模式：平台级凭据走 env/SOPS fail-fast（`PlatformModelConfig`）、per-provider
  secret map（`MarketingAttributionProperties`）、能力 gate `@ConditionalOnProperty`
  fail-closed——三种范式均已验证。

## 决策

### D1 核实方法分级（对齐 HLD「P0/P1」话术，区别于 GL 优先级）

| 级 | 方法 | 状态 |
|----|------|------|
| P0 | 链接可达 + 域名一致性 + 截图 AI 语义判定 + 人工复核 | 已落地（既有检查表 + ops 队列） |
| P1 | **经认证网关的平台官方数据**（账号归属/发布事实/互动指标/评论存在性） | 本 ADR：骨架已落地，数据源待凭据 |
| P2 | 独立可审计 OCR provider、逐平台原生 API 直连 | 未启动，按平台开放程度另行决策 |

P1 不直连任何平台开放 API：逐平台可用性、合法性（爬虫条款/开放平台资质/数据供应商牌照）
与商务签约**未决**，直连代码会是投机。落的是**防腐层**。

### D2 防腐层网关（本仓库定义契约，部署侧实现代理）

- intelligence `OfficialVerificationGateway`（`verification.official.gateway.*`，默认
  `enabled:false` fail-closed——bean 不装配）：调用**我们自定义契约**的认证网关
  `POST {base-url}/v1/official-verification`（Bearer token），由部署侧把网关实现为
  官方 API 代理或持牌数据供应商适配。换数据源不改本仓库代码。
- 网关响应契约（本 ADR 冻结；三态字段 null=无法判定）：
  `{"accountMatch":bool|null, "published":bool|null, "commentFound":bool|null,
    "metrics":{"likes":n,"comments":n,...}}`（metrics 键由网关归一，数值型）。
- 内部端点 `POST /internal/verification/official-data`（仅 marketplace 服务断言可调，
  不进 edge）；marketplace `IntelligenceOfficialVerificationClient` 封装。

### D3 核验语义（检查键 `official_data`）

- **省略**（检查项不出现在 checks）：网关未配置（默认）或 intelligence 调用失败——官方数据
  是附加证据源，「没有官方证据」≠「存疑」，不得把存量核验刷成 inconclusive 涌入人工队列。
- **inconclusive**：网关故障（unavailable）/申报评论在目标内容下不可见（可能被折叠/删除）/
  数据不完整（部分三态字段 null）。
- **failed**：官方数据明确否定（账号与申报平台账号不一致 / 目标内容未发布或不可见）。
- **passed**：账号一致 + 已发布；评论任务另需评论可见。detail 附归一化指标（供商家面板展示）。
- 聚合沿用既有 tri-state（failed > inconclusive > passed），人工复核走既有队列。

### D4 凭据与配置

- `VERIFICATION_OFFICIAL_GATEWAY_ENABLED/BASE_URL/TOKEN`（env；生产走 SOPS +
  materialize-production-secrets，不入库）。启用但缺 base-url/token → 启动失败
  （fail-fast，对齐 PlatformModelConfig）。
- 前端零改动（`official_data` 是检查表新键，商家面板按通用 check 渲染）。

## 取舍与理由

- **网关而非逐平台客户端**：逐平台合法性未决 → 契约由我们定义、代理由部署侧实现，
  是 HLD 防腐层端口的本意；签约某平台后只需部署网关 + 打开 flag。
- **未配置=省略而非 inconclusive**：与 AI 检查「不可用=inconclusive」刻意不同——AI 检查
  是既有证据链的一环（配置了就该可用），官方数据是配置了才存在的增量源。

## 开放问题（进入 P1 生产前必须回答）

1. 每个目标平台（抖音/小红书/B站）的官方数据获取渠道与合法性结论（开放平台资质？持牌供应商？）。
2. 网关的实现方与运维归属（平台自建 or 供应商提供），及指标归一口径（如「点赞」跨平台映射）。
3. D-02 阶梯佣金申报指标（`confirmed_metric_value`）是否在商家确认时点用官方数据交叉核对
  （当前决策：不在确认闸门强制，官方数据仍走核验流；若要强制需修订 D-02）。

## 实现索引

- intelligence：`verification/official/OfficialVerificationGateway`（fail-fast + WireMock
  契约测试）、`OfficialVerificationController`（内部端点 + 三态响应）。
- marketplace：`IntelligenceOfficialVerificationClient`、`EngagementVerificationService.
  officialDataCheck`（InteractionTaskFlowIT 三态用例：passed/failed/省略）。
- 配置：intelligence `application.yml` / `docker-compose.yml` / `.env.docker.example`。
