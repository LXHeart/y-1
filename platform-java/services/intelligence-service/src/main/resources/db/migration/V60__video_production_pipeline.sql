-- 任务书 #64（2026-09-01）：视频制作管线重构——分镜化成片生成。
--
-- 从「纯文本脚本 + 单镜头模型直出」重构为「结构化分镜 → 逐镜图生视频(多 take) → TTS 配音
-- → 字幕/BGM → FFmpeg 合成」的全链成片管线。本迁移只建新表，**旧 video_generation_job 一列不动**
-- （旧行只读保留，旧 jobs 端点继续可用；任务书 §2 范围外「不做旧表数据迁移」）。
--
-- 两条硬约束（违反即在既有回归测试上炸，勿改）：
--   1. **全部 DDL 幂等**（IF NOT EXISTS）——重放安全，可跑在空库与存量库。
--   2. **media_id 一律裸 uuid、不建 FK**。PlatformModelConcurrencyMigrationTest 从 V13 baseline
--      把 V14..V60 重放进只手工建了 platform_model_config / _history / ai_run 三张表的合成 schema，
--      那里 **media_reference（V4）不存在**——FK 过去会让该回归测试整体起不来。
--      对 ai_run 的 FK 反而安全（合成 schema 手工建了它，V22 的 run_id FK 即先例）。
--
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v60；V59 = #62 知乎双模式）。

-- ── 分镜（storyboard）：一次分镜生成 = 一行 ─────────────────────────────────────
-- request_payload 存整份分镜请求（含 base64 图片，1-9 张）——V22 的 input_payload 同款做法。
-- 必须落库而非只留在请求里：take worker 与图文成片是**异步**执行，届时原请求早已消失，
-- 锚定图（video_shot.anchor_image_index → 第 N 张图）只能从这里取。
CREATE TABLE IF NOT EXISTS video_storyboard (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id               text NOT NULL,          -- 逻辑引用 app_users，跨服务无 FK
    organization_id          text,                   -- 可空；独立模式无 org
    context_snapshot_id      uuid,                   -- 任务模式冻结的创作上下文；独立模式 null
    target_duration_seconds  int NOT NULL
                             CHECK (target_duration_seconds BETWEEN 15 AND 60
                                    AND target_duration_seconds % 5 = 0),  -- P9：15-60 步进 5
    request_payload          jsonb NOT NULL,
    status                   varchar(16) NOT NULL DEFAULT 'draft'
                             CHECK (status IN ('draft', 'committed')),
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_video_storyboard_account
    ON video_storyboard(account_id, created_at DESC);

-- ── 镜头（shot）：分镜下的逐镜行，用户可在第 2 步编辑 ──────────────────────────
-- anchor_image_index 是 **1 基** 图片序号，0 = 无锚定图（纯文生 / 图文成片复用相邻锚定图）。
-- 上界不进 CHECK：图片数是 request_payload 里的运行期事实，DDL 看不见，
-- 由 API 层按「∈ [0, 图片数]」校验并 400（任务书卡3.4）。
CREATE TABLE IF NOT EXISTS video_shot (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    storyboard_id      uuid NOT NULL REFERENCES video_storyboard(id) ON DELETE CASCADE,
    seq                int NOT NULL CHECK (seq BETWEEN 1 AND 10),   -- 镜头数 3-10（下界由 API 保证）
    visual             text NOT NULL,
    narration          text NOT NULL,
    planned_seconds    int NOT NULL CHECK (planned_seconds BETWEEN 4 AND 6),
    camera_move        varchar(32) NOT NULL,
    anchor_image_index int NOT NULL DEFAULT 0 CHECK (anchor_image_index >= 0),
    prompt             text NOT NULL,
    status             varchar(16) NOT NULL DEFAULT 'draft'
                       CHECK (status IN ('draft', 'generating', 'ready', 'failed')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (storyboard_id, seq)
);

-- ── take（每镜多候选，P5 抽卡）：默认 2 个候选，治理台可配 1-3 ─────────────────
-- claimed_until / claim_token / attempts / next_attempt_at 与 V22 的 worker claim 协议同构
-- （FOR UPDATE SKIP LOCKED + lease），以便 TakeGenerationWorker 照搬 VideoGenerationWorker 模式。
-- media_id：归档进私有对象存储后的 media_reference.id。**裸 uuid 无 FK**（见文件头约束 2）。
CREATE TABLE IF NOT EXISTS video_shot_take (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shot_id          uuid NOT NULL REFERENCES video_shot(id) ON DELETE CASCADE,
    take_no          int NOT NULL CHECK (take_no >= 1),
    provider         varchar(64) NOT NULL,
    model            varchar(128) NOT NULL,
    provider_task_id varchar(256),
    status           varchar(24) NOT NULL DEFAULT 'queued'
                     CHECK (status IN ('queued', 'submitted', 'processing',
                                       'succeeded', 'failed', 'cancelled')),
    attempts         int NOT NULL DEFAULT 0,
    media_id         uuid,
    duration_ms      int,
    error_code       varchar(64),
    error_message    text,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    claimed_until    timestamptz,
    claim_token      uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    UNIQUE (shot_id, take_no)
);
-- worker 派发走此部分索引（只扫未终结行），避免全表扫。
CREATE INDEX IF NOT EXISTS idx_video_shot_take_dispatch
    ON video_shot_take(next_attempt_at, created_at)
    WHERE status IN ('queued', 'submitted', 'processing');
CREATE INDEX IF NOT EXISTS idx_video_shot_take_shot
    ON video_shot_take(shot_id, take_no);

-- ── 逐镜配音（P1：MiniMax TTS，凭据/模型走治理台「AI 模型」）─────────────────────
-- 每镜一行（shot_id UNIQUE）：旁白为空或 TTS 不可用时落 status='skipped'，不阻断成片。
-- cues 存字幕时间轴（[{"text":"...","startMs":0,"endMs":1800}]），卡8 由此生成硬字幕与 SRT（P4 两者都要）。
-- duration_ms 是**实测**音频时长（ffprobe），卡8 音视频对齐与 P2 实际时长结算都读它。
CREATE TABLE IF NOT EXISTS video_shot_audio (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shot_id         uuid NOT NULL UNIQUE REFERENCES video_shot(id) ON DELETE CASCADE,
    provider        varchar(64),
    model           varchar(128),
    status          varchar(24) NOT NULL DEFAULT 'queued'
                    CHECK (status IN ('queued', 'processing', 'succeeded', 'failed', 'skipped')),
    attempts        int NOT NULL DEFAULT 0,
    media_id        uuid,                    -- 裸 uuid，见文件头约束 2
    cues            jsonb,
    duration_ms     int,
    error_code      varchar(64),
    error_message   text,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_until   timestamptz,
    claim_token     uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz
);
CREATE INDEX IF NOT EXISTS idx_video_shot_audio_dispatch
    ON video_shot_audio(next_attempt_at, created_at)
    WHERE status IN ('queued', 'processing');

-- ── 成片任务（P2 一价到底的计费主体）────────────────────────────────────────────
-- mode：video（有视频渠道）/ slideshow（P6 无渠道自动降级图文成片）。降级不额外收费。
-- selection 存用户选定的 take（{"<shotId>":"<takeId>"}）；卡9 未显式选则默认每镜首个 succeeded take。
-- 计费：estimated_cost_cents = target_duration_seconds × unit_price_cents（预留）；
--       actual_cost_cents = round(实际成片秒) × unit_price_cents（结算，差额退回）。
--       **一律经 AiExecutionService 三入口**（prepareMediaExecution / settleSuccessWithCost /
--       handleFailure），本表只存冻结参数与句柄，禁止任何手写账本 SQL（§5 计费红线）。
-- run_id 对 ai_run 建 FK：合成 schema 手工建了 ai_run，V22 已有同款先例，重放安全。
CREATE TABLE IF NOT EXISTS video_production_task (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    storyboard_id            uuid NOT NULL REFERENCES video_storyboard(id) ON DELETE CASCADE,
    account_id               text NOT NULL,
    organization_id          text,
    context_snapshot_id      uuid,
    operation_id             text NOT NULL,          -- 幂等键（沿用旧 jobs 的 idempotency 语义）
    mode                     varchar(16) NOT NULL DEFAULT 'video'
                             CHECK (mode IN ('video', 'slideshow')),
    phase                    varchar(24) NOT NULL DEFAULT 'queued'
                             CHECK (phase IN ('queued', 'generating', 'voicing', 'composing',
                                              'succeeded', 'failed', 'cancelled')),
    progress                 int NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    selection                jsonb,
    bgm_track_id             uuid,                   -- 裸 uuid（bgm_track 同库，但保持与 media 一致的松耦合）
    final_media_id           uuid,                   -- 成片 VIDEO_MASTER；裸 uuid，见约束 2
    srt_media_id             uuid,                   -- 可下载 SRT（P4）；裸 uuid
    target_duration_seconds  int NOT NULL,
    actual_duration_seconds  int,
    pricing_version          varchar(32) NOT NULL,
    unit_price_cents         int NOT NULL,
    estimated_cost_cents     int NOT NULL,
    actual_cost_cents        int,
    provider                 varchar(64),
    model                    varchar(128),
    platform_model_version   int,
    run_id                   uuid UNIQUE REFERENCES ai_run(id) ON DELETE RESTRICT,
    budget_id                uuid,
    budget_reservation_date  date,
    reserved_cost_cents      int,
    attempts                 int NOT NULL DEFAULT 0,
    error_code               varchar(64),
    error_message            text,
    next_attempt_at          timestamptz NOT NULL DEFAULT now(),
    claimed_until            timestamptz,
    claim_token              uuid,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    completed_at             timestamptz,
    UNIQUE (account_id, operation_id)
);
CREATE INDEX IF NOT EXISTS idx_video_production_task_account
    ON video_production_task(account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_production_task_dispatch
    ON video_production_task(next_attempt_at, created_at)
    WHERE phase IN ('queued', 'generating', 'voicing', 'composing');

-- ── BGM 曲库（P3：CC0/免版税，治理台上传 + 情绪分类；种子为空，运营上架指南见卡11）──
-- object_key 直指对象存储，不走 media_reference：BGM 是平台运营资产、无 owner_account_id，
-- 与「用户素材」生命周期无关（不参与配额、不随用户删除）。
-- mood_tags 是 8 个受控情绪标签的 jsonb 数组：轻快/温暖/治愈/燃/悬念/舒缓/国风/电子（值集在 Java 侧校验）。
CREATE TABLE IF NOT EXISTS bgm_track (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name         varchar(128) NOT NULL,
    mood_tags    jsonb NOT NULL DEFAULT '[]'::jsonb,
    object_key   text NOT NULL,
    content_type varchar(64) NOT NULL,
    size_bytes   bigint NOT NULL CHECK (size_bytes > 0),
    duration_ms  int,
    enabled      boolean NOT NULL DEFAULT true,
    uploaded_by  text,                      -- 上架运营的 account_id，仅留痕
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_bgm_track_enabled
    ON bgm_track(created_at DESC) WHERE enabled;

-- ── lineage kind 扩容（卡6/卡8 登记要用）───────────────────────────────────────
-- creation_generation.kind 是**真 CHECK 约束**（V53 建、V54 扩），不是纯 varchar：
-- 不在这里放行 video_storyboard / video_master，卡6 与卡8 的 lineage 写入会在运行期被库拒。
-- 沿用 V53/V54 的 DROP-then-ADD 幂等写法，并**保留 infographic**（V54 已放行，删掉即回归）。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'creation_generation_kind_check'
                 AND conrelid = 'creation_generation'::regclass) THEN
        ALTER TABLE creation_generation DROP CONSTRAINT creation_generation_kind_check;
    END IF;
    ALTER TABLE creation_generation ADD CONSTRAINT creation_generation_kind_check
        CHECK (kind IN ('video_adaptation', 'asset_image', 'scene_image',
                        'article', 'moments_copy', 'comedy_script', 'assistant_guide',
                        'card_series', 'infographic',
                        'video_storyboard', 'video_master'));
END $$;

COMMENT ON TABLE video_storyboard IS
    '#64 分镜：一次分镜生成一行；request_payload 保留原始请求（含 base64 图）供异步 worker 取锚定图';
COMMENT ON TABLE video_shot IS '#64 镜头：分镜下逐镜行，anchor_image_index 为 1 基图片序号（0=无锚定图）';
COMMENT ON TABLE video_shot_take IS '#64 take：每镜多候选（P5 默认 2，治理台可配 1-3）；media_id 裸 uuid 无 FK';
COMMENT ON TABLE video_shot_audio IS '#64 逐镜配音：每镜至多一行；cues 为字幕时间轴，duration_ms 为 ffprobe 实测';
COMMENT ON TABLE video_production_task IS
    '#64 成片任务：P2 一价到底（预留=目标时长×单价，结算=实际时长×单价，差额退回），计费只经 AiExecutionService';
COMMENT ON TABLE bgm_track IS '#64 BGM 曲库：CC0/免版税平台运营资产，直存 object_key 不进 media_reference';
