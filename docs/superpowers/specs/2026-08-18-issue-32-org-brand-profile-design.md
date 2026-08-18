# Issue #32 商家主体品牌资料 设计

- 日期：2026-08-18
- 状态：已定稿，待实现（计划见 `docs/superpowers/plans/2026-08-18-issue-32-org-brand-profile.md`）
- 范围：identity-service（资料表+API）+ intelligence-service（媒体 purpose/端点）+ 前端（组织管理区品牌资料面板）
- 登记：进度指南横向缺口 #32（PRD §2.1 商家主体资料）；推荐执行顺序第一批第 4 项
- 取代关系：worktree `feat/issues-26-32` 中未提交的品牌字段方案（挂 merchant_profile、复用 merchant_kyb 媒体、无乐观锁）作废，仅作参考；其迁移号 V33/V34/V28 与主线冲突，新实现用主线空号

## 目标

organization 主体拥有独立的品牌资料（品牌名称、品牌 Logo、商家简介、经营分类），owner/admin 可随时编辑、member 只读；Logo 走专用媒体 purpose 与 org 归属校验；版本乐观锁防并发覆盖；前端组织管理区提供与门店资料分区的编辑面板。仅商家后台读写，不做公开接口。

## 口径决策（已定死，勿另起方案）

| # | 决策 | 理由 |
|---|---|---|
| D1 | **独立新表 `organization_brand_profile`**（organization_id 主键单行 + version 乐观锁），不挂 `merchant_profile` | merchant_profile 受 KYB 审核门（approved 后不可编辑），品牌资料是营销信息须随时可改；且需求明确要求版本乐观锁，merchant_profile 无 version |
| D2 | 字段：`brand_name varchar(100)`、`brand_logo_media_reference_id uuid`（跨服务逻辑引用不建 FK）、`description varchar(2000)`、`industry varchar(32)`（`Industry` 枚举 dbValue，13 值复用，**不用自由文本**）、`version integer NOT NULL DEFAULT 0`、created_at/updated_at | 需求要求分类枚举与长度校验；复用现有 `Industry`（identity `permission/Industry.java`，前端已有镜像 union）；字段全可空（资料未填也是合法状态） |
| D3 | API：`GET /api/organizations/{orgId}/brand-profile`（MEMBER+，无行返回 version=0 的空资料）、`PUT`（ADMIN+，body 含 `expectedVersion`）。行存在：`UPDATE ... WHERE organization_id=:org AND version=:expected` 0 行→409「品牌资料已变更，请刷新后重试」；行不存在：`expectedVersion` 必须 0 否则 409，INSERT `ON CONFLICT DO NOTHING` 冲突→409。成功响应回完整资料+新 version | 乐观锁照 V31 `merchant_permission_request` 的 version CAS 路线（不抄 store_profile 的行锁）；PUT-upsert 语义，与门店资料的 POST-upsert 惯例差异在计划中说明 |
| D4 | 权限：PUT 与 Logo 开票 `OrgAuthorization.requireRole(ADMIN)`；GET `requireRole(MEMBER)`（member 只读）；非成员/跨组织 → OrgAuthorization 惯例 403「无权访问该组织」 | 需求「仅 owner/admin 可编辑，普通成员只读」；`MembershipRole.isAtLeast` 单调判定已覆盖 owner>admin>member |
| D5 | Logo 媒体：intelligence `MediaPurpose` 新增 `BRAND_LOGO("brand_logo")`；MIME 白名单 {image/png, image/jpeg, image/webp}、大小帽 `BRAND_LOGO_MAX_BYTES=2MB`；客户端自助开票黑名单加入 brand_logo（只能经 identity 服务断言代开）；`domain_type='brand_logo'`、`domain_id=organizationId` | 需求明确「增加品牌 Logo purpose、MIME/大小限制」；org 级归属四重过滤照 KYB `kybEvidence` 先例（purpose+organizationId+domainType+domainId） |
| D6 | 上传链路（三步，照 KYB 附件）：`POST /api/organizations/{orgId}/brand-profile/logo/upload-ticket`（identity `requireRole(ADMIN)` → 服务断言调 intelligence `POST /api/media/brand-logo-upload-tickets`，org 只取服务断言、ownerAccountId=操作者）→ 浏览器 PUT presigned → 现有 `POST /api/media/{id}/confirm` | 完全复用已验证的 KYB 票据代理与 confirm 内核 |
| D7 | 归属校验与展示：intelligence 新服务端点 `GET /api/media/{id}/brand-logo-url`（服务断言 + D5 四重过滤 + active + 未过期，通过则返回短 TTL presigned GET；不符/不存在统一 404）。identity `BrandLogoMediaClient`：PUT 保存时 fail-closed（404→400「品牌 Logo 媒体不可用或类型不符」，上游故障→503）；GET 资料时 fail-soft 解析 `logoUrl`（失败置 null，资料仍可读） | 单端点双用（校验信号=HTTP 状态，展示=URL），照 avatar-download-url + KYB validator 两先例合成 |
| D8 | 替换/删除语义：换 Logo = 新 mediaId 随 PUT 覆盖；清空 = `brandLogoMediaReferenceId=null`；旧媒体**不主动删**（头像先例，靠配额/TTL 自然回收） | 不引入 retention 租约体系（品牌 Logo 无 KYB 式法定留存需求），控制复杂度 |
| D9 | 公开性：仅组织后台（组织成员经鉴权）读写。任务大厅/任务详情/AI 创作上下文未来如需消费，再立项加白名单公开接口 | 需求「至少支持商家后台读写」为基线；当前无消费方 |
| D10 | 经营分类与 `organization.industry` 是两个字段互不影响（后者服务权限准入），品牌资料不回写 organization 表 | 语义不同（展示分类 vs 准入行业）；避免权限链路被品牌编辑扰动 |
| D11 | 前端：新独立组件 `OrganizationBrandCard.vue` 挂 GrasslandWorkbench 商家区（MerchantKybCard 之前），与门店资料分区展示；表单回填、Logo 压缩上传/预览/替换（照 MyRecommenderProfileCard 头像模式 + compress-image）、保存成功提示、409 提示「品牌资料已变更，请刷新后重试」（D3 后端契约文案，单一真相源在后端）并自动重拉；member 只读视图（无编辑控件） | 需求逐条对应；独立卡片满足「与门店资料分区」 |
| D12 | 迁移：identity `V35__organization_brand_profile.sql`（`CREATE TABLE IF NOT EXISTS`，幂等 DDL） | 重放测试约定；identity 主线最新 V34 |

## 现状锚点（2026-08-18 快照，行号漂移按符号搜）

- identity（`platform-java/services/identity-service/src/main/java/com/grassland/identity/`）
  - `membership/OrgAuthorization.java:32-39` `requireRole(request, orgId, minRole)`；`MembershipRole.java:10-43` OWNER>ADMIN>MEMBER
  - `store/StoreController.java:356-371`（请求 record 内嵌惯例）、`StoreMarketingFields.java:15-19`（长度帽集中定义惯例）
  - `permission/Industry.java:9-22`（13 值枚举 + `fromDb` 大小写不敏感）
  - 版本乐观锁样板：V31 migration + `MerchantPermissionRequestRepository.java:126-157`
  - 服务间媒体客户端样板：`kyb/KybMediaClient`、`AvatarMediaClient.java:49-59`（断言头 + fail-closed 503）；IT mock：`IdentityItSupport` 顶部 `@MockitoBean` + `stubKybMediaValidation`
  - 票据代理样板：`kyb/MerchantAttachmentController.java:77-97`
- intelligence（`.../intelligence-service/src/main/java/com/grassland/intelligence/media/`）
  - `MediaPurpose.java:18-25`；`MediaController.java`：`kyb-upload-tickets`(:131-137)→`createKybPending`(:330-348)、`avatar-download-url`(:296-306)、常量区(:54-81)、`createPending`(:308-328)、`validate` 的客户端禁用 purpose 黑名单
  - 测试：`MediaControllerIT`（`kybMetadataIsScopedToSignedOrganizationAndUsableEvidence` :462 是 org 归属样板）
- 前端：`src/components/MerchantKybCard.vue`（表单回填/防串扰版本号模式）、`MyRecommenderProfileCard.vue:112-135`（压缩上传+预览）、`src/composables/useGrasslandIdentity.ts`、`grassland-http.ts`（`GrasslandHttpError.status` 供 409 分支）、`src/types/grassland/organization.ts`（Industry union :125-128）、`GrasslandWorkbench.vue` 商家区（:1281-1337）
- 迁移：identity 主线最新 `V34`，本功能 **V35**；intelligence 媒体 purpose 是 varchar 无 CHECK，**不需要迁移**

## 测试（对应需求验收清单）

1. 权限矩阵（identity IT）：owner PUT ✓ / admin PUT ✓ / member GET 200 + PUT 403「权限不足」/ 未登录 401
2. 跨组织访问：非本组织成员（另一 org 的 admin）GET/PUT → 403，且无数据泄露
3. Logo 媒体：非法 MIME（gif/pdf）开票 400；超 2MB 400；客户端直连 `/api/media/upload-tickets` 申 brand_logo 被拒；`brand-logo-url` 对他 org 的 Logo 404；非 active/pending 媒体 404；服务断言缺失 401/403（intelligence IT）
4. 归属校验：PUT 带他 org 的 Logo mediaId → 400（identity IT，mock client 返回 404）
5. 字段校验：brand_name>100、description>2000、industry 非法值 → 400（中文 message）；全空值 PUT 合法（清空语义）
6. 乐观锁：并发/过期 expectedVersion → 409 且数据不被覆盖；首次创建 expectedVersion≠0 → 409
7. 与门店资料不串：同一 org 先写 store_profile 再 PUT brand-profile → GET store profile 字段不变（identity IT）
8. round-trip：PUT 全字段 → GET 回显 + version+1 + logoUrl（mock 正常）；无行 GET → version 0 空资料
9. 前端（Vitest+happy-dom+stubFetch）：表单回填；保存 payload（含 expectedVersion）；Logo 上传三步后 mediaId 入表单并随保存提交；409 提示+自动重拉；member 只读；卡片独立渲染不依赖 MerchantKybCard
10. composable：get/update/uploadBrandLogo 的错误分支（null 返回 + error 通道）

## 明确不做

- 公开（大厅/任务详情/AI 上下文）消费接口——等有消费方再立项（D9）
- Logo 媒体 retention 租约/留存体系（D8）
- 品牌资料审核流（区别于 KYB）；回写 organization.industry（D10）
- 品牌 Logo 之外的品牌媒体（如品牌图库）

## 完成标准

- 上述测试全部落地并通过；门禁全绿：`./gradlew :services:identity-service:test :services:intelligence-service:test`（JDK 25）+ `npm test` + `npm run typecheck` + `npm run build` + `git diff --check`
- CLAUDE.md API 路由表补新端点（identity 2 个用户端点 + 1 个票据端点；intelligence 2 个服务端点）
- 回写进度指南：#32 标完成、第一批第 4 项划掉、附测试证据
