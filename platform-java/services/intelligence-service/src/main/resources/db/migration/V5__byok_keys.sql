-- 草场 GL-P3-AI-001 Phase 1：BYOK 密钥存储（Envelope Encryption）。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v5）。

-- BYOK 密钥存储表（Envelope Encryption）
CREATE TABLE ai_provider_key (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     text,                             -- 可空；个人用户 BYOK
    owner_account_id    text NOT NULL,                     -- 创建者（逻辑引用 app_users）
    capability          varchar(64) NOT NULL,               -- text/image/image_generation/video
    provider            varchar(64) NOT NULL DEFAULT 'openai-compatible',
    base_url            text NOT NULL,
    model               varchar(128),
    encrypted_key       text NOT NULL,                     -- Base64 密文（Envelope Encryption）
    key_version         text NOT NULL DEFAULT 'v1',        -- KEK 版本
    masked_hint         text NOT NULL,                     -- 掩码提示（sk-***xyz）
    enabled             boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- 一个人/一个组织 + 一种能力 + 一个 provider 只能有一个有效密钥
CREATE UNIQUE INDEX idx_ai_provider_key_unique
    ON ai_provider_key(
        COALESCE(organization_id, 'personal'::text),
        owner_account_id,
        capability,
        provider
    ) WHERE enabled = true;

-- 按 org + capability 查询（运行时路由）
CREATE INDEX idx_ai_provider_key_lookup
    ON ai_provider_key(organization_id, capability, enabled)
    WHERE enabled = true;

-- 按个人账号 + capability 查询
CREATE INDEX idx_ai_provider_key_personal
    ON ai_provider_key(owner_account_id, capability, enabled)
    WHERE enabled = true AND organization_id IS NULL;
