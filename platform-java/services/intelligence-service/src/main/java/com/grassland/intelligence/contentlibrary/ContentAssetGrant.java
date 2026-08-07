package com.grassland.intelligence.contentlibrary;

import java.time.Instant;
import java.util.UUID;

/**
 * 素材授权关系（草场 PRD §4.8 / Slice 14）。镜像 {@code content_asset_grant} 表（V18）。
 *
 * <p>PRD §4.8 商家素材库「商家可以指定哪些素材允许推荐官使用」靠这张表。镜像 media_kyb_retention
 * （V9/V10）的 grant 模式：双期限（lease_until 滚动 / retained_until 绝对），续约用 GREATEST 只前进。
 *
 * <p>Stage 1 仅建表 + record，授权读写逻辑在 Stage 2（商家库）接线。
 *
 * @param assetId          被授权的素材
 * @param grantType        授权类型（recommender_share / org_internal / public）
 * @param granteeAccountId 被授权账号；仅 recommender_share 非 null
 * @param grantedBy        授权人
 * @param grantedAt        授权时间
 * @param leaseUntil       滚动租约到期，可空
 * @param retainedUntil    绝对截止日，可空
 * @param releasedAt       软释放时间，可空（撤销授权）
 */
public record ContentAssetGrant(
        UUID assetId,
        String grantType,
        String granteeAccountId,
        String grantedBy,
        Instant grantedAt,
        Instant leaseUntil,
        Instant retainedUntil,
        Instant releasedAt) {

    /** 授权类型常量（V18 CHECK 约束同款）。 */
    public static final String GRANT_RECOMMENDER_SHARE = "recommender_share";
    public static final String GRANT_ORG_INTERNAL = "org_internal";
    public static final String GRANT_PUBLIC = "public";
}
