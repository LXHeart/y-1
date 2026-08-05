-- 草场 trust-service V7：争议证据域 + 不可变审计（GL-P2-TRUST-001 Stage 1 / T1+T2）。
-- 独立 Flyway 历史表 trust_flyway_schema（续 V6 deferred_recommender_objection）。
--
-- 背景：V3 在 dispute_case 留了 evidence_ref「脱敏证据句柄（D-10 占位）」列，但从未被写入——
-- 历史"证据"只有 OpenDisputeRequest.reason 一段明文。本轮建独立证据表 + 审计表，让 evidence_ref 活过来。

-- ① 争议证据项。每条证据是争议的一个附件（文本 / 截图 media_reference 句柄 / 外链）。
--    raw 存 content_ref（文本原文 / media id / url）；审判官只看 redacted_ref（脱敏后）。
CREATE TABLE dispute_evidence (
    id uuid PRIMARY KEY,
    dispute_id uuid NOT NULL,
    submitted_by_account_id uuid NOT NULL,
    submitted_by_role varchar(32) NOT NULL,   -- merchant / recommender / customer_service
    kind varchar(32) NOT NULL,                 -- text / screenshot / link
    content_ref text NOT NULL,                 -- 文本原文 / intelligence media_reference id / 外链
    redacted_ref text,                         -- 脱敏后内容（审判官可见）；null=提交时未脱敏，读时再脱敏
    caption text,
    created_at timestamptz NOT NULL DEFAULT now(),
    retention_until timestamptz NOT NULL       -- D-10 证据保留期（应用层按 trust.evidence.retention-days 派生，provisional）
);
CREATE INDEX idx_evidence_dispute ON dispute_evidence(dispute_id, created_at);

-- ② 争议生命周期不可变审计（T2，镜像 marketplace ops_case_audit：只追加，无 update/delete）。
CREATE TABLE dispute_audit (
    id bigserial PRIMARY KEY,
    dispute_id uuid NOT NULL,
    action varchar(64) NOT NULL,               -- opened / evidence_submitted / decided / adjudicated / finalized / ...
    actor_account_id uuid,                     -- null + actor_role=system 表示系统动作
    actor_role varchar(32),
    note text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_dispute_audit ON dispute_audit(dispute_id, id);

-- ③ 证据查看审计（T2 / D-10：谁、何时查看了哪条证据——证据是受限展示对象）。
CREATE TABLE dispute_evidence_access_audit (
    id bigserial PRIMARY KEY,
    evidence_id uuid NOT NULL,
    dispute_id uuid NOT NULL,
    viewer_account_id uuid NOT NULL,
    viewer_role varchar(32) NOT NULL,          -- judge / customer_service / admin
    purpose varchar(32) NOT NULL DEFAULT 'adjudication',  -- adjudication / review
    viewed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_evidence_access_evidence ON dispute_evidence_access_audit(evidence_id);
CREATE INDEX idx_evidence_access_viewer ON dispute_evidence_access_audit(viewer_account_id, viewed_at);
