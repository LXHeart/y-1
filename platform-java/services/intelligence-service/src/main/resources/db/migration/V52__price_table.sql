-- 价目表搬入数据库（原先硬编码在 PriceTableService.buildDefaultPrices()）。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v52）。
--
-- 背景：加一个新模型（如 MiniMax）此前必须改 Java + 重新 bootJar + 重建镜像，运营无法自助调价。
--
-- **同时修一个既有缺陷**：PriceTable 的注释与 ai_run.price_table_version 都声称「存量 Run 按其
-- 时点价目表结算」，但 calculateCost 用的是 getCurrent()，getVersion(version) 零调用方。
-- 只有一张 v1 时两者等价，故一直没暴露；一旦能在页面改价就变成真 bug：
--   Run 在 v1 冻结 → 运营改价产生 v2 → 该 Run 的异步回调按 v2 结算。
-- 因此本迁移建的是「版本头 + 明细」两张表，且服务层改为按 Run 冻结的版本查表。
--
-- 形状照 content_safety_lexicon_version（任务书 #45）：status 三态 + 单 active 部分唯一索引。
-- 不用 JSONB 存明细：单价要按 model_id 唯一约束，且页面要逐模型编辑。

CREATE TABLE IF NOT EXISTS price_table_version (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 对外的版本号，等同 ai_run.price_table_version 的值（如 v1、v2）
    label        varchar(64) NOT NULL UNIQUE,
    status       varchar(16) NOT NULL
                 CONSTRAINT chk_price_table_status CHECK (status IN ('draft', 'active', 'retired')),
    note         text,
    created_by   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz
);

-- 同时只有一张生效价目表；draft/retired 不受限
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_table_single_active
    ON price_table_version (status) WHERE status = 'active';

CREATE TABLE IF NOT EXISTS price_table_model (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id                  uuid NOT NULL
                                REFERENCES price_table_version(id) ON DELETE CASCADE,
    -- 与 platform_model_config.model 同一取值域
    model_id                    varchar(128) NOT NULL,
    capability                  varchar(64) NOT NULL,
    provider                    varchar(64) NOT NULL,
    -- 四个计量维度，单位统一为「分」；用不到的维度填 0（全 0 = 免费，isZeroPricedModel 据此判定）
    cents_per_1k_input_tokens   integer NOT NULL DEFAULT 0
                                CONSTRAINT chk_price_input_nonneg CHECK (cents_per_1k_input_tokens >= 0),
    cents_per_1k_output_tokens  integer NOT NULL DEFAULT 0
                                CONSTRAINT chk_price_output_nonneg CHECK (cents_per_1k_output_tokens >= 0),
    cents_per_image             integer NOT NULL DEFAULT 0
                                CONSTRAINT chk_price_image_nonneg CHECK (cents_per_image >= 0),
    cents_per_second            integer NOT NULL DEFAULT 0
                                CONSTRAINT chk_price_second_nonneg CHECK (cents_per_second >= 0)
);

-- 同一版本内一个 model_id 只能有一条单价
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_table_model_unique
    ON price_table_model (version_id, model_id);

CREATE INDEX IF NOT EXISTS idx_price_table_model_lookup
    ON price_table_model (version_id);

COMMENT ON TABLE price_table_version IS
    '价目表版本头；单 active。存量 ai_run 按其冻结的 label 结算，故 retired 版本必须保留';
COMMENT ON TABLE price_table_model IS
    '某版本价目表的逐模型单价（分/计量单位）；四维全 0 = 免费，不进计费路径';
