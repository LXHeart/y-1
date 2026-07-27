-- 草场 intelligence-service 首个 schema（intelligence Slice 1：AI 基建层）。
-- 独立 Flyway 历史：intelligence_flyway_schema。与 identity/marketplace/finance/trust 共用 neon public schema，表名隔离。

-- outbox（复刻 marketplace 精简版：本 slice 仅写表 + Kafka 发布器）。
CREATE TABLE intelligence_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id text NOT NULL UNIQUE,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload json NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

-- 平台默认模型配置（HLD §12.3 model-control-plane 的雏形）。
-- 本 slice 实际配置从环境变量读（PlatformModelConfig），此表留作后续 BYOK/能力路由/预算演进，
-- 当前不写入数据；后续 slice 可按 capability upsert 平台主/备模型。
CREATE TABLE platform_model_config (
    capability varchar(64) PRIMARY KEY,        -- text / image / image_generation / video_production
    provider varchar(64) NOT NULL DEFAULT 'qwen',
    base_url text,
    model varchar(128),
    enabled boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now()
);
