-- 任务书 #57（2026-08-30）：小红书图文·创作风格 skill 库（标题套路 6 / 体裁 9 / 文风 7）。
-- intelligence 自有表（非共享表，不涉 database-bootstrap 双处 DDL 铁律）。
-- 种子经 Java 启动 Seeder（表空才种，/contracts/creation-style-skills.json）。
CREATE TABLE IF NOT EXISTS creation_style_skill (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category      text NOT NULL CHECK (category IN ('TITLE_FORMULA', 'GENRE', 'STYLE')),
    code          text NOT NULL,
    name          text NOT NULL,
    description   text NOT NULL DEFAULT '',
    prompt_content text NOT NULL,
    enabled       boolean NOT NULL DEFAULT true,
    sort_order    int NOT NULL DEFAULT 0,
    version       int NOT NULL DEFAULT 0,
    updated_by    uuid,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT creation_style_skill_category_code_key UNIQUE (category, code)
);
