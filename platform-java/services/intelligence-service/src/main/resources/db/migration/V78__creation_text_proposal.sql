-- 任务书 #101 C101-04（§7.2）：文本建议（改编/标题摘要建议）表。
-- 建议只插入与状态推进，不改写草稿；应用结果与草稿写同事务（apply 列由 TextProposalService 维护）。
CREATE TABLE IF NOT EXISTS creation_text_proposal (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    draft_id uuid NOT NULL,
    request_id text NOT NULL,
    request_hash char(64) NOT NULL,
    action text NOT NULL,
    status text NOT NULL DEFAULT 'preparing',
    base_draft_version integer NOT NULL,
    base_content_hash char(64) NOT NULL,
    source_document_id uuid,
    selected_blocks_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    input_snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    prompt_ciphertext text,
    prompt_hash char(64),
    result_json jsonb,
    run_id uuid,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    applied_draft_version integer,
    apply_request_id text,
    apply_result_json jsonb,
    CONSTRAINT creation_text_proposal_kind_check CHECK (action IN ('adapt-body', 'suggest-metadata')),
    CONSTRAINT creation_text_proposal_status_check CHECK (
        status IN ('preparing', 'ready', 'failed', 'unknown', 'applied'))
);

CREATE UNIQUE INDEX IF NOT EXISTS creation_text_proposal_owner_request_uk
    ON creation_text_proposal (owner_account_id, request_id);
CREATE UNIQUE INDEX IF NOT EXISTS creation_text_proposal_apply_request_uk
    ON creation_text_proposal (apply_request_id) WHERE apply_request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS creation_text_proposal_owner_draft_recent_idx
    ON creation_text_proposal (owner_account_id, draft_id, created_at DESC, id DESC);
