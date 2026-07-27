-- 草场 marketplace V6：商家对履约的评分（PRD 五「等级体系」的唯一缺失事实源）。
--
-- 背景：声誉的其余指标（完成数、完成率、响应时长）都能从既有事实派生，唯独**评分**在库里
-- 没有任何来源——而 PRD 五的 Lv3/Lv4/Lv5 门槛都含「评分 ≥4.0 / 4.5 / 4.8」。
-- 没有这张表，等级体系永远卡在 Lv2。
--
-- 只落评分本身。声誉指标（完成数/完成率/平均评分/平均响应时长）一律**从事实实时派生**，
-- 不建冗余汇总表：既有数据量下一次查询即可算完，冗余表反而带来「与事实不一致」的新故障面。

CREATE TABLE engagement_rating (
    id uuid PRIMARY KEY,
    -- 一次履约至多一份评分（UNIQUE）：商家评过就不能反复改分刷高/压低推荐官
    application_id uuid NOT NULL UNIQUE REFERENCES task_application(id),
    task_id uuid NOT NULL REFERENCES task(id),
    recommender_account_id uuid NOT NULL,   -- 冗余被评人：声誉聚合按人查，避免每次 join application
    rated_by_account_id uuid NOT NULL,      -- 打分的商家（= 任务 owner，服务端自查后写入）
    score smallint NOT NULL CHECK (score >= 1 AND score <= 5),  -- PRD 五：1-5 星
    comment text,                            -- 评语（可选）
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 声誉聚合的主查询路径：按被评推荐官取平均分与评分数。
CREATE INDEX idx_engagement_rating_recommender ON engagement_rating(recommender_account_id);
