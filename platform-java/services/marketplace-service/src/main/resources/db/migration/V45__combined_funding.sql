-- 草场 marketplace V45：组合付费放开（任务书 #46 / ADR-D12 D1 后置项）。
-- bounty（商家赏金腿 funds_reservation）与 freebie_deposit（推荐官押金腿 freebie_escrow）
-- 允许同任务并存；仍互斥的是阶梯佣金 × 押金（契约层 TaskCatalogFundingRules 拦）。
-- 仅 DROP 约束（IF EXISTS 重放安全）；冻结快照/双列快照 V40 已带两值，零结构改动。
ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_funding_xor;
