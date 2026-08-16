-- 任务书 #27：商家可配置「Lv N+ 自动通过」门槛（null=关闭，1–5 对应等级）
ALTER TABLE task ADD COLUMN auto_accept_min_level smallint NULL
    CHECK (auto_accept_min_level BETWEEN 1 AND 5);
