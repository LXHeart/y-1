-- D-02：阶梯佣金的 Sandbox 指标事实来源——商家确认时申报的指标达成值。
-- 只由手动确认的 guarded UPDATE 写入（与 confirmed_at 同一行原子落库）；窗口到期自动确认不写（保持 NULL）。
-- 结算读到「有冻结阶梯 + NULL 申报值」必须 hold 转运营，绝不按预留上限全额捕获。
ALTER TABLE task_application
    ADD COLUMN confirmed_metric_value bigint
        CHECK (confirmed_metric_value IS NULL OR confirmed_metric_value >= 0);
