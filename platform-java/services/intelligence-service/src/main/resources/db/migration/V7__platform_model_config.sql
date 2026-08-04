-- GL-P3-AI-001：平台模型控制面（model-control-plane，HLD §5.6 / §12.3）。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v7）。
--
-- 平台按能力配置主备模型/provider/健康/并发/适用范围，版本化（HLD §2.3 / §6.2「模型必须保存使用时的
-- 版本快照」）。ByokRoutingService 的平台分支据此解析（替掉原 env 硬编码），TaskContext 冻结 version。
-- admin CRUD 经 requireAdmin（GL-P3-AI-001 新增门闩）。

-- V1 留了 platform_model_config 雏形（capability 单列 PK、从未写入）。本 slice 落真实控制面：
-- 需 (capability, model_role) 主备 + 版本化，故重建。表未切流、无数据，DROP 安全。
DROP TABLE IF EXISTS platform_model_config;

-- 当前有效配置：每个 (capability, model_role) 同时只有一个 enabled 行（部分唯一索引强制）
CREATE TABLE platform_model_config (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    capability          varchar(64) NOT NULL,              -- text/vision/image_generation/video_understanding/video_generation/voice/content_safety/retrieval
    model_role          varchar(16) NOT NULL,               -- primary / backup
    provider            varchar(64) NOT NULL,               -- qwen / openai-compatible / ...
    model               varchar(128) NOT NULL,
    base_url            text NOT NULL,
    max_concurrency     int,                                -- null = 不限
    health_status       varchar(16) NOT NULL DEFAULT 'healthy', -- healthy / degraded / unhealthy
    enabled             boolean NOT NULL DEFAULT true,
    version             int NOT NULL DEFAULT 1,             -- 每次 admin mutation +1（单调）
    updated_by          text,                               -- admin account id（审计）
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- 每个 (capability, model_role) 同时至多一个 enabled 行——upsert 新版本时必须先 disable 旧版本
CREATE UNIQUE INDEX idx_platform_model_config_current
    ON platform_model_config(capability, model_role)
    WHERE enabled = true;

-- 按能力查主备（运行时路由）
CREATE INDEX idx_platform_model_config_capability
    ON platform_model_config(capability, model_role)
    WHERE enabled = true;

-- 版本快照历史（append-only，HLD §2.3 模型版本化）：每次 admin 变更落一行，存量 Run 按其时点版本结算/复现
CREATE TABLE platform_model_config_history (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    capability          varchar(64) NOT NULL,
    model_role          varchar(16) NOT NULL,
    provider            varchar(64) NOT NULL,
    model               varchar(128) NOT NULL,
    base_url            text NOT NULL,
    max_concurrency     int,
    health_status       varchar(16) NOT NULL,
    version             int NOT NULL,
    changed_by          text NOT NULL,
    change_type         varchar(16) NOT NULL,               -- create / update / disable / delete
    changed_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_model_config_history_lookup
    ON platform_model_config_history(capability, model_role, version DESC);
