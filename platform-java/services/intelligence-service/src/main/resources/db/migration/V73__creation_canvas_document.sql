-- 任务书 #100（C100-09 / API-08/09 / §7.1 V73）：独立画布文档表。
--
-- 语义：一分镜工作区草稿至多一份画布文档（draft_id 唯一）；document 为整份 CanvasDocumentBody
-- （jsonb object），revision 从 1 起单调递增（API 侧 expectedRevision=0 表示首建，落库即 1）；
-- (account_id, updated_at DESC, id DESC) 支撑按账号的最近画布读取。
--
-- 硬约束（沿用 V71/V72 口径）：DDL 全幂等（IF NOT EXISTS / DO 块重建），重放进空库、
-- 存量库与 PlatformModelConcurrencyMigrationTest 合成 schema 均安全——不建 FK
-- （草稿/分镜可用性由服务层校验，媒体裸 UUID 惯例一致）。

CREATE TABLE IF NOT EXISTS creation_canvas_document (
    id uuid PRIMARY KEY,
    draft_id uuid NOT NULL,
    account_id text NOT NULL,
    schema_version int NOT NULL DEFAULT 1,
    revision bigint NOT NULL DEFAULT 1,
    document jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT creation_canvas_document_revision_check CHECK (revision > 0),
    CONSTRAINT creation_canvas_document_document_check CHECK (jsonb_typeof(document) = 'object')
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'creation_canvas_document_draft_key'
                       AND tablename = 'creation_canvas_document') THEN
        CREATE UNIQUE INDEX creation_canvas_document_draft_key
            ON creation_canvas_document (draft_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'creation_canvas_document_account_recent'
                       AND tablename = 'creation_canvas_document') THEN
        CREATE INDEX creation_canvas_document_account_recent
            ON creation_canvas_document (account_id, updated_at DESC, id DESC);
    END IF;
END $$;

COMMENT ON TABLE creation_canvas_document IS
    '#100 C100-09 独立画布文档：draft 唯一，revision CAS，document ≤256KiB 由服务层校验';
