package com.grassland.identity.assertion;

import java.time.Instant;

/**
 * 断言重放防护（GL-P0-ASSERT-001，可选）。生产扩副本前必须替换为共享存储（Redis/DB），
 * 归入 {@code GL-P3-PLATFORM-001} 可观测性/连接池/部署统一收口。
 *
 * <p>默认无操作（jti 只签入 payload 不消费）。启用单次消费后，每个 jti 只能通过一次，
 * 重复 token 在 {@link IdentityAssertionSigner#verify} 阶段被拒。
 */
public interface AssertionReplayGuard {

    /**
     * 检查并标记 jti 为已消费（幂等）。
     *
     * @param jti JWT ID（UUID 字符串）
     * @param expiresAt token 过期时刻
     * @return true=首次消费（通过），false=已消费（拒绝重放）
     */
    boolean consumeOnce(String jti, Instant expiresAt);

    /** 无操作实现（默认）。 */
    AssertionReplayGuard NO_OP = (jti, expiresAt) -> true;

    /**
     * 单次消费接口（可选）。
     */
    interface SingleUse extends AssertionReplayGuard {
        /** 本 guard 是否已启用（未启用时等价 NO_OP）。 */
        boolean isEnabled();
    }
}
