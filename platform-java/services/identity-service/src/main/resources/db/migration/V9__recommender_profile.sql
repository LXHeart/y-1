-- 草场 identity V9：推荐官画像（PRD 六「商家筛选推荐官」的基础信息 / 社交平台 / 标签）。
--
-- 背景：商家收到报名时，界面上只有一串 account_id 前 8 位——谁报的、做什么内容的、
-- 有没有社交账号，全不知道，接受/拒绝是盲选。撮合平台的撮合质量是它存在的理由，这个洞得先补。
--
-- 归属：画像是**身份资料**，故落在 identity（声誉指标由 marketplace 从履约事实派生，两者分开）。
--
-- 标签用 text[] 而不是关联表：本轮标签是推荐官**自选**的短字符串集合，没有独立生命周期，
-- 也没有「标签自身的属性」要存。等 PRD 六说的「系统自动打标」落地、需要打标来源/置信度时再拆表。

CREATE TABLE recommender_profile (
    account_id uuid PRIMARY KEY,                 -- 跨服务引用 app_users，无 FK（database-per-service）
    display_name varchar(64),                    -- 对商家展示的昵称；空则前端回落到账号邮箱前缀
    bio text,                                    -- 自我介绍
    content_tags text[] NOT NULL DEFAULT '{}',   -- 内容风格标签：美食探店 / 生活日常 / 高端测评 …
    domain_tags text[] NOT NULL DEFAULT '{}',    -- 擅长领域标签：餐饮 / 丽人 / 休闲娱乐 / 酒旅 …
    -- 已绑定的社交账号：[{platform, handle, followers}]。用 jsonb 存——平台种类会持续增加，
    -- 且本轮不对单个账号做查询/约束；真要按「粉丝数 ≥ x」筛选时再拆表建索引。
    social_accounts jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
