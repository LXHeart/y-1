-- GL-P0-CRED-001: 积分操作幂等键
--
-- 内部 credits bridge（草场 intelligence → legacy）与前端重试都可能重复投递同一次扣减。
-- operation_id 由调用方生成并在重试时复用，唯一约束把「重复投递」变成可识别的冲突，
-- 服务层据此返回既有流水而不是二次扣款。
--
-- 历史流水没有 operation_id，故用部分唯一索引（NULL 不参与唯一性），无需回填。

ALTER TABLE credit_transactions
  ADD COLUMN operation_id text;

CREATE UNIQUE INDEX idx_credit_transactions_operation
  ON credit_transactions(operation_id)
  WHERE operation_id IS NOT NULL;
