package com.grassland.intelligence.ai.run;

import java.time.Instant;
import java.util.UUID;

/**
 * AI Run 记录实体（GL-P3-AI-001 Phase 3 + 控制面闭环）。
 * <p>记录每次 AI 调用的用量、成本、状态和任务上下文快照（TaskContext）。
 *
 * <p>{@code platformModelVersion}/{@code fallbackAuthorized} 连同 {@code priceTableVersion} 在 Run 起始冻结
 * （HLD §6.2「模型必须保存使用时的版本快照」），使每条 Run 可复现、计费口径冻结（D-11）。
 */
public record AiRun(
    UUID id,
    String organizationId,       // 可空；个人用户
    String accountId,            // 执行者
    String capability,           // text/image_generation 等
    String provider,             // qwen/openai-compatible
    String model,                // 使用的模型
    String runType,              // sync/async/sse

    // 用量计量
    Integer inputTokens,
    Integer outputTokens,
    Integer imagesGenerated,
    Integer videoSeconds,

    // 预算与结算
    int budgetCents,             // 预算上限
    Integer actualCents,         // 实际消耗

    // 状态
    String status,               // running/completed/failed/cancelled
    String failureReason,

    // 时间
    Instant startedAt,
    Instant completedAt,

    // 计费相关
    String priceTableVersion,
    UUID operationId,            // 幂等键（credits 预留/退回）
    UUID refundOperationId,      // 退回预留时的 operation ID

    Instant createdAt,
    Instant updatedAt,

    // 任务上下文快照（TaskContext，V8 新增）
    Integer platformModelVersion, // 平台模型配置版本（平台 run 冻结；BYOK run 为 null）
    boolean fallbackAuthorized    // 本次调用是否经授权回退平台（HLD §12.3 审计）
) {
    /** 创建新 Run。 */
    public static AiRun forCreate(
        String organizationId,
        String accountId,
        String capability,
        String provider,
        String model,
        String runType,
        int budgetCents,
        UUID operationId,
        int priceTableVersion,           // 当前价目表版本（首期固定 v1 → 1）
        Integer platformModelVersion,
        boolean fallbackAuthorized
    ) {
        return forCreate(organizationId, accountId, capability, provider, model, runType,
                budgetCents, operationId, priceTableVersion == 1 ? "v1" : "v" + priceTableVersion,
                platformModelVersion, fallbackAuthorized);
    }

    /** 创建使用显式冻结价目版本的 Run，供异步媒体任务使用。 */
    public static AiRun forCreate(
        String organizationId,
        String accountId,
        String capability,
        String provider,
        String model,
        String runType,
        int budgetCents,
        UUID operationId,
        String priceTableVersion,
        Integer platformModelVersion,
        boolean fallbackAuthorized
    ) {
        return new AiRun(
            null,  // id 由数据库生成
            organizationId,
            accountId,
            capability,
            provider,
            model,
            runType,
            null,  // inputTokens
            null,  // outputTokens
            0,     // imagesGenerated
            0,     // videoSeconds
            budgetCents,
            null,  // actualCents
            "running",
            null,  // failureReason
            Instant.now(),
            null,  // completedAt
            priceTableVersion,
            operationId,
            null,  // refundOperationId
            null,  // createdAt 由数据库默认
            null,  // updatedAt 由数据库默认
            platformModelVersion,
            fallbackAuthorized
        );
    }

    /** 是否已完成（成功或失败或取消）。 */
    public boolean isFinished() {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    /** 是否需要退回预留（失败状态）。 */
    public boolean shouldRefund() {
        return "failed".equals(status);
    }
}
