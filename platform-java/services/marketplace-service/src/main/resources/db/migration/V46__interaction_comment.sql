-- 草场 marketplace V46：评论类互动（缺口清偿之九，ADR-D13 R5 后置项放开）。
-- actionType 受控值扩展 comment（契约层）；engagement_submission 加 comment_text：
-- 评论任务的推荐官评论文本（提交契约层校验必填 ≤500，DB 可空防御；提交链路同步 L1 词库审核）。
ALTER TABLE engagement_submission ADD COLUMN IF NOT EXISTS comment_text varchar(500);
