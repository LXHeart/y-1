-- 任务书 #64 卡1：media_reference.purpose 增加 video_take / video_master 两个用途。
--
-- **本迁移只改注释**，不是遗漏。media_reference.purpose（V4）是裸 varchar(64) 且**没有 CHECK 约束**
-- （V4 行 10 只用行内注释列举取值），所以用途值集的强制点在 Java 侧：
--   - MediaPurpose 枚举（受控值集唯一真相源，卡1 同步加两个常量）
--   - StoreMediaModerationService.isModerated（按用途决定是否过审核）
-- 库里加 CHECK 反而会把「值集真相源」劈成两处，且 V4 存量行未必全在新值集内——不做。
-- 任务书卡1「V61 迁移：media_reference.purpose 增加 video_master/video_take（若 purpose 是
-- varchar 则仅注释；按实际 DDL 定）」——实际 DDL 即为此分支。
--
-- 用 DO 块包 COMMENT 而非裸语句：PlatformModelConcurrencyMigrationTest 把 V14..V61 重放进
-- 只手工建了 platform_model_config / _history / ai_run 的合成 schema，那里 **media_reference 不存在**，
-- 裸 COMMENT ON COLUMN 会直接 42P01 让该回归测试起不来（与 V60 文件头约束 2 同源）。
DO $$
BEGIN
    -- table_schema 必须锁 current_schema()：同一测试库的 public 里有应用自己迁出来的
    -- media_reference，不限 schema 会让守卫在合成 schema 里误判为存在，COMMENT 仍 42P01。
    IF EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = 'media_reference' AND column_name = 'purpose') THEN
        COMMENT ON COLUMN media_reference.purpose IS
            '媒体用途受控值集（强制点在 Java MediaPurpose 枚举，本列无 CHECK）：'
            'article_generated / engagement_attachment / merchant_kyb / video_asset / '
            'user_upload / content_asset / avatar / brand_logo / speech_audio / '
            'store_media / card_series，'
            '#64 新增 video_take（逐镜候选片段）与 video_master（合成成片）。';
    END IF;
END $$;
