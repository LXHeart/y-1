-- 统一审计台按争议和时间窗口倒序查询证据访问流水。
CREATE INDEX idx_evidence_access_dispute_viewed
    ON dispute_evidence_access_audit(dispute_id, viewed_at DESC, id DESC);
CREATE INDEX idx_evidence_access_viewed
    ON dispute_evidence_access_audit(viewed_at DESC, id DESC);
