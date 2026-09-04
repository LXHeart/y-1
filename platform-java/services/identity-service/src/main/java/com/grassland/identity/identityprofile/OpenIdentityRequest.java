package com.grassland.identity.identityprofile;

/**
 * 开通身份的请求体。草场身份域 Slice 2G；2026-09-04 身份模型改版后仅服务「存量裸账号补开推荐官」。
 *
 * <p>
 * {@code type} 必须是 {@link IdentityType} 合法值（merchant/recommender），compact
 * constructor 内校验。 {@code organizationId} 恒忽略（merchant 一律 403；recommender
 * 档案不挂主体），仅为旧客户端兼容保留字段。
 */
public record OpenIdentityRequest(String type, String organizationId) {
	public OpenIdentityRequest {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("type is required");
		}
		IdentityType.fromDb(type); // 校验为已知身份类型，非法抛 IllegalArgumentException
	}
}
