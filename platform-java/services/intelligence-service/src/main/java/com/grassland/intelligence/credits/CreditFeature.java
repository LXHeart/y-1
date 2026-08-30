package com.grassland.intelligence.credits;

/**
 * 积分扣减功能键（写入 legacy {@code credit_transactions.feature}）。前 6 个与 legacy 完全一致；
 * {@link #INTELLIGENCE_SMOKE} 是 Java 侧新增键，用于冒烟端点。
 *
 * <p>legacy 侧 {@code feature} 是无 CHECK 约束的 {@code text} 列，单次扣减恒为 1 积分
 * （{@code balance - 1}），因此新增键不需要 migration。
 */
public enum CreditFeature {
    VIDEO_ANALYSIS("video_analysis"),
    IMAGE_ANALYSIS("image_analysis"),
    ARTICLE_GENERATION("article_generation"),
    COMEDY_GENERATION("comedy_generation"),
    VIDEO_PRODUCTION_SCRIPT("video_production_script"),
    VIDEO_PRODUCTION_VIDEO("video_production_video"),
    /** 控制面 text run（GL-P3-AI-001）：经 AiExecutionService 的平台模型 text 能力调用，按平台口径扣 1 积分；BYOK run 不扣。 */
    AI_RUN_TEXT("ai_run_text"),
    /** 平台语音识别按真实秒数/token 成本结算；Sandbox 0 成本不扣。 */
    AI_RUN_VOICE("ai_run_voice"),
    /** 平台 Embedding 按真实 input token 成本结算；Sandbox 0 成本不扣。 */
    AI_RUN_EMBEDDING("ai_run_embedding"),
    /** 冒烟端点：真实消耗 Qwen 上游，与其它 AI 调用同等扣分（GL-P0-SEC-002，原先免费）。 */
    INTELLIGENCE_SMOKE("intelligence_smoke"),
    /** 智能创作助手（PRD §4.9）：内容评分 / 优化建议 / 问答引导等 AI 诊断调用，单次扣 1 积分。 */
    CREATION_ASSISTANT("creation_assistant"),
    /** 朋友圈内容生成（PRD §4.4 图片+文字）：一次多模态调用产出精简文案 + 图片顺序建议 + 每图配文，单次扣 1 积分。 */
    MOMENTS_GENERATION("moments_generation"),
    /** 视频工坊 BGM 节奏建议（任务书 #43 D6）：文本类 AI 建议，单次扣 1 积分，与 image_analysis/creation_assistant 同口径。 */
    VIDEO_STUDIO_BGM("video_studio_bgm"),
    /** 系列图卡卡片计划（任务书 #54 C 决策）：一次文本调用产出结构化拆卡计划，单次扣 1 积分；逐卡生图走 image_generation cents 预算口径。 */
    CARD_SERIES_PLAN("card_series_plan"),;

    private final String key;

    CreditFeature(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static CreditFeature fromKey(String key) {
        for (CreditFeature feature : values()) {
            if (feature.key.equals(key)) {
                return feature;
            }
        }
        throw new IllegalArgumentException("未知积分功能键: " + key);
    }
}
