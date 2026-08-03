package com.grassland.identity.kyb;

import java.time.Instant;
import java.util.UUID;

/**
 * KYB 审核申请（统一工作流）。GL-P3-MERCHANT-001。
 *
 * <p>镜像 {@code kyb_verification_request} 表全字段。{@code verificationType}/{@code status} 存 DB 小写字符串（按需转枚举）。
 */
public record KybVerificationRequest(
        UUID id,
        String organizationId,
        String requesterAccountId,
        String verificationType,              // merchant_profile / store_profile / withdrawal_account
        UUID targetId,                        // 审核对象 ID
        String materials,                     // 提交材料引用（merchant_attachment_id 列表，JSONB）
        String status,                        // pending/under_review/approved/rejected
        String reviewerAccountId,
        String reviewNote,
        Instant reviewDeadline,
        Instant createdAt,
        Instant updatedAt
) {}
