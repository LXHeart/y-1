-- 门店公开媒体内容安全审核（缺口清偿之五，#42 D9 登记）：
-- store_media 用途媒体的多模态审核结论，一行 = 一条媒体的最新结论（重复审核覆盖更新）。
-- status：pass=通过 / review=待人工复核（公开展示不拦截）/ blocked=拦截（公开端点过滤）。
-- 无行 = 未审（审核模型未配置/失败时的 advisory 降级，公开展示不拦截，与内容安全 D6 姿态一致）。
CREATE TABLE IF NOT EXISTS store_media_moderation (
    media_reference_id uuid PRIMARY KEY REFERENCES media_reference(id),
    status varchar(16) NOT NULL CHECK (status IN ('pass', 'review', 'blocked')),
    findings jsonb NOT NULL DEFAULT '[]'::jsonb,
    model varchar(128),
    run_id varchar(128),
    moderated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_store_media_moderation_status ON store_media_moderation (status);
