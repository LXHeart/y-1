-- 任务书 #100（C100-04 / API-07）：分镜 ↔ 创作草稿的唯一工作区关联表。
--
-- 语义（§6.3）：按分镜唯一补关联——storyboard_id 是主键，一个分镜至多一条关联；
-- draft_id 全局唯一（一个草稿只服务一个分镜的画布）；(account_id, operation_id) 唯一
-- 支撑「绑定响应丢失后原键重放返回同一关联」；request_hash 记录请求摘要，供重放比对。
--
-- 硬约束（沿用 V71 口径）：DDL 全幂等（IF NOT EXISTS / DO 块重建），重放进空库、
-- 存量库与 PlatformModelConcurrencyMigrationTest 合成 schema 均安全——本表不依赖
-- 其他表的外键（草稿可用性由服务层校验，不建 FK，媒体裸 UUID 惯例一致）。

CREATE TABLE IF NOT EXISTS video_storyboard_workspace (
    storyboard_id uuid PRIMARY KEY,
    draft_id uuid NOT NULL,
    account_id text NOT NULL,
    operation_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'video_storyboard_workspace_draft_key'
                       AND tablename = 'video_storyboard_workspace') THEN
        CREATE UNIQUE INDEX video_storyboard_workspace_draft_key
            ON video_storyboard_workspace (draft_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'video_storyboard_workspace_account_operation_key'
                       AND tablename = 'video_storyboard_workspace') THEN
        CREATE UNIQUE INDEX video_storyboard_workspace_account_operation_key
            ON video_storyboard_workspace (account_id, operation_id);
    END IF;
END $$;

COMMENT ON TABLE video_storyboard_workspace IS
    '#100 C100-04 分镜画布工作区关联：storyboard 唯一，draft 唯一，(account,operation) 幂等重放';
