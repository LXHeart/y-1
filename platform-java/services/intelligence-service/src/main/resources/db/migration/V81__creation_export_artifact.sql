-- 任务书 #101 C101-18：创作导出产物（V81）。
-- 新格式（markdown/text/wechat-html/bundle-zip）幂等落库；owner+request_id 唯一——
-- 同键重试读回既有导出，不重复装配。manifest 不存签名 URL（读取时恢复签名）。
CREATE TABLE IF NOT EXISTS creation_export (
    id                   uuid PRIMARY KEY,
    owner_account_id     text NOT NULL,
    request_id           text NOT NULL,
    draft_id             uuid NOT NULL,
    version              integer NOT NULL,
    format               varchar(32) NOT NULL,
    theme                varchar(16) NOT NULL DEFAULT 'standard',
    include_title        boolean NOT NULL DEFAULT false,
    cite_external_links  boolean NOT NULL DEFAULT false,
    payload_hash         char(64) NOT NULL,
    state                varchar(16) NOT NULL DEFAULT 'building',
    manifest_json        jsonb,
    content_hash         char(64),
    error_code           varchar(64),
    created_at           timestamptz NOT NULL DEFAULT now(),
    ready_at             timestamptz,
    failed_at            timestamptz,
    CONSTRAINT uq_creation_export_owner_request UNIQUE (owner_account_id, request_id)
);
CREATE INDEX IF NOT EXISTS creation_export_owner_draft_idx
    ON creation_export (owner_account_id, draft_id, created_at DESC);
