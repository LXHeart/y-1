-- 任务书 #103 C103-18：历史一致性只读诊断 SQL（人工/工具对真实库执行；默认只读，无任何写语句）。
-- 每段输出：case_id 定位键 + 分类 + 证据引用（不倾倒敏感正文）。
-- 分类对齐 §7.5：exit_funds_inconsistent / legacy_post_split_refund / legacy_closure_residue /
-- missed_notification / missing_settlement_fact / legacy_multi_manager / needs_review。

-- 1) 协商退出已确认但资金腿未收敛（BR-02：一个经济键只对应一份结果）
-- SELECT e.id::text AS case_id, 'exit_funds_inconsistent' AS category,
--        e.application_id::text AS economic_key, e.state, e.created_at, e.updated_at
-- FROM engagement_exit_operation e
-- WHERE e.state NOT IN ('completed', 'not_required')
--   AND e.created_at < now() - interval '7 days';

-- 2) 已结后历史退款（§7.5-2：保留历史事实，只列诊断并阻止再次自动分账）
-- SELECT ('refund:' || r.operation_id) AS case_id, 'legacy_post_split_refund' AS category,
--        r.order_id::text AS economic_key, r.amount_cents, r.occurred_at
-- FROM consumer_order_refund r
-- JOIN consumer_order o ON o.id = r.order_id
-- WHERE o.split_completed_at IS NOT NULL AND o.split_completed_at < r.occurred_at;

-- 3) 旧 completed 注销残留（§7.5-4：不自动回溯删除，进 needs_review）
-- SELECT c.account_id::text AS case_id, 'legacy_closure_residue' AS category,
--        c.account_id::text AS economic_key, c.state, c.created_at
-- FROM account_closure c
-- WHERE c.state = 'completed';

-- 4) 漏通知（事件存在但 inbox/通知缺失——补发需显式事件 ID 计划）
-- SELECT ('evt:' || o.event_id) AS case_id, 'missed_notification' AS category,
--        o.aggregate_id AS economic_key, o.event_type, o.created_at
-- FROM marketplace_outbox o
-- LEFT JOIN LATERAL (
--   SELECT 1 FROM notification n WHERE n.source_event_id = o.event_id LIMIT 1
-- ) n ON TRUE
-- WHERE n IS DISTINCT FROM NULL IS NULL AND FALSE  -- 真实环境按 identity 库联查；此处示意需跨库核对
--   AND FALSE;

-- 5) 缺分账事实投影（C103-15 历史存量；C18 按原 operation 键补投影）
-- SELECT ('split:' || o.id::text) AS case_id, 'missing_settlement_fact' AS category,
--        o.id::text AS economic_key, o.split_operation_id, o.split_completed_at
-- FROM consumer_order o
-- WHERE o.split_completed_at IS NOT NULL
--   AND NOT EXISTS (SELECT 1 FROM commerce_settlement_fact f WHERE f.order_id = o.id);

-- 6) 历史多店长/重复挂靠（§7.5-3：不在迁移自动修，仅诊断；新增并发由父锁保证）
-- SELECT ('store:' || sm.store_id::text) AS case_id, 'legacy_multi_manager' AS category,
--        sm.store_id::text AS economic_key, COUNT(*) AS manager_rows
-- FROM store_membership sm
-- WHERE sm.role = 'manager'
-- GROUP BY sm.store_id
-- HAVING COUNT(*) > 1;

-- 说明：本文件在真实环境按段手工执行（各服务自有库），结果粘贴/导出后由
-- scripts/acceptance/task-103-diagnose.ts --cases <导出JSON> 生成可审阅计划。
-- 默认不含任何 UPDATE/DELETE/INSERT；任何修复动作只能经 task-103-replay.ts 显式 apply。
