-- 草场 marketplace V14：task_application 落 bounty_cents 快照（GL-P1-TASK-001：snapshot-pinning）。
--
-- 背景：accept/结算此前重读可变 task.bounty_cents——SettlementReconciliationActivityImpl 用它判 fund/非 fund 分支，
-- startSettlementWorkflow 用它作 capture 金额。一旦放任「编辑已发布任务改赏金」（全字段 revise），accept 后改赏金
-- 会让结算读到新值：fund 任务被改成 0 后结算跳过 finance 对账（预留的钱不再走 release/reverse）、非 fund 被改成
-- >0 后结算误调 finance——两种都是真实资金错误。HLD §2.3「配置不篡改历史：履约快照」对此不成立。
--
-- 修法：accept 时把当时 task.bounty_cents 冻进 task_application.bounty_cents，accept/结算读这列而非 task 行。
-- accept 之后改 task 赏金只影响**新报名**（新 app 冻新值），已 accept 履约仍按 accept 时的金额结算。
--
-- 刻意只冻 bounty_cents：accept/结算的金融分支只读这一个 task 字段（title/platform/content_form 不进资金流），
-- 故最小充分。完整 task_version 快照引用留待将来有「按履约还原全字段」需求时再加。
--
-- 纯增量、可安全跑在已部署 V13 的库：先 ADD 可空列，backfill 自 task，再 NOT NULL（每个 app 都有 task_id 真 FK → 必有 task → 必有 bounty_cents）。
ALTER TABLE task_application ADD COLUMN bounty_cents bigint;

UPDATE task_application a
   SET bounty_cents = t.bounty_cents
  FROM task t
 WHERE a.task_id = t.id;

ALTER TABLE task_application ALTER COLUMN bounty_cents SET NOT NULL;

-- 历史回填说明：存量 app 冻的是 task「当前」赏金。本批之前赏金不可改（revise 未上线、bounty 始终冻结），
-- 故 task 当前赏金 == 该 app accept 时的赏金，回填语义正确。close/cancel 会 bump task.version 但不动 bounty，
-- 故即便 app 关联的 task version 后来变了，bounty 仍一致。
