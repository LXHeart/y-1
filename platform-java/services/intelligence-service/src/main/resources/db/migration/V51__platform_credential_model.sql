-- 任务书 #47 S1 续：平台凭据的「已勾选模型」白名单。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v51）。
--
-- 背景：治理台「平台模型」表单的模型名此前是自由文本框，admin 得凭记忆手抄模型名，抄错要等
-- 运行时 502/unpriced 才暴露。GET /api/admin/ai/credentials/{id}/models 能实时列上游模型，
-- 但实时列表不适合直接当下拉数据源：
--   1) 上游不可达（网络策略、fake-IP DNS 劫持、上游故障）时表单就没得选；
--   2) 上游会返回该 key 有权但平台并不打算用的全部模型，运营需要一层人工收敛。
--
-- 故拆成两步：admin 在「平台通用凭据」里点「获取模型」拉实时列表并打勾 → 勾选集落这张表 →
-- 「平台模型」表单的模型下拉只读这张表，不再触网。上游临时不可达不影响改配置。
--
-- 子表而非 JSONB 列：与 platform_model_concurrency_slot 同风格，且勾选集要按 model_id 唯一约束。

CREATE TABLE IF NOT EXISTS platform_credential_model (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    credential_id uuid NOT NULL
                  REFERENCES platform_provider_credential(id) ON DELETE CASCADE,
    -- 上游 /models 返回的 id，即请求体里 model 字段要填的值
    model_id      varchar(128) NOT NULL,
    -- 上游 owned_by，仅供展示；上游不回则 NULL
    owned_by      text,
    selected_by   text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- 同一凭据下同一模型只勾一次；PUT 整份覆盖时先删后插，靠这个约束兜住并发重复
CREATE UNIQUE INDEX IF NOT EXISTS idx_platform_credential_model_unique
    ON platform_credential_model (credential_id, model_id);

-- 表单加载勾选集的唯一查询路径
CREATE INDEX IF NOT EXISTS idx_platform_credential_model_lookup
    ON platform_credential_model (credential_id);

COMMENT ON TABLE platform_credential_model IS
    '平台凭据下 admin 勾选启用的模型白名单；平台模型表单的模型下拉只读这里，不实时触网';
COMMENT ON COLUMN platform_credential_model.model_id IS
    '上游 /models 的 id，等同 platform_model_config.model 要填的值';
