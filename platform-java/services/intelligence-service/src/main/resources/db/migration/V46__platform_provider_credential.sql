-- 任务书 #47 S1：平台通用凭据表（D1/D2/D3）。独立 Flyway 历史：intelligence_flyway_schema（now at v46）。
--
-- 背景：平台模型凭据此前只有单个环境变量 ai.qwen.api-key（PlatformModelConfig.validate 启动期
-- fail-fast），admin 能改 platform_model_config 的 provider/base_url 却改不了 key —— 换了地址
-- 密钥不跟着换，配了也调不通（D23②）。
--
-- 决策：把 base_url 与 encrypted_key 收进同一行凭据（D2），platform_model_config 改挂
-- credential_id。「一套通用密钥」= 建一行凭据，十几行模型配置全部指向它（D3）。
-- 不按 provider 归组：provider 是类别不是实例（qwen|openai-compatible|sandbox），
-- openai-compatible 可以是多家不同 base_url 的厂商，按名归组必撞车。
--
-- 本迁移只做「数据并存」：base_url 列保留，读路径不动（S1 关键断言=既有 AI 流行为逐字节不变）。
-- credential_id 收 NOT NULL 与 DROP base_url 在 V47（破坏性，单独发布）。

CREATE TABLE IF NOT EXISTS platform_provider_credential (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name            text NOT NULL,                  -- 运营可读标签，如 "qwen-dashscope.aliyuncs.com"
    provider        varchar(64) NOT NULL,           -- qwen / openai-compatible / sandbox
    base_url        text NOT NULL,
    -- NULL = 无密钥凭据：① sandbox provider 本就不需要 key（D23①）；
    -- ② env bootstrap 兜底——回填出的行先为 NULL，执行侧回落 ai.qwen.api-key（D1/D8）。
    encrypted_key   text,
    key_version     text,                           -- EnvelopeEncryption.keyVersion(ciphertext)
    masked_hint     text,                           -- MaskedKey.mask 结果；GET 只回这个（D5）
    enabled         boolean NOT NULL DEFAULT true,
    version         bigint NOT NULL DEFAULT 1,      -- 每次变更 +1（轮换/改连接信息），审计与 ai_run 冻结用
    updated_by      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- 同一 (provider, base_url) 至多一行有效凭据：同目的地两把 key 无法确定用哪把。
CREATE UNIQUE INDEX IF NOT EXISTS idx_platform_provider_credential_destination
    ON platform_provider_credential(provider, base_url)
    WHERE enabled = true;

-- 标签在有效行内唯一（停用后可复用同名）。
CREATE UNIQUE INDEX IF NOT EXISTS idx_platform_provider_credential_name
    ON platform_provider_credential(name)
    WHERE enabled = true;

-- 回填：platform_model_config 的每个 distinct (provider, base_url) 一行凭据，密钥留空走 env 兜底。
-- 覆盖**全部**行（含 enabled=false 的历史版本），否则历史行的 credential_id 会是 NULL，
-- V47 的 NOT NULL 收口将失败。仅被历史行引用的目的地回填为 enabled=false（不占用唯一索引）。
WITH destinations AS (
    SELECT provider,
           base_url,
           bool_or(enabled) AS still_active,
           min(created_at)  AS first_seen
    FROM platform_model_config
    GROUP BY provider, base_url
), labelled AS (
    SELECT provider,
           base_url,
           still_active,
           provider || '-' || coalesce(substring(base_url from '://([^/:]+)'), 'default') AS base_name,
           row_number() OVER (
               PARTITION BY provider, coalesce(substring(base_url from '://([^/:]+)'), 'default')
               ORDER BY first_seen, base_url
           ) AS dup_rank
    FROM destinations
)
INSERT INTO platform_provider_credential(name, provider, base_url, enabled, version, updated_by)
SELECT CASE WHEN dup_rank = 1 THEN base_name ELSE base_name || '-' || dup_rank END,
       provider,
       base_url,
       still_active,
       1,
       'system'
FROM labelled;

ALTER TABLE platform_model_config
    ADD COLUMN IF NOT EXISTS credential_id uuid REFERENCES platform_provider_credential(id);

UPDATE platform_model_config AS config
SET credential_id = credential.id
FROM platform_provider_credential AS credential
WHERE credential.provider = config.provider
  AND credential.base_url = config.base_url
  AND config.credential_id IS NULL;

-- 运行时按凭据反查引用（D6 引用中拒删）。
CREATE INDEX IF NOT EXISTS idx_platform_model_config_credential
    ON platform_model_config(credential_id);
