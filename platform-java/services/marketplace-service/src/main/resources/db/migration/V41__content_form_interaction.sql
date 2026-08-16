-- 草场 marketplace V41：content_form 受控化 + 互动任务（任务书 #23 / ADR-D13）。
--
-- content_form 自 V1 起是自由文本（varchar(32) 无值域），本迁移把它规范化为受控值集
-- image|video|article|interaction（NULL=未指定）：先归并存量（lower/trim + 已知同义词，未知置 NULL），
-- 再 CHECK 两段式（NOT VALID → VALIDATE，镜像 V21，避免锁表）。
-- engagement_submission 加 platform_handle：互动任务的推荐官平台账号标识（提交契约层校验必填，DB 可空防御）。

UPDATE task SET content_form = NULL
 WHERE content_form IS NOT NULL AND content_form = '';

UPDATE task SET content_form = lower(trim(content_form));

UPDATE task SET content_form = 'video'  WHERE content_form IN ('视频', 'videoseeding');
UPDATE task SET content_form = 'image'  WHERE content_form IN ('图文', '图片');
UPDATE task SET content_form = 'article' WHERE content_form IN ('文章');

-- 未知值（不在受控值集内）置 NULL：可见性不受影响（feed 只在显式筛选时按值过滤），发布修订时商家可重选。
UPDATE task SET content_form = NULL
 WHERE content_form IS NOT NULL
   AND content_form NOT IN ('image', 'video', 'article', 'interaction');

-- task_version 是 task 的不可变快照，同口径归并（否则 CHECK 会让迁移卡死在旧快照行上）。
UPDATE task_version SET content_form = NULL
 WHERE content_form IS NOT NULL AND content_form = '';
UPDATE task_version SET content_form = lower(trim(content_form));
UPDATE task_version SET content_form = 'video'  WHERE content_form IN ('视频', 'videoseeding');
UPDATE task_version SET content_form = 'image'  WHERE content_form IN ('图文', '图片');
UPDATE task_version SET content_form = 'article' WHERE content_form IN ('文章');
UPDATE task_version SET content_form = NULL
 WHERE content_form IS NOT NULL
   AND content_form NOT IN ('image', 'video', 'article', 'interaction');

ALTER TABLE task DROP CONSTRAINT IF EXISTS ck_task_content_form;
ALTER TABLE task ADD CONSTRAINT ck_task_content_form
    CHECK (content_form IS NULL OR content_form IN ('image', 'video', 'article', 'interaction')) NOT VALID;
ALTER TABLE task VALIDATE CONSTRAINT ck_task_content_form;

ALTER TABLE task_version DROP CONSTRAINT IF EXISTS ck_task_version_content_form;
ALTER TABLE task_version ADD CONSTRAINT ck_task_version_content_form
    CHECK (content_form IS NULL OR content_form IN ('image', 'video', 'article', 'interaction')) NOT VALID;
ALTER TABLE task_version VALIDATE CONSTRAINT ck_task_version_content_form;

ALTER TABLE engagement_submission ADD COLUMN IF NOT EXISTS platform_handle varchar(64);
