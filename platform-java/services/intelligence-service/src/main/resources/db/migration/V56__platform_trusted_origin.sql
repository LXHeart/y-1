-- 任务书 #58（决策 B）：平台模型受信 origin 白名单控制面化。
-- 取代 env 锚点（ai.qwen.base-url）与 ai.platform-model.trusted-*-origins 两个 env 白名单——
-- 平台 provider base-url 的 SSRF 校验从此以本表为唯一真相源（治理台可见可删可停）。
-- 幂等 DDL（IF NOT EXISTS）：OutboxRepositoryIT / TaskLifecycleMigrationTest 重放红线。
CREATE TABLE IF NOT EXISTS platform_trusted_origin (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    origin      text NOT NULL,            -- scheme://host[:port]，无 path
    label       text NOT NULL DEFAULT '', -- 治理台备注（如「MiniMax 图像」）
    enabled     boolean NOT NULL DEFAULT true,
    version     int NOT NULL DEFAULT 0,
    updated_by  uuid,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_trusted_origin_origin_key UNIQUE (origin)
);

-- 内置默认两行（原 env 默认值落库）：仅首次建表时种（表空判断），既有部署升级不覆盖运营改动。
INSERT INTO platform_trusted_origin (origin, label)
SELECT * FROM (VALUES
    ('https://dashscope.aliyuncs.com', '内置默认·Qwen/DashScope'),
    ('https://api.openai.com', '内置默认·OpenAI 兼容')
) AS seed(origin, label)
WHERE NOT EXISTS (SELECT 1 FROM platform_trusted_origin);
