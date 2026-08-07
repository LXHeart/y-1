-- 草场 PRD §4.8 内容素材库（Slice 14 Stage 1）：商家 / 个人 / 公共与 AI 三类素材库的业务层。
--
-- 背景：media_reference（V4）是「单次上传资产的物理元数据 + 配额/GC 生命周期」，没有素材库需要的
-- 分类 / 标签 / 有效期 / 来源 / 授权范围 / 历史快照任何概念。照搬 media_kyb_retention（V9）的既定分层
-- 范式——media_reference 作物理资产层（复用三步上传 / 配额 / GC，零改动），本迁移在它之上叠三张
-- 业务关系表。这是 greenfield 新建，非迁移。
--
-- 三类素材库的归属模型（复用 media_reference 的 owner/org 双轨，V4 L8-9）：
--   - personal：owner_account_id = 上传者，organization_id = null（推荐官/用户）
--   - merchant：owner_account_id = 上传的商家成员，organization_id = 商家 org（非空）
--   - public  ：owner_account_id = 上传的运营账号，organization_id = null，强制 source/license_scope
--
-- 数据库纪律（与 V9 / marketplace V9 engagement_submission_attachment 一致）：media_reference_id 是
-- **无 FK 的 uuid**——media_reference 属于 intelligence 库同库，但刻意不建 FK，保持与既有 retention/
-- attachment 表同口径（media 走自己的软删生命周期，FK 级联会破坏 GC 状态机）。挂接时把 mime_type/
-- size_bytes 快照一份，media 日后被删仍能展示残留元信息。被 version 快照引用的 media 防物理删由
-- MediaReferenceRepository.claimDelete 的 NOT EXISTS 守卫兜（与 kyb_retention 同机制，Stage 2 接线）。
--
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v18）。与 identity/marketplace/finance/trust
-- 共用 neon public schema，表名隔离。

-- ① 素材条目主表（可变当前行）。镜像 platform_model_config（V7）的 version + 部分唯一索引范式。
CREATE TABLE content_asset (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    media_reference_id uuid NOT NULL,            -- 跨服务引用 media_reference（同库无 FK，同 V9 口径）
    library_type      varchar(16) NOT NULL       -- personal / merchant / public
                      CHECK (library_type IN ('personal', 'merchant', 'public')),
    category          varchar(32) NOT NULL       -- store 门店 / product 产品 / campaign 活动 / scene 场景 / brand 品牌 / other
                      CHECK (category IN ('store', 'product', 'campaign', 'scene', 'brand', 'copy', 'other')),
    owner_account_id  text NOT NULL,             -- 逻辑引用 app_users，跨服务无 FK（上传者）
    organization_id   text,                      -- 可空；商家库非空，个人/公共库为 null
    title             text NOT NULL,             -- 素材标题（用户可读名）
    tags              jsonb NOT NULL DEFAULT '[]'::jsonb,   -- 标签数组 string[]，筛选用
    mime_type         text,                      -- 挂接时快照（media 删除后仍可展示类型）
    size_bytes        bigint,                    -- 挂接时快照
    valid_until       timestamptz,               -- 有效期；可空=永久。公共库必填（PRD §4.8）
    status            varchar(20) NOT NULL DEFAULT 'active'
                      CHECK (status IN ('draft', 'pending_review', 'active', 'rejected', 'expired')),
    version           integer NOT NULL DEFAULT 1,  -- 乐观锁 + 快照版本号（每次编辑 +1）
    source            text,                      -- 来源（公共库必填：PRD §4.8「公共素材必须包含来源」）
    license_scope     text,                      -- 授权范围（公共库必填：PRD §4.8「授权范围」）
    review_note       text,                      -- 审核备注（公共库 pending_review→rejected 时填）
    reviewed_by       text,                      -- 审核人 account id
    reviewed_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz                -- 软删审计（与 media_reference 同口径）
);

-- 个人库：按 owner 列活跃素材（默认列表查询热路径）。
CREATE INDEX idx_content_asset_personal
    ON content_asset(owner_account_id, created_at DESC)
    WHERE library_type = 'personal' AND deleted_at IS NULL AND status = 'active';
-- 商家库：按 org 列活跃素材。
CREATE INDEX idx_content_asset_merchant
    ON content_asset(organization_id, category, created_at DESC)
    WHERE library_type = 'merchant' AND deleted_at IS NULL AND status = 'active';
-- 公共库：全员只读列活跃且未过期素材（resolveOptional 热路径，含未登录）。
CREATE INDEX idx_content_asset_public
    ON content_asset(category, created_at DESC)
    WHERE library_type = 'public' AND deleted_at IS NULL AND status = 'active';
-- 公共库审核队列：pending_review 列表。
CREATE INDEX idx_content_asset_pending_review
    ON content_asset(created_at DESC)
    WHERE library_type = 'public' AND status = 'pending_review';

-- ② 不可变历史快照表（镜像 task_version V11 / platform_model_config_history V7）。
-- PRD §4.8「素材更新后不覆盖已进入任务的历史快照」：每次编辑落新 version 整行镜像，历史不可改。
CREATE TABLE content_asset_version (
    asset_id          uuid NOT NULL,
    version           integer NOT NULL,
    library_type      varchar(16) NOT NULL,
    category          varchar(32) NOT NULL,
    owner_account_id  text NOT NULL,
    organization_id   text,
    title             text NOT NULL,
    tags              jsonb NOT NULL DEFAULT '[]'::jsonb,
    mime_type         text,
    size_bytes        bigint,
    valid_until       timestamptz,
    source            text,
    license_scope     text,
    snapshotted_at    timestamptz NOT NULL DEFAULT now(),
    snapshotted_by    text NOT NULL,             -- 执行编辑的 account id
    PRIMARY KEY (asset_id, version)
);

-- ③ 授权关系表（镜像 media_kyb_retention V9/V10）。
-- PRD §4.8 商家素材库「商家可以指定哪些素材允许推荐官使用」。grant_type 区分授权语义：
--   - recommender_share：商家授权给推荐官（跨账号读的依据）
--   - org_internal：org 内部成员共享（商家库默认）
--   - public：公共库自动放行（全员可读）
-- 有效期双字段（lease_until 滚动 / retained_until 绝对），至少一个非空（V10 同款 CHECK）。
CREATE TABLE content_asset_grant (
    asset_id          uuid NOT NULL,
    grant_type        varchar(24) NOT NULL
                      CHECK (grant_type IN ('recommender_share', 'org_internal', 'public')),
    grantee_account_id text,                     -- recommender_share 时填被授权推荐官；其余为 null
    granted_by        text NOT NULL,             -- 授权人 account id
    granted_at        timestamptz NOT NULL DEFAULT now(),
    lease_until       timestamptz,               -- 滚动租约（续约用 GREATEST 只前进）
    retained_until    timestamptz,               -- 绝对截止日
    released_at       timestamptz,               -- 软释放（撤销授权）
    -- 续约/封存期限只能前进不能后退（V10 ck_media_kyb_retention_deadline 同款）。
    CHECK ((lease_until IS NOT NULL OR retained_until IS NOT NULL)
           OR released_at IS NOT NULL),
    -- recommender_share 必须指定被授权账号；org_internal/public 不指定具体人。
    CHECK ((grant_type = 'recommender_share') = (grantee_account_id IS NOT NULL)),
    PRIMARY KEY (asset_id, grant_type, grantee_account_id)
);

-- 推荐官查「我被授权的商家素材」热路径。
CREATE INDEX idx_content_grant_grantee
    ON content_asset_grant(grantee_account_id)
    WHERE grant_type = 'recommender_share' AND released_at IS NULL;
