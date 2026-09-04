-- 任务书 #74 方案 α（争议列表/详情页）：被诉方账号落库。
-- 背景：DisputeCase 此前只有 openedByAccountId + organizationId——商家开争议时被诉推荐官无 org 可匹配，
-- 「我参与的争议」列表与详情受众判定都覆盖不到被诉推荐官。开争议时 marketplace 授权响应已带
-- recommenderAccountId，merchant 开争议 = 被诉方即该推荐官，直接固化（避免事后跨服务回查）。
-- recommender 开争议（被诉方是商家组织，org 维度可查）与 merchant_rejection（D-03 零改动红线）保持 NULL。
-- 存量行为 NULL：历史案件中被诉推荐官仍经通知深链进入详情（受众判定见 DisputeAudience）。
ALTER TABLE dispute_case
    ADD COLUMN IF NOT EXISTS respondent_account_id uuid;

CREATE INDEX IF NOT EXISTS idx_dispute_respondent ON dispute_case(respondent_account_id) WHERE respondent_account_id IS NOT NULL;
