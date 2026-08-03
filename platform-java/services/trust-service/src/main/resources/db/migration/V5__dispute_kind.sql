-- D-03 slice 2：dispute_case 加 kind，区分「商家对核实通过履约的拒绝」（merchant_rejection）与普通争议。
-- merchant_rejection 争议直送客服终审（不走 7 官面板）：客观核实 vs 商家拒绝是事实冲突，由客服裁定。
-- default 'standard'：既有争议与普通用户开争议保持原语义。
ALTER TABLE dispute_case
    ADD COLUMN IF NOT EXISTS kind varchar(32) NOT NULL DEFAULT 'standard';
