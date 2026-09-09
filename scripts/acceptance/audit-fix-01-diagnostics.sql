-- 审查修复 01（§5 迁移与历史数据）：资金闭环只读诊断。
-- 全部为 SELECT（零写入）；跨域 JOIN 依赖本项目「五服务共库逻辑隔离」的部署事实
-- （marketplace 的 consumer_order 与 finance 的 consumer_payment* 同库同 schema）。
-- 用法：psql "$DATABASE_URL" -f scripts/acceptance/audit-fix-01-diagnostics.sql
-- 产物（隔离库演练输出）：test-artifacts/audit-fix-01/diagnostics-output.txt

\echo '===== D1 取消但财务已支付（R01 历史形态：cancelled + consumer_payment 成功 + 未全额退）====='
SELECT o.id::text AS order_id, o.status, o.price_cents, o.refunded_amount_cents,
       p.amount_cents AS finance_paid, p.refunded_amount_cents AS finance_refunded,
       p.status AS payment_status
  FROM consumer_order o
  JOIN consumer_payment p ON p.order_ref = o.id::text
 WHERE o.status = 'cancelled'
   AND p.refunded_amount_cents < p.amount_cents
 ORDER BY o.created_at;

\echo '===== D1b D1 的补偿修复预案（dry-run：拟动作=全额补偿退款，幂等键 commerce-cancel-compensation:<orderId>）====='
SELECT o.id::text AS order_id,
       p.amount_cents - p.refunded_amount_cents AS proposed_refund_cents,
       'commerce-cancel-compensation:' || o.id::text AS proposed_operation_id,
       '证据: cancelled 订单 ' || o.status || ' + finance 支付 ' || p.status
         || ' 已退 ' || p.refunded_amount_cents || '/' || p.amount_cents AS evidence
  FROM consumer_order o
  JOIN consumer_payment p ON p.order_ref = o.id::text
 WHERE o.status = 'cancelled'
   AND p.refunded_amount_cents < p.amount_cents
   AND NOT EXISTS (SELECT 1 FROM consumer_payment_refund r
                   WHERE r.order_ref = p.order_ref
                     AND r.operation_id = 'commerce-cancel-compensation:' || o.id::text)
 ORDER BY o.created_at;

\echo '===== D2 分账事实缺失（R02 历史形态：finance split completed 而 marketplace split_completed_at 为空）====='
SELECT o.id::text AS order_id, o.status, s.status AS finance_split_status,
       s.completed_at AS finance_split_completed_at,
       s.recommender_amount_cents, s.merchant_amount_cents, s.platform_fee_cents
  FROM consumer_order o
  JOIN consumer_payment_split s ON s.order_ref = o.id::text
 WHERE s.status = 'completed'
   AND o.split_completed_at IS NULL
 ORDER BY o.updated_at;

\echo '===== D3 部分退款后剩余本金无处置路径（R03 历史形态）====='
-- 已核销 + 部分退款 + 未分账（修复后由净额分账接管；命中=修复未覆盖或历史积压）
SELECT o.id::text AS order_id, o.status, o.redeemed_at, o.refunded_amount_cents,
       o.price_cents - o.refunded_amount_cents AS remaining_cents,
       o.split_completed_at, o.split_eligible_at
  FROM consumer_order o
 WHERE o.status = 'partially_refunded'
   AND o.redeemed_at IS NOT NULL
   AND o.split_completed_at IS NULL
   AND o.split_eligible_at <= now()
 ORDER BY o.split_eligible_at;

\echo '===== D3b 未核销部分退款已过核销期但未收口（应被到期退款接管；命中=积压）====='
SELECT o.id::text AS order_id, o.status, o.redeem_deadline,
       o.price_cents - o.refunded_amount_cents AS remaining_cents
  FROM consumer_order o
 WHERE o.status = 'partially_refunded'
   AND o.redeemed_at IS NULL
   AND o.redeem_deadline <= now()
 ORDER BY o.redeem_deadline;

\echo '===== D4 长期未执行的资金操作（commerce_fund_operation 非终态超 1 小时）====='
SELECT operation_id, order_id::text AS order_id, operation_type, status, attempts,
       next_attempt_at, last_error, created_at
  FROM commerce_fund_operation
 WHERE status IN ('in_flight', 'failed', 'needs_review')
   AND updated_at < now() - interval '1 hour'
 ORDER BY next_attempt_at;

\echo '===== D5 splitting 占位悬挂（分账占位超过 10 分钟未收尾=执行者崩溃且恢复未接管）====='
SELECT o.id::text AS order_id, o.status, o.split_operation_id, o.updated_at
  FROM consumer_order o
 WHERE o.status = 'splitting'
   AND o.updated_at < now() - interval '10 minutes';
