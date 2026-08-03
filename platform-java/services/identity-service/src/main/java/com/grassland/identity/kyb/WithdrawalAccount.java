package com.grassland.identity.kyb;

import java.time.Instant;
import java.util.UUID;

/**
 * 商家收款账户。GL-P3-MERCHANT-001。
 *
 * <p>镜像 {@code withdrawal_account} 表全字段。{@code accountType}/{@code status} 存 DB 小写字符串（按需转枚举）。
 */
public record WithdrawalAccount(
        UUID id,
        String organizationId,
        String accountType,                   // bank_card / alipay / wechat
        String accountName,
        String accountNumberEncrypted,        // 加密存储
        String bankName,
        String branchName,
        boolean isDefault,
        String status,                        // pending/under_review/approved/rejected
        Instant submittedAt,
        Instant reviewedAt,
        String reviewerAccountId,
        String reviewNote,
        Instant createdAt,
        Instant updatedAt
) {}
