-- 任务书 #101 C101-21：公众号草稿同步（V83）。
-- owner+request_id 唯一（同键重放读回）；「同账号＋draftVersion＋payloadHash」对进行中/unknown/succeeded
-- 部分唯一——复用既有记录，不创建第二个外部草稿（failed/cancelled 不阻挡重建）。
-- payload_json 冻结完整载荷（标题/正文 HTML/摘要/作者/来源链接/评论选项/封面与图片顺序），列表永不返回。
-- draft_add_done=持久提交标记：置 state=submitting 后由同一次 activity 完成唯一 draft/add；
-- 崩溃/重放看到 submitting 且未 done 一律转 unknown（禁止第二次派发——R101-15）。
CREATE TABLE IF NOT EXISTS creation_wechat_draft_sync (
    id                      uuid PRIMARY KEY,
    owner_account_id        text NOT NULL,
    request_id              text NOT NULL,
    account_id              uuid NOT NULL,
    account_version         integer NOT NULL,
    draft_id                uuid NOT NULL,
    draft_version           integer NOT NULL,
    export_id               uuid NOT NULL,
    payload_hash            char(64) NOT NULL,
    payload_json            jsonb NOT NULL,
    state                   varchar(16) NOT NULL DEFAULT 'preparing',
    external_draft_media_id varchar(256),
    error_code              varchar(64),
    dispatch_state          varchar(16) NOT NULL DEFAULT 'pending',
    draft_add_done          boolean NOT NULL DEFAULT false,
    version                 integer NOT NULL DEFAULT 1,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    verified_at             timestamptz,
    CONSTRAINT uq_creation_wechat_sync_owner_request UNIQUE (owner_account_id, request_id),
    CONSTRAINT ck_creation_wechat_sync_state CHECK (state IN
        ('preparing','uploading','submitting','verifying','succeeded','failed','unknown','cancelled'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_creation_wechat_sync_active
    ON creation_wechat_draft_sync (account_id, draft_id, draft_version, payload_hash)
    WHERE state IN ('preparing','uploading','submitting','verifying','unknown','succeeded');
CREATE INDEX IF NOT EXISTS creation_wechat_sync_owner_draft_idx
    ON creation_wechat_draft_sync (owner_account_id, draft_id, created_at DESC);
CREATE INDEX IF NOT EXISTS creation_wechat_sync_adopt_idx
    ON creation_wechat_draft_sync (dispatch_state, updated_at) WHERE dispatch_state <> 'completed';

-- 上传映射缓存：连接＋凭据版本＋内容 hash＋用途 唯一（跨同步复用，两种 media_id/URL 语义不混用）。
CREATE TABLE IF NOT EXISTS creation_wechat_media_mapping (
    id                 uuid PRIMARY KEY,
    sync_id            uuid NOT NULL,
    owner_account_id   text NOT NULL,
    account_id         uuid NOT NULL,
    account_version    integer NOT NULL,
    media_ref_id       uuid NOT NULL,
    purpose            varchar(16) NOT NULL,
    ordinal            integer NOT NULL DEFAULT 0,
    content_hash       char(64) NOT NULL,
    media_id           varchar(256),
    media_url          varchar(1024),
    derived_object_key text,
    state              varchar(16) NOT NULL DEFAULT 'pending',
    attempts           integer NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_creation_wechat_media UNIQUE (account_id, account_version, content_hash, purpose),
    CONSTRAINT uq_creation_wechat_media_sync UNIQUE (sync_id, ordinal),
    CONSTRAINT ck_creation_wechat_media_purpose CHECK (purpose IN ('content','cover'))
);
