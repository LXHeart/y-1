-- 任务书 #101 C101-02（§7.2）：不可变来源文档。正文只插入不更新；重新导入创建新 ID。
-- 幂等 DDL（Flyway 重放测试要求 IF NOT EXISTS）。
CREATE TABLE IF NOT EXISTS creation_source_document (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    draft_id uuid NOT NULL,
    request_id text NOT NULL,
    request_hash char(64) NOT NULL,
    kind text NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,
    title text NOT NULL DEFAULT '',
    raw_text text NOT NULL,
    normalized_markdown text NOT NULL,
    content_hash char(64) NOT NULL,
    blocks_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    source_refs_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    warnings_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT creation_source_document_kind_check
        CHECK (kind IN ('plain-text', 'markdown', 'draft-content')),
    CONSTRAINT creation_source_document_schema_check CHECK (schema_version = 1),
    CONSTRAINT creation_source_document_request_id_len_check CHECK (char_length(request_id) <= 128),
    CONSTRAINT creation_source_document_raw_bytes_check CHECK (octet_length(raw_text) <= 131072)
);

-- 同 owner 同 requestId 幂等：首次回包丢失用完全相同 payload 重放取回原 ID（§6.1）。
CREATE UNIQUE INDEX IF NOT EXISTS creation_source_document_owner_request_uk
    ON creation_source_document (owner_account_id, request_id);

-- 草稿维度的最近来源列表（keyset 分页）。
CREATE INDEX IF NOT EXISTS creation_source_document_owner_draft_recent_idx
    ON creation_source_document (owner_account_id, draft_id, created_at DESC, id DESC);
