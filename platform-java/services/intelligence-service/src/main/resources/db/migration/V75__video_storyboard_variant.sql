-- 任务书 #100（C100-14 / API-11/12 / §7.1 V75）：独立方案谱系表。
--
-- 语义：storyboard_id 主键 = 一方案一行；parent/root 记录派生谱系（root 方案自身无行——
-- 根即自身 id）；(account_id, operation_id) 唯一支撑「派生响应丢失后原键重放返回原结果」；
-- source_edit_version / source_draft_version 记录派生时刻的源版本（重放比对）；
-- shot_id_map jsonb 记录 旧→新 镜头 ID 重映射（画布 refs/grouping 重写依据）。
--
-- 硬约束（沿用 V71～V74 口径）：DDL 全幂等，不建 FK（谱系一致性由服务层事务维护）。

CREATE TABLE IF NOT EXISTS video_storyboard_variant (
    storyboard_id uuid PRIMARY KEY,
    parent_storyboard_id uuid NOT NULL,
    root_storyboard_id uuid NOT NULL,
    account_id text NOT NULL,
    operation_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    source_edit_version bigint NOT NULL,
    source_draft_version int NOT NULL,
    title varchar(240) NOT NULL,
    shot_id_map jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'video_storyboard_variant_account_operation_key'
                       AND tablename = 'video_storyboard_variant') THEN
        CREATE UNIQUE INDEX video_storyboard_variant_account_operation_key
            ON video_storyboard_variant (account_id, operation_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'video_storyboard_variant_root_lineage'
                       AND tablename = 'video_storyboard_variant') THEN
        CREATE INDEX video_storyboard_variant_root_lineage
            ON video_storyboard_variant (root_storyboard_id, created_at, storyboard_id);
    END IF;
END $$;

COMMENT ON TABLE video_storyboard_variant IS
    '#100 C100-14 独立方案谱系：每根至多 20 派生（服务层闸），操作键幂等重放';
