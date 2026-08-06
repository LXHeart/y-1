package com.grassland.identity.recommenderprofile;

import java.time.Instant;
import java.util.UUID;

/**
 * 推荐官平台认证审核申请（GL-P2-ADMIN-002）。
 *
 * <p>克隆 KYB 范式：{@code pending → approved/rejected}。推荐官身份仍是自助开通，
 * 本表承载可选的平台认证审核流（approved 获得认证徽标）。
 */
public record RecommenderVerificationRequest(
        UUID id,
        String accountId,
        String materials,
        String status,
        String reviewerAccountId,
        String reviewNote,
        Instant reviewDeadline,
        Instant createdAt,
        Instant updatedAt) {
}
