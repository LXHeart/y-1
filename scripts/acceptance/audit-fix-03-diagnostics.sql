-- Audit-fix 03 historical diagnostics. Read-only and safe to rerun.
-- Run in the marketplace schema; every result is a candidate for review, not an
-- instruction to mutate production data.

-- 1) A no-fault withdrawal must not retain an occupied slot or an open exit request.
SELECT a.id::text AS application_id,
       a.task_id::text AS task_id,
       a.recommender_account_id::text AS recommender_account_id,
       a.exit_kind,
       c.occupied_slots,
       x.pending_count
  FROM task_application a
  LEFT JOIN task_acceptance_counter c ON c.task_id = a.task_id
  LEFT JOIN LATERAL (
      SELECT COUNT(*)::int AS pending_count
        FROM exit_request e
       WHERE e.application_id = a.id AND e.status = 'pending'
  ) x ON TRUE
 WHERE a.status = 'withdrawn'
   AND a.exit_kind = 'no_fault'
   AND (COALESCE(c.occupied_slots, 0) > 0 OR x.pending_count > 0)
 ORDER BY a.task_id, a.id;

-- 2) A confirmed refund must not leave its after-sales dispute open.
SELECT d.id::text AS dispute_id,
       d.order_id::text AS order_id,
       d.refund_operation_id,
       d.status AS dispute_status,
       o.status AS order_status,
       o.refunded_amount_cents,
       o.refunded_at
  FROM consumer_order_after_sales_dispute d
  JOIN consumer_order o ON o.id = d.order_id
 WHERE d.status = 'open'
   AND o.status IN ('refunded', 'partially_refunded')
   AND o.refunded_amount_cents > 0
 ORDER BY d.created_at, d.id;

-- 3) Timeout must remain accountable; only merchant_cancel (or legacy NULL
-- exit_kind) belongs to the merchant-cancel compatibility bucket.
SELECT a.id::text AS application_id,
       a.task_id::text AS task_id,
       a.recommender_account_id::text AS recommender_account_id,
       a.status,
       a.exit_kind,
       a.exited_at
  FROM task_application a
 WHERE a.status = 'refunded'
   AND a.exit_kind = 'timeout'
 ORDER BY a.exited_at NULLS FIRST, a.id;
