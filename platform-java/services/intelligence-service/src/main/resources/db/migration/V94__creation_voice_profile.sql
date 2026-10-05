-- 任务书 #108（去AI味优化）C-02 / W13：私有文风档案表（2026-10）。
-- §7.1：account+role 联合主键（四固定角色槽位）；payload 为 §6 rules/samples 的规范 JSON，
-- 由应用层 EnvelopeEncryption 加密后存 encrypted_payload；payload_hash 为含 enabled 在内的
-- 规范目标内容 SHA-256（幂等重放比较用）。不存 organization_id，无新增可公开读取字段。
-- revision CHECK(>0)：空槽在应用层表现为 revision=0（不落行），首次建槽写 1。
CREATE TABLE creation_voice_profile (
    account_id text NOT NULL,
    role text NOT NULL
        CHECK (role IN ('consumer', 'merchant', 'commercial-creator', 'researcher')),
    revision bigint NOT NULL CHECK (revision > 0),
    enabled boolean NOT NULL DEFAULT false,
    encrypted_payload text NOT NULL,
    payload_hash text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, role)
);

-- §7.4：专用 BEFORE INSERT OR UPDATE 屏障触发器。复用 V86/V87 的 gate 锁语义
-- （先注册缺失 gate 行，再 FOR SHARE 序列化，非 active 拒绝），但不改写共享函数
-- intelligence_guard_personal_write()——本表无终态回调例外，冻结/清理/已擦除一律拒绝写入，
-- 防直接 SQL 绕过应用层锁序。DELETE 不设障（注销擦除批次依赖 DELETE 清行）。
CREATE OR REPLACE FUNCTION intelligence_guard_creation_voice_write() RETURNS trigger AS $voice_guard$
DECLARE
    gate_state text;
BEGIN
    -- Register missing accounts before locking: absence must not evade serialization
    -- （与 V86 共享函数同款前置，防「无 gate 行」成为绕过通道）。
    INSERT INTO intelligence_account_lifecycle(account_id) VALUES (NEW.account_id)
        ON CONFLICT (account_id) DO NOTHING;
    SELECT state INTO gate_state FROM intelligence_account_lifecycle
        WHERE account_id = NEW.account_id FOR SHARE;
    IF gate_state <> 'active' THEN
        RAISE EXCEPTION 'account_closure_barrier: % is %', NEW.account_id, gate_state
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$voice_guard$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_creation_voice_profile ON creation_voice_profile;
CREATE TRIGGER trg_guard_creation_voice_profile
    BEFORE INSERT OR UPDATE ON creation_voice_profile
    FOR EACH ROW EXECUTE FUNCTION intelligence_guard_creation_voice_write();
