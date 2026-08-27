-- 任务书 #48：成员添加审核开关（D6）。默认免审——单店小商户零负担；连锁商家自行开启后，
-- 店长代建的员工账号需主体过审才转 active。开关只影响店长代建路径，owner/admin 直建永不 pending。
ALTER TABLE organization
    ADD COLUMN IF NOT EXISTS member_review_required BOOLEAN NOT NULL DEFAULT FALSE;
