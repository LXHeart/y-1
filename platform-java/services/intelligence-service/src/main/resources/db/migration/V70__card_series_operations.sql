-- AI内容中心改造-02（总方案 §8.4 第 7 项 / T21）：图卡生成操作记录。
-- owner + request_id 唯一：首次占位后才进入执行；相同 requestId + 相同请求摘要回读原结果，
-- 摘要变更返回 409。result 保存完整响应体（卡片身份/成功失败/runId 归并其中）。
-- 网络超时先 GET 查询本表，供应商受理后不可核实的运行保持待确认，不自动当作失败二次计费。
CREATE TABLE IF NOT EXISTS card_series_operation (
    id                  UUID PRIMARY KEY,
    owner_account_id    text NOT NULL,
    request_id          varchar(128) NOT NULL,
    request_digest      varchar(128) NOT NULL,
    status              varchar(32) NOT NULL,
    context_snapshot_id UUID,
    error_code          varchar(64),
    error_message       text,
    result              jsonb,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_card_series_operation_owner_request UNIQUE (owner_account_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_card_series_operation_owner_recent
    ON card_series_operation (owner_account_id, updated_at DESC);
