# ADR-D14：游客有限体验（AI 试用额度策略）

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-17 | D14（PRD §4.11 游客行 / §4.1） | Intelligence、edge、前端 | 平台内闭环拍板（2026-08-15） |

## 背景

未登录游客此前只能「可浏览」（生成动作前后端一致硬 401）。本决策放开**有限体验**：游客可试用选定 AI 能力，
以试用带动注册转化；同时把防刷边界定死。既有生成端点鉴权**零改动**（fail-closed 不动，作为回归基线）。

## 决策

### R1 体验目录：3 项白名单，配置驱动

| capability | 形态 |
|---|---|
| `article-titles` | 输入主题 → SSE 流式 5 个候选标题 |
| `content-score` | 粘贴文案 → 5 维评分 + 优化建议 |
| `image-review` | 上传 1 张图（base64 ≤4MB）→ 一段探店点评草稿 |

**明确排除**：视频制作（异步 job 高成本）、抖音/B 站提取（带宽型工具）、视频改编、图片生成、草稿/设置/BYOK/任务上下文。
目录做成配置列表（`ai.guest-trial.capabilities`），扩容不改代码面。cap 不在目录 → 404。

### R2 额度：匿名身份主键 + IP 双层兜底

- 每 capability **3 次/天**（`ai.guest-trial.daily-limit-per-capability`，按 capability 可覆写 `daily-limits`）。
- IP 日上限 **30 次/天**（所有 capability 合计；Redis INCR + 当日 TTL，镜像断言 replay 防护的 Redis 用法）——
  cookie 被清掉也刷不穿的真正闸门。**Redis 不可用时 fail-closed 拒绝**（不静默放行，同断言防护语义）。
- IP 短窗限流 **10 次/分钟**（挡脚本重试）。IP 取 `X-Forwarded-For` 首值（经 edge 入口），否则 remoteAddress；
  XFF 信任边界取决于入口代理配置，生产要求入口覆写 XFF（风险登记）。
- 默认值进本 ADR；生产可经配置收紧或经总开关 `ai.guest-trial.enabled=false` 整体关闭。

### R3 匿名身份：httpOnly 随机 cookie

首次访问经 trial 端点发放 `gtid`（随机 UUID，httpOnly、SameSite=Lax、Path=/、长 TTL；secure 经 env 约定）。
额度键 = `gtid`；cookie 被用户清除 → 额度重置，由 R2 IP 层兜底（权衡：不建设备指纹，隐私优先）。

### R4 计量与账本：intelligence 轻量表，**不进 finance 积分**

- `guest_trial_quota(gtid, capability, day, used)`，扣减用原子 `UPDATE ... WHERE used < :limit RETURNING`
  （镜像 finance credits 原子扣减范式）；日界按北京时间。
- 审计：`guest_trial_run(gtid, capability, ip_hash, outcome, created_at)` append-only；**不存原始 IP/UA**
  （SHA-256 截断哈希），无任何个人数据。
- **商业化边界（核心）**：游客试用 = 平台赞助的营销动作，**不建虚拟账号、不写 credits 流水、不产生对账噪音**；
  注册后才有用量账户与新用户赠送（既有 `CreditsService` 注册赠送路径）。试用产物（草稿/评分）**不持久化、不迁移**
  ——无账号可迁，PRD「不保存敏感设置」的自然延伸。**验收基线：finance credits 与 ai_run 表零新增行。**

### R5 独立窄面：`/api/guest-trial/{capability}`（POST，SSE）

单一 controller + 白名单路由；请求体各 cap 专属（image-review 用 base64 内嵌 ≤4MB，进 buffer 前校验，不落对象存储）。
SSE 帧复用既有约定（`data: {json}\n\n` + `[DONE]`；progress/result/error 帧联合）。
**失败语义锁定（二选一已定）**：连接建立（200 SSE + progress 帧）后的失败一律走 `{error, code}` 帧——
`quota_exhausted`（额度用尽，触发登录引导）与 `provider_error`；IP 限流在 SSE 前判，直接 **HTTP 429**（滥用非 UX）。
成功才计次（R6）；调用走既有 AI adapter（reactive，无新增阻塞边界）。

### R6 计次语义

**成功才算一次**（provider 失败/超时不烧游客额度）；扣减时机 = provider 成功返回后、result 帧下发前；
扣减失败（并发超限）不影响已产出内容，仅下次拒绝（原子 UPDATE 空结果即弃）。

### R7 注册转化钩子

- 前端额度徽标（「今日剩余 N 次」，读专用 GET `/api/guest-trial/quota`）+ 用尽后登录引导弹层，
  文案带新用户赠送额度（读配置值，不硬编码）。
- **不加**注册时迁移/补偿试用记录的逻辑（R4 边界）。

### R8 隐私与合规

只存：随机 gtid、能力名、IP 哈希、结果、时间；不存输入内容与生成产物（内存态即焚）。可追溯性对游客降级为
「发生过什么」（PRD §4.14 运行留痕义务以账号为前提）。

## 影响

- **Intelligence**：`guesttrial` 包（controller/catalog/quota/audit/rate-limiter/prompts/service）+ V29 迁移 +
  配置全集；依赖 spring-data-redis-reactive（既有 classpath 经 platform-identity-assertion 已带，显式声明）。
- **edge**：RouteManifest 注册 `/api/guest-trial` → intelligence + flag `EDGE_ROUTE_GUEST_TRIAL_INTELLIGENCE`
  （默认 true；fail-closed：不注册即 404）。
- **前端**：AI 中心未登录「免费体验」入口（三能力迷你表单 + SSE 消费复用既有模式）+ 额度徽标 + 登录引导；
  登录用户不显示体验入口。
- **prompt 策略**：复制裁剪自既有（titles 裁自 article-generation、score 裁自 creation-assistant、image-review
  裁自 image-analysis），**不改既有 prompt 文件本体**。

## 不在范围

- 钱包充值/真实 PSP；设备指纹/更激进防刷；试用产物持久化与账号迁移；目录外能力（视频/提取/改编/图生成）。
