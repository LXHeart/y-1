-- 任务书 #62（2026-08-31）：知乎「回答 / 文章」双模式。
--
-- 两处加列，均幂等（IF NOT EXISTS）——重放安全，可跑在空库与存量库：
--   1. creation_draft：草稿要能区分「这篇是回答还是文章」并记住目标问题，否则跨设备恢复后
--      模式与问题双丢（草稿是自动保存的唯一载体，V19 建表时无「回答」概念）。
--      question_ref 存本地正则从粘贴链接提取的 questionId，仅作溯源存档——
--      **零网络请求**：知乎问题页 2026-08-31 实测 403 zse-ck JS 挑战，抓取整条路判死（任务书 3.7）。
--   2. creation_style_skill：风格 skill 获得平台归属维度（#57 建表时只服务小红书，无归属概念）。
--      逗号分隔 platform id 而非数组：与库内其余「多值存文本」约定一致；
--      空串 = 全平台通用（存量 22 条走 DEFAULT '' 自动落到通用，零行为变化）。
--
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v59；V58 = #61 humanize）。

ALTER TABLE creation_draft
    ADD COLUMN IF NOT EXISTS content_mode  varchar(16) NOT NULL DEFAULT 'article',
    ADD COLUMN IF NOT EXISTS question_text text,
    ADD COLUMN IF NOT EXISTS question_ref  text;

-- CHECK 单独加并容忍重复执行（ADD CONSTRAINT 无 IF NOT EXISTS，靠 catalog 探测保幂等）。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'creation_draft_content_mode_check') THEN
        ALTER TABLE creation_draft
            ADD CONSTRAINT creation_draft_content_mode_check
            CHECK (content_mode IN ('article', 'answer'));
    END IF;
END $$;

-- 不可变历史快照同步加列（V19 的 creation_draft_version 是整行镜像，
-- 漏列会让「回答草稿的历史版本」丢模式与问题，恢复到旧版本即退化成文章）。
ALTER TABLE creation_draft_version
    ADD COLUMN IF NOT EXISTS content_mode  varchar(16) NOT NULL DEFAULT 'article',
    ADD COLUMN IF NOT EXISTS question_text text,
    ADD COLUMN IF NOT EXISTS question_ref  text;

ALTER TABLE creation_style_skill
    ADD COLUMN IF NOT EXISTS applicable_platforms text NOT NULL DEFAULT '';
