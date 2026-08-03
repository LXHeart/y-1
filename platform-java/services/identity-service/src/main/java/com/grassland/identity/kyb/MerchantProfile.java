package com.grassland.identity.kyb;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 商家主体详细资料（KYB）。GL-P3-MERCHANT-001。
 *
 * <p>镜像 {@code merchant_profile} 表全字段。{@code status} 存 DB 小写字符串（按需用 {@link MerchantProfileStatus} 转枚举）。
 */
public record MerchantProfile(
        String organizationId,
        String legalName,
        String unifiedSocialCreditCode,
        String businessType,
        String legalPersonName,
        String legalPersonIdNumber,
        Long registeredCapitalCents,
        LocalDate establishmentDate,
        String businessAddress,               // JSONB: {province,city,district,address,longitude,latitude}
        String contactPhone,
        String contactEmail,
        String status,                        // draft/pending/under_review/approved/rejected
        Instant submittedAt,
        Instant reviewedAt,
        String reviewerAccountId,
        String reviewNote,
        Instant createdAt,
        Instant updatedAt
) {}
