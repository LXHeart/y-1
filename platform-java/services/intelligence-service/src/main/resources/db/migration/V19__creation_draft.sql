-- 草场 PRD §4.9 智能创作助手（Slice 15 Stage 1）：创作草稿持久化（§4.9.7「保存草稿、自动保存、跨设备继续」）。
--
-- 背景：此前创作中心（AiCreationCenter / ArticleCreationView）的全部创作输入都只活在组件本地 ref，
-- 靠 Vue KeepAlive 在会话期内苟活，刷新即丢，更不能跨设备。grep 全仓确认无任何创作正文持久化
-- （localStorage 只用于主题/身份偏好；ImageAnalysisView 的「评价草稿」自述仅内存态；marketplace
-- task draft 是「商家发包任务」草稿非「推荐官创作内容」）。本迁移是 greenfield 新建。
--
-- 范式：镜像 content_asset / content_asset_version（V18）+ task / task_version（marketplace V11）的
-- 「可变当前行 + 不可变 version 快照」分层。可变行承载自动保存（debounce PUT + 乐观锁），快照表
-- 留作 §4.9.7 草稿历史 + §4.12 不可变创作上下文快照的入口（task 源落 task_version 引用）。
--
-- source 关联复用前端 CreationSource 联合类型（ai-creation.ts）：independent/task/store/hot-topic/
-- reference。task 源的 task_version 引用是 §4.12 创作上下文快照的衔接点（当前只存引用 id，完整快照另立 Slice）。
--
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v19）。与 identity/marketplace/finance/trust
-- 共用 neon public schema，表名隔离。

CREATE TABLE creation_draft (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_account_id  text NOT NULL,             -- 创作者账号（推荐官/用户），跨服务无 FK
    organization_id   text,                      -- 可空；从门店创作时填商家 org（便于后续 org 维度统计）
    title             text NOT NULL DEFAULT '未命名草稿',  -- 用户可读标题（首期默认，可改）
    source_type       varchar(16) NOT NULL       -- independent / task / store / hot-topic / reference
                      CHECK (source_type IN ('independent', 'task', 'store', 'hot-topic', 'reference')),
    -- source 关联引用（与 source_type 配对，可空；跨服务无 FK）
    task_id           text,                      -- task 源：关联履约任务 id（source_type='task' 时填）
    task_version      integer,                   -- task 源：进入创作时冻结的任务版本号（§4.12 创作上下文快照入口）
    store_id          text,                      -- store 源：关联门店 id（source_type='store' 时填）
    platform          varchar(32),               -- 目标发布平台（wechat/zhihu/xiaohongshu…，可空=未定）
    content_form      varchar(32),               -- 内容形式（graphic/video/image-text/video-text，可空=未定）
    topic             text,                      -- 创作主题/选题
    article_title     text,                      -- 文章标题（创作阶段产物，区别于草稿标题）
    outline           text,                      -- 大纲
    content           text,                      -- 正文
    status            varchar(16) NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft', 'in_progress', 'completed', 'archived')),
    version           integer NOT NULL DEFAULT 1,  -- 乐观锁（自动保存并发控制）
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz                -- 软删（与 content_asset 同口径）
);

-- 列出某用户的草稿（自动保存热路径 + 草稿列表 UI）。排除软删。
CREATE INDEX idx_creation_draft_owner
    ON creation_draft(owner_account_id, updated_at DESC)
    WHERE deleted_at IS NULL;
-- 从任务进入创作时定位「该任务的草稿」（避免重复开多篇）。
CREATE INDEX idx_creation_draft_task
    ON creation_draft(owner_account_id, task_id)
    WHERE source_type = 'task' AND task_id IS NOT NULL AND deleted_at IS NULL;

-- 不可变历史快照（镜像 task_version V11 / content_asset_version V18）。
-- §4.9.7 草稿历史 + §4.12 创作上下文快照入口：每次保存落一行整行镜像，历史不可改。
CREATE TABLE creation_draft_version (
    draft_id          uuid NOT NULL,
    version           integer NOT NULL,
    title             text NOT NULL,
    source_type       varchar(16) NOT NULL,
    task_id           text,
    task_version      integer,
    store_id          text,
    platform          varchar(32),
    content_form      varchar(32),
    topic             text,
    article_title     text,
    outline           text,
    content           text,
    status            varchar(16) NOT NULL,
    snapshotted_at    timestamptz NOT NULL DEFAULT now(),
    snapshotted_by    text NOT NULL,
    PRIMARY KEY (draft_id, version)
);
