# Issue #32 商家主体品牌资料 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** organization 主体的独立品牌资料（品牌名/Logo/简介/经营分类）——新表+版本乐观锁、专用 brand_logo 媒体链路（org 归属校验）、owner/admin 编辑 member 只读、前端独立面板与门店资料分区。

**Architecture:** identity 落 `organization_brand_profile` 单行表（V35，幂等 DDL），GET/PUT API 走 `OrgAuthorization.requireRole`（MEMBER 读/ADMIN 写）+ version CAS；Logo 复用 intelligence 媒体三步链路——identity 凭服务断言代开 brand_logo 票据，`brand-logo-url` 服务端点做四重归属过滤（兼做保存时校验信号与展示 URL 源）；前端 `OrganizationBrandCard.vue` 照「头像上传 + 门店资料表单」两样板合成。

**Tech Stack:** Java 25 / Spring WebFlux + R2DBC / Flyway / Testcontainers（IdentityItSupport、MediaControllerIT）、Vue3 + Vitest + happy-dom。

**Spec:** `docs/superpowers/specs/2026-08-18-issue-32-org-brand-profile-design.md`（D1–D12 为约束权威）

## Global Constraints

- JDK 25：`JAVA_HOME=/opt/homebrew/opt/openjdk@25`，gradle 在 `platform-java/` 下
- 迁移幂等：`CREATE TABLE IF NOT EXISTS`（V35）
- identity 无 ObjectMapper bean（不注入）；错误走 `IdentityException(status, 中文message)`
- intelligence 媒体错误走 `IntelligenceException`/`IllegalArgumentException`（照 MediaController 现状）
- Reactor：`switchIfEmpty` 参数位置副作用必须 `Mono.defer`；不吞错（Logo 校验 fail-closed 503、展示 fail-soft 是规格 D7 明确的例外）
- 每个 Task 单独 commit，中文 conventional commits：`feat(intelligence): #32 …` / `feat(identity): #32 …` / `feat(frontend): #32 …` / `docs: #32 …`
- 行号是 2026-08-18 快照，漂移按符号搜；动手前先读锚点
- 全程在 worktree 分支 `feat/issue-32-org-brand-profile`，不直接改 main

---

### Task 1: intelligence 品牌 Logo 媒体设施

**Files:**
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/media/MediaPurpose.java`（加 BRAND_LOGO）
- Modify: `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/media/MediaController.java`（常量区 + 两个端点 + `validate` 黑名单）
- Test: `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/media/MediaControllerIT.java`（追加）

**Interfaces:**
- Produces: `POST /api/media/brand-logo-upload-tickets`（服务断言 identity 专用；body `{ownerAccountId, contentType, sizeBytes}`；校验 D5 白名单后走既有 `createPending`，`domain_type='brand_logo'`、`domain_id=organizationId`）
- Produces: `GET /api/media/{id}/brand-logo-url`（服务断言；四重过滤 purpose+organizationId+domainType+domainId+active+未过期+MIME 白名单；通过返回 `{url, expiresAt}`，不符统一 404）

- [ ] **Step 1: 读锚点**——`MediaPurpose`、`MediaController` 的常量区(:54-81)/`kyb-upload-tickets`(:131-137)/`createKybPending`(:330-348)/`avatar-download-url`(:296-306)/`createPending`(:308-328)/`validate` 的客户端禁用 purpose 黑名单/`UploadSpec`/`MediaServiceDownloadResponse`；`MediaControllerIT.kybMetadataIsScopedToSignedOrganizationAndUsableEvidence`(:462) 与服务断言造数方式

- [ ] **Step 2: 写失败测试**（MediaControllerIT 追加，照 KYB 用例结构）：

```java
// ① brand-logo 票据：服务断言开票成功，落 pending 行 purpose=brand_logo domain_type=brand_logo domain_id=org owner=请求体账号
// ② 非法 MIME（image/gif、application/pdf）开票 → 400（「品牌 Logo 仅支持 PNG、JPEG 或 WebP 图片」）
// ③ sizeBytes 超 2MB / <1 → 400
// ④ 客户端直连 POST /api/media/upload-tickets 带 purpose=brand_logo → 400（黑名单）
// ⑤ brand-logo-url：confirm 激活后服务断言取 URL → 200 含 url；换 org 断言（他 org）→ 404；不存在 id → 404
// ⑥ 两个新端点缺服务断言（普通用户 cookie）→ 401/403
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd platform-java && JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test --tests "com.grassland.intelligence.media.MediaControllerIT"`
Expected: 新用例 FAIL

- [ ] **Step 4: 实现**

`MediaPurpose` 加：

```java
BRAND_LOGO("brand_logo"),
```

`MediaController` 常量区加（AVATAR 三常量之后）：

```java
/** 组织品牌 Logo（#32 D5）：org 级资产，仅图片 MIME + 独立大小帽，票据只能由 identity 服务断言代开。 */
private static final String BRAND_LOGO_PURPOSE = MediaPurpose.BRAND_LOGO.db();
private static final Set<String> BRAND_LOGO_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
private static final long BRAND_LOGO_MAX_BYTES = 2L * 1024 * 1024;
```

`validate` 的客户端自助开票 purpose 黑名单加入 `BRAND_LOGO`（照 MERCHANT_KYB 的写法与提示语风格）。端点（`kyb-upload-tickets` 后）：

```java
/** identity 完成组织授权（ADMIN+）后代申请品牌 Logo 上传票据（#32 D6）；组织上下文只取服务断言。 */
@PostMapping("/brand-logo-upload-tickets")
public Mono<Map<String, Object>> createBrandLogoUploadTicket(
        @RequestBody CreateBrandLogoUploadTicketRequest body, ServerWebExchange exchange) {
    return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
            .flatMap(caller -> createBrandLogoPending(caller, body))
            .map(MediaController::success);
}
```

`createBrandLogoPending` 完全照 `createKybPending` 结构：校验 `ownerAccountId` 必填、`contentType ∈ BRAND_LOGO_MIME_TYPES`、`1 <= sizeBytes <= BRAND_LOGO_MAX_BYTES`，`UploadSpec(contentType, MediaPurpose.BRAND_LOGO, BRAND_LOGO_PURPOSE, organizationId, sizeBytes, null)` → `createPending(ownerAccountId, organizationId, spec)`。请求 record `CreateBrandLogoUploadTicketRequest(ownerAccountId, contentType, sizeBytes)` 照 `CreateKybUploadTicketRequest` 位置声明。

服务端读端点（`avatar-download-url` 后）：

```java
/** identity 专用品牌 Logo 下载/校验端点（#32 D7）：org 级四重过滤，不符/不存在统一 404。 */
@GetMapping("/{id}/brand-logo-url")
public Mono<Map<String, Object>> brandLogoDownloadUrl(@PathVariable String id, ServerWebExchange exchange) {
    UUID mediaId = parseId(id);
    return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
            .flatMap(caller -> brandLogoAsset(mediaId, required(caller.organizationId(), 200, "服务断言 organizationId")))
            .map(ref -> new MediaServiceDownloadResponse(
                    storage.presignDownload(ref.objectKey(), downloadTtl(ref, Instant.now()), downloadDisposition(ref)),
                    ref.expiresAt()))
            .map(MediaController::success);
}

private Mono<MediaReference> brandLogoAsset(UUID mediaId, String organizationId) {
    return mediaRefs.findById(mediaId)
            .filter(ref -> BRAND_LOGO_PURPOSE.equals(ref.purpose())
                    && organizationId.equals(ref.organizationId())
                    && BRAND_LOGO_PURPOSE.equals(ref.domainType())
                    && organizationId.equals(ref.domainId())
                    && ref.status() == MediaStatus.ACTIVE
                    && BRAND_LOGO_MIME_TYPES.contains(ref.mimeType())
                    && !isExpired(ref, Instant.now()))
            .switchIfEmpty(notFound());
}
```

（`MediaServiceDownloadResponse`/`notFound`/`downloadTtl`/`downloadDisposition` 均为既有私有设施——若 record 构造签名不同按实际适配。）

- [ ] **Step 5: 跑测试确认通过**（含 MediaControllerIT 全类回归）
- [ ] **Step 6: Commit**：`git commit -m "feat(intelligence): #32 品牌Logo媒体purpose与服务端点"`

---

### Task 2: identity 数据层（V35 + 实体 + 仓储）

**Files:**
- Create: `platform-java/services/identity-service/src/main/resources/db/migration/V35__organization_brand_profile.sql`
- Create: `platform-java/services/identity-service/src/main/java/com/grassland/identity/brand/OrganizationBrandProfile.java`
- Create: `platform-java/services/identity-service/src/main/java/com/grassland/identity/brand/OrganizationBrandProfileRepository.java`
- Test: 并入 Task 3 的 `BrandProfileControllerIT`（数据层经 API 验证；本任务跑既有迁移重放测试回归）

**Interfaces:**
- Produces: `OrganizationBrandProfile(organizationId, brandName, brandLogoMediaReferenceId, description, industry, version, createdAt, updatedAt)` record
- Produces: `OrganizationBrandProfileRepository.find(String orgId): Mono<OrganizationBrandProfile>`（无行 empty）
- Produces: `OrganizationBrandProfileRepository.save(String orgId, String brandName, String logoMediaReferenceId, String description, String industry, int expectedVersion): Mono<OrganizationBrandProfile>`——行存在走 `UPDATE ... SET ..., version=version+1, updated_at=now() WHERE organization_id=:org AND version=:expected RETURNING ...`；行不存在且 expectedVersion==0 走 `INSERT ... ON CONFLICT (organization_id) DO NOTHING RETURNING ...`；任一 0 行 → empty（调用方转 409）

- [ ] **Step 1: 读锚点**——`OrganizationRepository`/`MerchantProfileRepository` 的 DatabaseClient 惯用法（SELECT_COLS/map/绑定）、`MerchantPermissionRequestRepository.java:126-157`（version CAS）、identity migration 目录确认 V34 为最新

- [ ] **Step 2: 实现 V35**：

```sql
-- #32 商家主体品牌资料（PRD §2.1）：组织级单行资料表，独立于 KYB merchant_profile 与门店 store_profile。
-- 媒体引用指向 intelligence.media_reference，跨服务不建 FK。
CREATE TABLE IF NOT EXISTS organization_brand_profile (
    organization_id uuid PRIMARY KEY REFERENCES organization(id),
    brand_name varchar(100),
    brand_logo_media_reference_id uuid,
    description varchar(2000),
    industry varchar(32),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

- [ ] **Step 3: 实现 record + repository**（照 MerchantProfileRepository 风格；nullable uuid 绑定用 `bindNull(name, UUID.class)`；save 的 update-then-insert 两步在同一 `transactions.transactional` 内由调用方包，或仓储内用 `Mono.defer` 串联——以调用方事务包裹为准，与 identity 现有 controller 模式一致）

- [ ] **Step 4: 跑迁移重放回归**：`./gradlew :services:identity-service:test`（确认 Flyway 全绿、无重放破坏；此时无新测试用例，数据行为由 Task 3 IT 验证）
- [ ] **Step 5: Commit**：`git commit -m "feat(identity): #32 品牌资料表与仓储（版本乐观锁）"`

---

### Task 3: identity API 层（client + controller + IT 全矩阵）

**Files:**
- Create: `platform-java/services/identity-service/src/main/java/com/grassland/identity/brand/BrandLogoMediaClient.java`
- Create: `platform-java/services/identity-service/src/main/java/com/grassland/identity/brand/BrandProfileController.java`
- Modify: `platform-java/services/identity-service/src/test/java/com/grassland/identity/IdentityItSupport.java`（`@MockitoBean BrandLogoMediaClient` + stub helper，照 `KybMediaClient`/`AvatarMediaClient` 模式）
- Test: Create `platform-java/services/identity-service/src/test/java/com/grassland/identity/brand/BrandProfileControllerIT.java`

**Interfaces:**
- Consumes: Task 1 两端点、Task 2 仓储、`OrgAuthorization.requireRole`、`Industry.fromDb`
- Produces: `GET /api/organizations/{orgId}/brand-profile`（MEMBER+；无行返回 `{brandName:null, brandLogoMediaReferenceId:null, logoUrl:null, description:null, industry:null, version:0}`）；`PUT`（ADMIN+，body `{brandName?, brandLogoMediaReferenceId?, description?, industry?, expectedVersion}`，成功回完整资料+新 version + logoUrl）
- Produces: `POST /api/organizations/{orgId}/brand-profile/logo/upload-ticket`（ADMIN+，body `{contentType, sizeBytes}`，透传 intelligence 票据响应）
- Produces: `BrandLogoMediaClient.usableLogoUrl(String mediaId, String orgId): Mono<String>`（200→url；404→empty；上游故障→`IdentityException(503,"品牌Logo服务暂不可用")`）、`createTicket(...): Mono<TicketResponse>`（透传 4xx 为 `IdentityException(同状态码, 上游中文错误)`）
- GET 的 logoUrl：行存在且 logo 非空 → `client.logoUrlFailSoft(...)` 失败置 null（D7）

- [ ] **Step 1: 读锚点**——`MerchantAttachmentController.java:77-97`（票据代理 + requireRole + 服务断言签发）、`AvatarMediaClient`/`KybMediaClient`（HTTP 基建、断言头、超时、fail-closed）、`OrgAuthorization`、`StoreMarketingFields`（长度帽定义风格）、`Industry`、`IdentityItSupport`（seedAccount/cookieFor/createOrg/加成员方式——照 `OrgTeamCard` 相关 IT 或 `InternalOrgAuthorizationControllerIT` 造 owner/admin/member 三角色）

- [ ] **Step 2: 写失败测试** `BrandProfileControllerIT`（规格测试清单 1-8 逐条）：

```java
// 权限矩阵：owner/admin PUT 成功；member GET 200（version 回显）+ PUT 403「权限不足」；未登录 401
// 跨组织：他 org admin 对本 org GET/PUT → 403「无权访问该组织」
// 字段校验：brand_name 101 字 / description 2001 字 / industry "not_an_industry" → 400 中文 message
//          全 null PUT 合法（清空）且 version+1
// 乐观锁：PUT 成功 version 0→1；用旧 expectedVersion 再 PUT → 409「品牌资料已变更，请刷新后重试」且数据未变
//          无行时 expectedVersion=1 → 409
// Logo 归属：PUT 带存在但 mock 返回 404 的 mediaId → 400「品牌 Logo 媒体不可用或类型不符」
//           mock 503 → 503；GET 资料时 mock 失败 → logoUrl=null 但其余字段正常（fail-soft）
// round-trip：PUT 全字段 → GET 回显 + logoUrl（mock url）+ version 递增
// 无行 GET：version=0 全空
// 与门店不串：同一 org 先 POST store profile（照 StoreControllerIT 造数）→ PUT brand → GET store profile 原样
// 票据端点：member → 403；admin → mock client 透传票据 JSON；上游 400 → 400 透传
```

- [ ] **Step 3: 跑测试确认失败**

- [ ] **Step 4: 实现**
  - `BrandLogoMediaClient`：照 `AvatarMediaClient` 的 WebClient/断言签发/超时结构；`usableLogoUrl` 把 404 映射 empty、5xx/异常映射 503 fail-closed；`logoUrlFailSoft` 包装（异常→null，仅日志）
  - `BrandProfileController`：常量 `BRAND_NAME_MAX=100`、`DESCRIPTION_MAX=2000`（照 StoreMarketingFields 风格）；PUT 校验链（长度→industry `Industry.fromDb` 非法 400「经营分类无效」→ logo 非空时 `usableLogoUrl` fail-closed→`repository.save` CAS→empty 转 409）；GET/PUT/票据三端点的权限与响应组装照 `MerchantAttachmentController`/`MerchantProfileController`；PUT 事务包 `lockOrganization(orgId).then(...)`（照 MerchantProfileController 串行化同 org 写）
  - 请求/响应 record 内嵌 controller（照 StoreController 惯例；可选数值字段用包装类型）
- [ ] **Step 5: 跑测试确认通过**：`:services:identity-service:test` 全量（含 StoreControllerIT 等回归）
- [ ] **Step 6: Commit**：`git commit -m "feat(identity): #32 品牌资料API——权限矩阵/乐观锁/Logo归属校验"`

---

### Task 4: 前端数据层（类型 + composable）

**Files:**
- Modify: `src/types/grassland/organization.ts`（`BrandProfile`/`SaveBrandProfileInput`）
- Modify: `src/composables/useGrasslandIdentity.ts`（`getBrandProfile`/`updateBrandProfile`/`uploadBrandLogo`）
- Test: `src/composables/useGrassland.test.ts`（或照 batch 测试新建 `useGrasslandIdentity.brand.test.ts`，以现有测试文件组织为准）

**Interfaces:**
- Produces（TS）：

```ts
export interface BrandProfile {
  brandName: string | null
  brandLogoMediaReferenceId: string | null
  logoUrl: string | null
  description: string | null
  industry: Industry | null
  version: number
}
export interface SaveBrandProfileInput {
  brandName?: string | null
  brandLogoMediaReferenceId?: string | null
  description?: string | null
  industry?: Industry | null
  expectedVersion: number
}
```

- `getBrandProfile(orgId)` → GET；`updateBrandProfile(orgId, input)` → PUT（409 抛 `GrasslandHttpError(409)` 供组件分支）；`uploadBrandLogo(orgId, file)` → 压缩（`compress-image`，≤1MB，照 `uploadAvatar`）→ `POST /api/organizations/{orgId}/brand-profile/logo/upload-ticket` `{contentType, sizeBytes}` → `putToPresignedUrl` → `POST /api/media/{id}/confirm` → 返回 mediaId（照 `uploadMerchantProfileLogo` 先例，注意压缩后 contentType/sizeBytes 用压缩结果）

- [ ] **Step 1: 读锚点**——`useGrasslandIdentity.ts` 现有函数风格、`useGrasslandMarketplace.ts` 的 `uploadAvatar`(:152-166) 与 `uploadMerchantProfileLogo`（旧 worktree 先例已并入参考：三步但不建附件记录）、`grassland-http.ts`、`compress-image.ts`、`organization.ts` 类型注释风格
- [ ] **Step 2: 写失败测试**——stubFetch：GET 回显、PUT payload 断言（含 expectedVersion）、上传三步断言（ticket→PUT presigned→confirm 返回 mediaId）、各步失败 → null + error
- [ ] **Step 3: 跑测试确认失败** → **Step 4: 实现** → **Step 5: 全绿 + `npm run typecheck`** → **Step 6: Commit**：`git commit -m "feat(frontend): #32 品牌资料类型与API客户端"`

---

### Task 5: 前端组件（OrganizationBrandCard + 挂载）

**Files:**
- Create: `src/components/OrganizationBrandCard.vue`
- Modify: `src/views/grassland/GrasslandWorkbench.vue`（商家区网格 MerchantKybCard 前插入）
- Test: `src/components/OrganizationBrandCard.test.ts`

**Interfaces:**
- Consumes: Task 4 全部函数；props/emit 照 MerchantKybCard 的组织上下文传递方式（读 workbench 现有卡片接线）
- 产出行为：随选中组织加载回填；brandName 输入 / industry 下拉（13 值，含「未设置」空选项）/ description textarea / Logo 上传（压缩→三步→本地 `URL.createObjectURL` 预览）与「移除 Logo」；保存 → 成功 notice「品牌资料已保存」+ 刷新 version；409 → error「品牌资料已被他人修改，请刷新后重试」+ 自动重拉最新资料；非 admin/owner → 只读展示（无编辑控件）；防串扰版本号模式照 MerchantKybCard（`organizationLoadVersion`）

- [ ] **Step 1: 读锚点**——`MerchantKybCard.vue`（表单状态/load/save/防串扰/模板结构）、`MyRecommenderProfileCard.vue:112-135,184-195`（上传+预览 UI）、workbench 商家区卡片接线（props 传组织/角色）、`MerchantKybCard.test.ts`（stubFetch 组件测试模式，文件顶部 `// @vitest-environment happy-dom`）
- [ ] **Step 2: 写失败测试**（规格测试清单 9 逐条）：回填断言；保存 payload；上传三步后 mediaId 随保存提交；409 提示+重拉；member 只读（无 input/保存钮）；组件独立 mount 不依赖 MerchantKybCard
- [ ] **Step 3: 跑测试确认失败** → **Step 4: 实现** → **Step 5: 全绿 + typecheck** → **Step 6: Commit**：`git commit -m "feat(frontend): #32 组织管理区品牌资料编辑面板"`

---

### Task 6: 文档回写与全量门禁

**Files:**
- Modify: `CLAUDE.md`（API routes 表：identity 3 个用户端点 + intelligence 2 个服务端点，照表内既有行格式）
- Modify: `docs/草场开发进度与续接指南.md`（#32 行标完成 + 第一批第 4 项划掉 + 测试证据 + 头部日期）
- Modify: `docs/superpowers/specs/2026-08-18-issue-32-org-brand-profile-design.md`（状态行「已实现（2026-08-18）」）

- [ ] **Step 1: 全量门禁**（worktree 根）：

```bash
cd platform-java && JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:identity-service:test :services:intelligence-service:test
cd .. && npm test && npm run typecheck && npm run build && git diff --check
```

- [ ] **Step 2: 回写文档**（不写 SHA）→ **Step 3: Commit**：`git commit -m "docs: #32 状态回写——商家主体品牌资料落地"`
