package com.grassland.marketplace.ops;

import java.time.Instant;

/**
 * 「待人工判定」核验（GL-P1-OPS-001 Stage 3）：自动核验聚合态为 {@code inconclusive} 且交付物仍
 * {@code submitted} 的履约。
 *
 * <p><b>刻意不是 ops_case 的一种来源</b>：{@code inconclusive} 按设计<b>永不阻断结算</b>
 * （{@code VerificationChecker} 只有 {@code failed} 阻断），把它开成处置单会让运营误以为有资金卡住，
 * 也会让每次商家点「重新核验」都产生一张需要双人审批的单。它是**只读观察窗**：运营看到后的动作是
 * 线下联系商家或推荐官，而非在平台上改判 —— 平台侧的决策权仍在商家的 confirm / reject。
 *
 * <p>过滤 {@code status='submitted'} 的理由：商家一旦 confirm/reject，人工判定已经发生，
 * 这条就不该再占运营视野。
 */
public record OpsPendingVerification(
        String verificationId,
        String submissionId,
        String applicationId,
        String taskId,
        String taskTitle,
        String organizationId,
        String recommenderAccountId,
        String contentUrl,
        String checksJson,
        Instant lastCheckedAt,
        Instant submittedAt
) {
}
