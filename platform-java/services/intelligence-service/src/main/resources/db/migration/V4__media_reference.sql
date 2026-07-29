-- 草场 Slice 8 第二步：media_reference 领域表。
-- 把对象从「裸 S3 key」升级为「有 owner/org/domain、MIME/size/checksum、TTL/删除审计的 media 资产」。
-- 对象本体存 S3/MinIO，本表只存元数据、归属与生命周期（HLD 6.2「对象本体存入 S3/MinIO，领域服务只保存元数据、权限和引用」）。
-- 关键：object_key 不是外部授权凭据——bucket 非公开，读必须经鉴权后的 presigned URL（见 MediaController）。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v4）。与 identity/marketplace/finance/trust 共用 neon public schema，表名隔离。
CREATE TABLE media_reference (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_account_id text NOT NULL,            -- 逻辑引用 app_users，跨服务无 FK（database-per-service）
    organization_id  text,                     -- 可空；推荐官无 org
    purpose          varchar(64) NOT NULL,     -- article_generated / engagement_attachment / video_asset / user_upload
    domain_type      varchar(64),              -- 关联领域类型（如 task / application），可空
    domain_id        text,                     -- 关联领域 id，可空
    object_key       text NOT NULL UNIQUE,     -- 最终 S3/local key（从不暴露 PUT 凭据）
    upload_key       text UNIQUE,              -- 临时直传 key；服务端生成资产为 null
    mime_type        text NOT NULL,
    size_bytes       bigint NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    checksum         text,                     -- sha256 hex
    source           varchar(32) NOT NULL DEFAULT 'upload'
                     CHECK (source IN ('upload', 'generated', 'local')),
    status           varchar(16) NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending', 'finalizing', 'active', 'deleting', 'deleted')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz,              -- 可空 TTL（生成图 30min；上传资产可空=永久）
    deleted_at       timestamptz,              -- 删除审计（软删）
    quota_released   boolean NOT NULL DEFAULT false  -- 配额是否已释放（exactly-once 释放，防重试双扣）
);

CREATE INDEX idx_media_reference_owner   ON media_reference(owner_account_id);
CREATE INDEX idx_media_reference_domain  ON media_reference(domain_type, domain_id) WHERE domain_type IS NOT NULL;
CREATE INDEX idx_media_reference_expires ON media_reference(expires_at) WHERE expires_at IS NOT NULL AND deleted_at IS NULL;
-- GC 扫描 stale pending/finalizing/deleting（按 updated_at 宽限）走此部分索引，避免全表扫。
CREATE INDEX idx_media_reference_stale   ON media_reference(status, updated_at)
    WHERE deleted_at IS NULL AND status IN ('pending', 'finalizing', 'deleting');

-- owner 级媒体配额计数：原子预留/释放的 source of truth。
-- insertIfQuotaAllowed 用 ON CONFLICT DO UPDATE 行锁串行化同 owner 的并发预留（MVCC 行锁，
-- 比 advisory xact lock 在 R2DBC autocommit 下更可靠）；media_reference.quota_released 保证释放幂等。
CREATE TABLE media_owner_quota (
    owner_account_id text PRIMARY KEY,         -- 逻辑引用 app_users，跨服务无 FK
    object_count     bigint NOT NULL DEFAULT 0 CHECK (object_count >= 0),
    total_bytes      bigint NOT NULL DEFAULT 0 CHECK (total_bytes >= 0)
);
