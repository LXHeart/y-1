package com.grassland.identity.brand;

import java.time.Instant;

/**
 * 组织品牌资料（#32）。镜像 {@code organization_brand_profile} 表全字段。
 *
 * <p>独立于 KYB {@code merchant_profile}（品牌营销信息可随时编辑，不受审核门约束）；
 * 字段全可空（资料未填也是合法状态）。{@code industry} 存 {@code Industry} 枚举 dbValue；
 * {@code version} 供乐观锁 CAS（首次创建期望 0）。
 */
public record OrganizationBrandProfile(
        String organizationId,
        String brandName,
        String brandLogoMediaReferenceId,
        String description,
        String industry,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
