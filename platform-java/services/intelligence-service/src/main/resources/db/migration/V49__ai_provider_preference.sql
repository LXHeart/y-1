-- 任务书 #47 S5（D11/D12/D13/D14）：个人「用我自己的模型」按能力开关。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v49）。
--
-- 为什么按 capability 而非全局单开关（D11）：「只想自己出文本的钱、图片用平台」是最常见的
-- 部分配置，全局开关表达不了；要求四项配齐才能开又太糙。
--
-- **无行 = on**（D14，关键）：与改造前语义逐字节一致——有个人密钥就用它。存量 BYOK 用户
-- 因此零感知，迁移脚本一行都不用写。若默认 off，所有存量 BYOK 用户会在上线瞬间静默切到
-- 平台模型并**开始扣积分**（ADR-D11：BYOK 恒 0 cents，平台扣分）。只有用户显式关闭才写行。
--
-- 开关 off 不动密钥（D12）：密文照旧留在 ai_provider_key，只是不参与路由。开关的价值在可逆
-- ——「今天省自己的额度」不该让用户明天重贴一遍 key。不解密即不触碰，安全面不变。
--
-- 不塞 user_settings（D13）：那张表正被本任务清空（S7 的 D17/D18/D19），不该往里加新东西。
-- 形状对齐 V41 的 ai_org_byok_policy：小表 + version 乐观锁。

CREATE TABLE IF NOT EXISTS ai_provider_preference (
    account_id   text        NOT NULL,
    capability   varchar(64) NOT NULL,   -- text / image / image_generation / video_generation
    use_own_key  boolean     NOT NULL DEFAULT true,
    version      bigint      NOT NULL DEFAULT 1,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, capability)
);

COMMENT ON TABLE ai_provider_preference IS
    '个人按能力的 BYOK 开关；无行即视为 use_own_key=true（有个人密钥就用）';
COMMENT ON COLUMN ai_provider_preference.use_own_key IS
    'false = 该能力改用平台默认模型并按积分计费；个人密钥密文保留不删';
