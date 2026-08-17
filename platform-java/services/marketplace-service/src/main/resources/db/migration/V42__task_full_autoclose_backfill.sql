-- #26 满员自动关闭：回填历史满员任务。口径 = accepted 计数 >= max_slots（设计 D1/D8）。
-- 纯 DML（无 DDL），重放安全；不伪造 outbox 事件（V11/V14 先例）。
UPDATE task
SET status = 'closed', version = version + 1, updated_at = now()
WHERE status = 'published'
  AND max_slots IS NOT NULL
  AND (SELECT count(*) FROM task_application a
       WHERE a.task_id = task.id AND a.status = 'accepted') >= task.max_slots;
