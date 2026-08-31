-- 任务书 #61（2026-08-31）：去AI味 skill 统一注入。
-- intelligence 自有表（非共享表，不涉 database-bootstrap 双处 DDL 铁律）。
-- humanize_skill：3 条种子经 Java 启动 Seeder（表空才种，/contracts/humanize-skills.json）。
-- humanize_config：单行表（固定 id=1），active_skill_code 为 NULL 表示不注入；
--   激活与 skill 行软关联（无 FK），注入时 JOIN + enabled=true 双检，停用即自动失效。
CREATE TABLE IF NOT EXISTS humanize_skill (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code           text NOT NULL,
    display_name   text NOT NULL,
    description    text NOT NULL DEFAULT '',
    prompt_content text NOT NULL,
    source_repo    text NOT NULL DEFAULT '',
    source_license text NOT NULL DEFAULT 'MIT',
    enabled        boolean NOT NULL DEFAULT true,
    version        int NOT NULL DEFAULT 0,
    updated_by     uuid,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT humanize_skill_code_key UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS humanize_config (
    id                 int PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    active_skill_code  text,
    version            bigint NOT NULL DEFAULT 1,
    updated_by         text,
    updated_at         timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE humanize_skill IS '去AI味写作规则库（平台级单选激活，治理台可编辑内容）';
COMMENT ON COLUMN humanize_config.active_skill_code IS '当前激活的 skill code；NULL=不注入（默认无行同 NULL 语义）';
