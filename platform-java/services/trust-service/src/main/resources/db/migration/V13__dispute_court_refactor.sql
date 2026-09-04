-- 任务书 #74 争议小法庭重构：卡 A（通道 cs_direct）+ 卡 B（举证质证期）+ 卡 D（垂类配额抽签）列。
-- 幂等 DDL 铁律：一律 ADD COLUMN IF NOT EXISTS（重放测试跑两遍）。

-- 卡 A：争议通道（court=小法庭 / cs_direct=客服直裁）。存量行默认 court 语义不变。
-- cs_due_at = cs_direct 受理时刻 + SLA（默认 120h=5 天）；court 恒空。
ALTER TABLE dispute_case
    ADD COLUMN IF NOT EXISTS channel varchar(16) NOT NULL DEFAULT 'court',
    ADD COLUMN IF NOT EXISTS cs_due_at timestamptz;

-- 卡 D：涉案任务目标平台（开争议时由 marketplace 授权响应落库）——垂类配额抽签与判例库共用，
-- 避免事后跨服务回查。存量行为 NULL（抽签按无平台信息处理，只走通用池）。
ALTER TABLE dispute_case
    ADD COLUMN IF NOT EXISTS task_platform varchar(32);

-- 卡 B：质证期（evidence 态）——复用原「开庭等待窗」48h 时隙，总资金 hold 时长不变。
-- claimant_done_at / respondent_done_at：双方「质证完毕」标志，齐 → workflow 提前开庭。
-- respondent_answered：被诉方是否已在质证期答辩（缺席仅标注不判负，D1）。
-- evidence_deadline = 受理时刻 + 质证窗（展示用；真正到期由 workflow Timer 驱动）。
ALTER TABLE dispute_case
    ADD COLUMN IF NOT EXISTS claimant_done_at timestamptz,
    ADD COLUMN IF NOT EXISTS respondent_done_at timestamptz,
    ADD COLUMN IF NOT EXISTS respondent_answered boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS evidence_deadline timestamptz;

-- 卡 B：证据轮次（claim=原告首轮 / answer=被告答辩 / rebuttal=原告补充）。
-- 存量行（开争议首轮 + 既有补证）默认 claim。
ALTER TABLE dispute_evidence
    ADD COLUMN IF NOT EXISTS phase varchar(16) NOT NULL DEFAULT 'claim';

-- 卡 D：面板熟手标记（涉案平台完成 ≥3 任务的审判官）。硬配额 ≥4/7 席的达成率供治理台核查。
ALTER TABLE dispute_panel_assignment
    ADD COLUMN IF NOT EXISTS matched_platform boolean NOT NULL DEFAULT false;
