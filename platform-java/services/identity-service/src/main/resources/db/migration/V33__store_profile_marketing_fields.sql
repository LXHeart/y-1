-- 任务书 #24：门店资料扩成 PRD §2.1 完整门店资料（营销/品牌字段）。
-- 列表字段用 text[]（同 recommender_profile 惯例）；空数组与 null 等价（清空语义）。
-- 品牌语气/价格区间/到店提示 blank 归一为 null；金额一律 cents。
-- 不改 V22 KYB 审核列与 status 语义：编辑仍走 draft 重置，审核流程零变动。
ALTER TABLE store_profile
    ADD COLUMN categories text[],            -- 主营品类
    ADD COLUMN signature_items text[],       -- 特色产品/服务
    ADD COLUMN selling_points text[],        -- 推荐卖点
    ADD COLUMN must_emphasize text[],        -- 必须强调
    ADD COLUMN forbidden_phrases text[],     -- 禁止表达
    ADD COLUMN allowed_tags text[],          -- 可使用标签
    ADD COLUMN brand_tone text,              -- 品牌语气
    ADD COLUMN price_range varchar(50),      -- 价格区间（自由文本，如 ¥30–¥80）
    ADD COLUMN average_spend_cents integer,  -- 人均消费（cents）
    ADD COLUMN visit_notes text;             -- 交通/停车/预约/到店注意
