package com.grassland.intelligence.ai.run;

import java.time.Instant;
import java.util.UUID;

/**
 * AI Run 记录实体（GL-P3-AI-001 Phase 3）。
 * <p>记录每次 AI 调用的用量、成本和状态。
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
    Instant updatedAt
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
        UUID operationId
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
            "v1",  // priceTableVersion
            operationId,
            null,  // refundOperationId
            null,  // createdAt 由数据库默认
            null   // updatedAt 由数据库默认
        );
    }

    /** 标记为完成（结算）。 */
    public AiRun complete(int actualCents) {
        return new AiRun(
            id,
            organizationId,
            accountId,
            capability,
            provider,
            model,
            runType,
            inputTokens,
            outputTokens,
            imagesGenerated,
            videoSeconds,
            budgetCents,
            actualCents,
            "completed",
            null,
            startedAt,
            Instant.now(),
            priceTableVersion,
            operationId,
            refundOperationId,
            createdAt,
            Instant.now()
        );
    }

    /** 标记为失败。 */
    public AiRun fail(String reason) {
        return new AiRun(
            id,
            organizationId,
            accountId,
            capability,
            provider,
            model,
            runType,
            inputTokens,
            outputTokens,
            imagesGenerated,
            videoSeconds,
            budgetCents,
            null,  // actualCents
            "failed",
            reason,
            startedAt,
            Instant.now(),
            priceTableVersion,
            operationId,
            refundOperationId,
            createdAt,
            Instant.now()
        );
    }

    /** 标记为取消（用户主动 abort，不退预留）。 */
    public AiRun cancel() {
        return new AiRun(
            id,
            organizationId,
            accountId,
            capability,
            provider,
            model,
            runType,
            inputTokens,
            outputTokens,
            imagesGenerated,
            videoSeconds,
            budgetCents,
            null,  // actualCents
            "cancelled",
            "user aborted",
            startedAt,
            Instant.now(),
            priceTableVersion,
            operationId,
            refundOperationId,
            createdAt,
            Instant.now()
        );
    }

    /** 设置退回操作 ID。 */
    public AiRun withRefundOperation(UUID refundOpId) {
        return new AiRun(
            id,
            organizationId,
            accountId,
            capability,
            provider,
            model,
            runType,
            inputTokens,
            outputTokens,
            imagesGenerated,
            videoSeconds,
            budgetCents,
            actualCents,
            status,
            failureReason,
            startedAt,
            completedAt,
            priceTableVersion,
            operationId,
            refundOpId,
            createdAt,
            Instant.now()
        );
    }

    /** 是否已完成（成功或失败）。 */
    public boolean isFinished() {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    /** 是否需要退回预留（失败状态）。 */
    public boolean shouldRefund() {
        return "failed".equals(status);
    }
}
