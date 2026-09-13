-- 任务书 #101 C101-05（§7.2）：视觉计划、不可变 revision、quote、应用幂等四表。
-- 计划只插入与状态推进；确认元数据（confirmed_*）随确认写入；revision 快照一经落库不可改写。
CREATE TABLE IF NOT EXISTS creation_visual_plan (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    draft_id uuid NOT NULL,
    request_id text NOT NULL,
    request_hash char(64) NOT NULL,
    source_document_id uuid NOT NULL,
    source_content_hash char(64) NOT NULL,
    base_draft_version integer NOT NULL,
    base_content_hash char(64) NOT NULL,
    recipe_id text NOT NULL,
    recipe_version text NOT NULL,
    upstream_commit text NOT NULL,
    input_snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    prompt_ciphertext text,
    prompt_hash char(64),
    status text NOT NULL DEFAULT 'preparing',
    current_revision integer NOT NULL DEFAULT 0,
    confirmed_revision integer,
    confirmed_draft_version integer,
    confirmed_content_hash char(64),
    confirmed_at timestamptz,
    confirmed_actor text,
    run_id uuid,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT creation_visual_plan_status_check CHECK (
        status IN ('preparing', 'ready', 'failed', 'unknown')),
    CONSTRAINT creation_visual_plan_revision_check CHECK (current_revision >= 0),
    CONSTRAINT creation_visual_plan_confirmed_check CHECK (
        confirmed_revision IS NULL OR (confirmed_revision >= 1 AND confirmed_revision <= current_revision
            AND confirmed_draft_version IS NOT NULL AND confirmed_content_hash IS NOT NULL
            AND confirmed_at IS NOT NULL AND confirmed_actor IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS creation_visual_plan_owner_request_uk
    ON creation_visual_plan (owner_account_id, request_id);
CREATE INDEX IF NOT EXISTS creation_visual_plan_owner_draft_recent_idx
    ON creation_visual_plan (owner_account_id, draft_id, created_at DESC, id DESC);

-- 不可变 revision 快照：PATCH 只追加新行并推进指针，旧行禁止 UPDATE（§6.5 计划编辑／§7.2）；
-- DELETE 不在此拦截——行随草稿保留期／清理策略整体处置（§7.4），非用户态改写路径。
CREATE TABLE IF NOT EXISTS creation_visual_plan_revision (
    plan_id uuid NOT NULL,
    revision integer NOT NULL,
    document_json jsonb NOT NULL,
    document_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT creation_visual_plan_revision_pk PRIMARY KEY (plan_id, revision),
    CONSTRAINT creation_visual_plan_revision_positive_check CHECK (revision >= 1)
);

CREATE OR REPLACE FUNCTION reject_visual_plan_revision_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'visual plan revisions are immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_visual_plan_revision_immutable ON creation_visual_plan_revision;
CREATE TRIGGER trg_visual_plan_revision_immutable
    BEFORE UPDATE ON creation_visual_plan_revision
    FOR EACH ROW EXECUTE FUNCTION reject_visual_plan_revision_mutation();

-- 估算（quote）：只读路由与价目快照，不冻结资金、不落 AI run；120s 内对新任务有效。
CREATE TABLE IF NOT EXISTS creation_visual_quote (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    plan_id uuid NOT NULL,
    plan_revision integer NOT NULL,
    request_id text NOT NULL,
    request_hash char(64) NOT NULL,
    quote_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS creation_visual_quote_owner_request_uk
    ON creation_visual_quote (owner_account_id, request_id);
CREATE INDEX IF NOT EXISTS creation_visual_quote_plan_revision_idx
    ON creation_visual_quote (plan_id, plan_revision);
CREATE INDEX IF NOT EXISTS creation_visual_quote_expires_idx ON creation_visual_quote (expires_at);

-- 草稿写侧应用幂等（§7.2）：text-proposal adopt 等后续卡按 kind 复用；
-- 与草稿历史／新草稿写入同事务，UNIQUE(owner, kind, request_id) 承接同键重放。
CREATE TABLE IF NOT EXISTS creation_studio_apply (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    request_id text NOT NULL,
    request_hash char(64) NOT NULL,
    kind text NOT NULL,
    resource_id uuid NOT NULL,
    applied_draft_version integer NOT NULL,
    result_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS creation_studio_apply_owner_kind_request_uk
    ON creation_studio_apply (owner_account_id, kind, request_id);
