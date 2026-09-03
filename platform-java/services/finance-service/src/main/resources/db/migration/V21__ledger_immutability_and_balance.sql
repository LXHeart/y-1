-- V21__ledger_immutability_and_balance.sql
-- 任务书 #67:账本 DB 级兜底(对齐 credits 表 V10 trg_credits_quota_transaction_immutable 先例)。
-- 1) journal/posting 仅允许追加:UPDATE/DELETE 一律拒绝,维护通道 = ledger_maintenance_delete_journals()。
-- 2) 每笔 journal 借贷合计为零在 COMMIT 时校验(DEFERRABLE 约束触发器;
--    LedgerRepository.postJournal 不自启事务、journal 头与全部 posting 由调用方单事务写入,
--    因此 COMMIT 时点校验安全——这是服务层 assertBalanced 的 DB 兜底)。
-- 幂等:全部 CREATE OR REPLACE / DROP TRIGGER IF EXISTS,Flyway 重放安全。

CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    IF coalesce(current_setting('grassland.ledger_mutation_allowed', true), 'off') <> 'on' THEN
        RAISE EXCEPTION 'ledger journal/posting is append-only (HLD §6.4); use a reversal journal or ledger_maintenance_delete_journals()';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_journal_immutable ON journal;
CREATE TRIGGER trg_journal_immutable
    BEFORE UPDATE OR DELETE ON journal
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

DROP TRIGGER IF EXISTS trg_posting_immutable ON posting;
CREATE TRIGGER trg_posting_immutable
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE OR REPLACE FUNCTION assert_journal_balanced() RETURNS trigger AS $$
DECLARE
    debits bigint;
    credits bigint;
BEGIN
    SELECT coalesce(sum(amount_cents) FILTER (WHERE direction = 'DEBIT'), 0),
           coalesce(sum(amount_cents) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debits, credits
      FROM posting
     WHERE journal_id = NEW.journal_id;
    IF debits <> credits THEN
        RAISE EXCEPTION 'journal % unbalanced at commit: debit % <> credit %', NEW.journal_id, debits, credits;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_posting_journal_balanced ON posting;
CREATE CONSTRAINT TRIGGER trg_posting_journal_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_balanced();

-- 维护/测试通道:单语句内开事务局部开关(set_config 第 3 参 true=事务局部,
-- 同语句内行级触发器可见)后按 operation_id 前缀删除。仅供 IT 清理与运维排障;
-- 业务代码禁止调用——业务路径的 UPDATE/DELETE 被触发器默认拦死。
CREATE OR REPLACE FUNCTION ledger_maintenance_delete_journals(p_operation_prefix text)
RETURNS integer AS $$
DECLARE
    deleted_postings integer;
BEGIN
    PERFORM set_config('grassland.ledger_mutation_allowed', 'on', true);
    WITH doomed AS (
        DELETE FROM posting
         WHERE journal_id IN (SELECT id FROM journal WHERE operation_id LIKE p_operation_prefix)
        RETURNING 1
    )
    SELECT count(*) INTO deleted_postings FROM doomed;
    DELETE FROM journal WHERE operation_id LIKE p_operation_prefix;
    RETURN deleted_postings;
END;
$$ LANGUAGE plpgsql;
