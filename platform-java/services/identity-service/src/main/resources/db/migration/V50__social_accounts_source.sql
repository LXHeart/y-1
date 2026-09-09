-- 草场 identity V50：社交账号粉丝数据来源标注（任务书 #98 / D98-03）。
--
-- recommender_profile.social_accounts 为 jsonb 数组（V9），存量元素缺 source/collectedAt。
-- 回填（幂等可重放）：
--   source      缺省 'self_reported'（COALESCE——已有值不覆盖）；
--   collectedAt 缺省 = 行更新时间 updated_at（自报保存时点，可解释）。
-- verified/platform_fact 仅枚举预留：无平台授权接入，任何路径不得产出这两种值（D98-03 红线）。
-- 列存在性守卫：迁移重放测试会以最小自造 schema（仅 account_id 列）重放 V13+，须容错跳过。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'recommender_profile'
                 AND column_name = 'social_accounts') THEN
        UPDATE recommender_profile p
        SET social_accounts = (
            SELECT COALESCE(jsonb_agg(
                       CASE WHEN jsonb_typeof(elem) = 'object' THEN
                           elem || jsonb_build_object(
                               'source', COALESCE(elem->>'source', 'self_reported'),
                               'collectedAt', COALESCE(elem->>'collectedAt',
                                   to_char(p.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')))
                       ELSE elem END
                       ORDER BY ord), '[]'::jsonb)
            FROM jsonb_array_elements(p.social_accounts) WITH ORDINALITY AS t(elem, ord)
        )
        WHERE p.social_accounts IS NOT NULL
          AND jsonb_typeof(p.social_accounts) = 'array'
          AND p.social_accounts <> '[]'::jsonb;
    END IF;
END $$;
