-- 任务书 #62（2026-08-31）：商家任务可携带「目标问题」以派发知乎回答形态任务。
--
-- 纯增量、幂等（IF NOT EXISTS）、两列均 nullable —— 不回填既有行，无锁表风险。
-- task_version 是不可变快照表（V11）：只加列，既有快照行保持 NULL（历史任务无问题概念，不强造）。
-- 仅 platform=zhihu 允许携带（校验在 Controller/请求体，非 DB 约束——平台值可演进）。
-- question_ref = 从粘贴链接本地正则提取的 questionId，仅溯源存档，服务端零抓取（任务书 3.7）。
--
-- 独立 Flyway 历史：marketplace_flyway_schema（now at v50）。

ALTER TABLE task
    ADD COLUMN IF NOT EXISTS question_text text,
    ADD COLUMN IF NOT EXISTS question_ref  text;

ALTER TABLE task_version
    ADD COLUMN IF NOT EXISTS question_text text,
    ADD COLUMN IF NOT EXISTS question_ref  text;
