-- #103 只读历史诊断。使用已迁移的隔离库/审阅授权的只读连接执行。
-- 当前部署各域共用 public schema；这是离线跨域诊断，不得嵌入运行时服务。
-- 每项最多 100 行，均为待核实候选，不直接授权修复或补发；不输出内容正文。
-- 生成重放输入时还须从权威服务核实原操作键、版本/指纹、收件人及期限。

-- 1) 未收敛的退出操作；合法成功终态为 succeeded。
SELECT e.id::text AS case_id, 'exit_funds_inconsistent' AS category,
       e.application_id::text AS economic_key, e.state, e.version, e.updated_at
FROM engagement_exit_operation e
WHERE e.state = 'needs_review'
   OR (e.state <> 'succeeded' AND e.created_at < now() - interval '7 days')
ORDER BY e.created_at, e.id
LIMIT 100;

-- 2) 已完成分账之后的历史退款，直接比较 Finance 权威事实。
SELECT r.id::text AS case_id, 'legacy_post_split_refund' AS category,
       r.order_ref AS economic_key, r.operation_id, r.amount_cents,
       r.created_at AS refunded_at, s.completed_at AS split_completed_at
FROM consumer_payment_refund r
JOIN consumer_payment_split s ON s.order_ref = r.order_ref
WHERE s.status = 'completed' AND s.completed_at < r.created_at
ORDER BY r.created_at, r.id
LIMIT 100;

-- 3) 注销已完成却缺少 Intelligence 清理完成回执的历史候选。
-- 旧账号可能没有个人创作数据，缺回执不能直接认定残留或自动再次删除。
SELECT c.id::text AS case_id, 'legacy_closure_residue' AS category,
       c.account_id::text AS economic_key, c.status, c.completed_at,
       m.state AS manifest_state
FROM account_closure_request c
LEFT JOIN personal_data_erasure_manifest m ON m.closure_request_id = c.id
WHERE c.status = 'completed'
  AND (m.id IS NULL OR m.state <> 'completed' OR m.verified_at IS NULL)
ORDER BY c.completed_at, c.id
LIMIT 100;

-- 4) 已发布履约事件但无通知的候选。过期提醒、无有效收件人可以合法不通知，
-- 必须经通知消费者的原始期限/收件人守卫复核，不能据此批量补发。
SELECT o.event_id AS case_id, 'missed_notification' AS category,
       o.aggregate_id AS economic_key, o.event_type, o.created_at
FROM marketplace_outbox o
WHERE o.published_at IS NOT NULL AND o.created_at < now() - interval '5 minutes'
  AND o.event_type IN (
    'DeliveryDeadlineExpiring',
    'DeliveryExtensionRequested',
    'DeliveryExtensionApproved',
    'DeliveryExtensionRejected',
    'DeliveryTimeoutTerminated',
    'DraftSubmitted',
    'DraftReviewExpiring',
    'EngagementExitRequested',
    'EngagementExitRejected',
    'EngagementExitCancelled',
    'EngagementExitExpired',
    'ApplicationExitedNoFault',
    'EngagementExitedNegotiated',
    'BenefitBooked',
    'BenefitFulfilled',
    'BenefitFulfillmentConfirmed',
    'BenefitDefaultClaimed',
    'BenefitDefaultDenied',
    'BenefitDefaultEstablished',
    'BenefitCancelled',
    'MilestoneConfirmed'
  )
  AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.source_event_id = o.event_id)
ORDER BY o.created_at, o.event_id
LIMIT 100;

-- 5) 已分账但缺投影，后续只可按原操作键回读 Finance 补投影。
SELECT o.id::text AS case_id, 'missing_settlement_fact' AS category,
       o.id::text AS economic_key, o.split_operation_id, o.split_completed_at
FROM consumer_order o
WHERE o.split_completed_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM commerce_settlement_fact f WHERE f.order_id = o.id)
ORDER BY o.split_completed_at, o.id
LIMIT 100;

-- 6) 历史多店长，仅列候选，不自动指定谁保留。
SELECT sm.store_id::text AS case_id, 'legacy_multi_manager' AS category,
       sm.store_id::text AS economic_key, COUNT(*) AS manager_rows
FROM store_membership sm
WHERE sm.role = 'manager'
GROUP BY sm.store_id
HAVING COUNT(*) > 1
ORDER BY sm.store_id
LIMIT 100;
