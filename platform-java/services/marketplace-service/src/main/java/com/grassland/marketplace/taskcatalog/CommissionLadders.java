package com.grassland.marketplace.taskcatalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * D-02：从 accept 时冻结的 {@code task_application.task_context_snapshot} 解析阶梯佣金策略。
 *
 * <p>快照由 V27 触发器在 pending/reserving → accepted 时从 {@code task_version.requirements} 冻结，
 * 此后任务行修订不影响已接受报名——结算读快照而非可变 task 行。无 ladder（固定佣金契约）返回 null；
 * 快照损坏或策略非法抛 {@link IllegalArgumentException}，由调用方转 hold，绝不按预留上限全额捕获。
 */
public final class CommissionLadders {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CommissionLadders() {}

    /** @return 冻结的阶梯策略；null = 固定佣金契约（或无快照的历史行）。 */
    public static CommissionLadder fromTaskContextSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return null;
        }
        JsonNode ladder;
        try {
            ladder = MAPPER.readTree(snapshotJson).path("requirements").path("commissionLadder");
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("任务快照损坏，无法解析阶梯佣金策略", error);
        }
        if (ladder.isMissingNode() || ladder.isNull() || ladder.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(ladder, CommissionLadder.class);
        } catch (JsonProcessingException error) {
            // 记录构造器的校验异常会被 Jackson 包成 ValueInstantiationException——解包保留原始语义。
            if (error.getCause() instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("任务快照中的阶梯佣金策略非法", error);
        }
    }
}
