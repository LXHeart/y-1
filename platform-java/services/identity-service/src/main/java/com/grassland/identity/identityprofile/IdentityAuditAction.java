package com.grassland.identity.identityprofile;

/**
 * 身份审计动作。草场身份域 Slice 2I（HLD 10.1「更新活动身份和审计」）。
 *
 * <p>
 * DB 存小写 dbValue。开通身份（open）走 outbox {@code IdentityOpened}，不在此审计；本枚举覆盖活动身份生命周期。
 */
public enum IdentityAuditAction {
	ACTIVATE("activate"), DEACTIVATE("deactivate"), REVOKE_SESSION("revoke_session"),
	/** 并发活动身份超限后，系统将最旧设备切回消费者；登录会话仍保留。 */
	POLICY_DEACTIVATE("policy_deactivate"),
	/** 移动端 refresh token 撤销（/api/auth/revoke，GL-P3-IDENTITY-001）。 */
	TOKEN_REVOKE("token_revoke"),
	/** 移动端设备撤销（DELETE /api/me/devices/{id}，GL-P3-IDENTITY-001）。 */
	DEVICE_REVOKE("device_revoke"),
	/**
	 * 跨应用一次性免登 token 签发（POST /api/auth/cross-app-tokens，任务书 #76 卡 A）。审计禁止落 token
	 * 明文。
	 */
	CROSS_APP_TOKEN_ISSUE("cross_app_token_issue"),
	/**
	 * 跨应用一次性免登 token 核销（POST /api/auth/cross-app-tokens/exchange）；session_token
	 * 列落新会话 sid。
	 */
	CROSS_APP_TOKEN_EXCHANGE("cross_app_token_exchange");

	private final String dbValue;

	IdentityAuditAction(String dbValue) {
		this.dbValue = dbValue;
	}

	public String dbValue() {
		return dbValue;
	}

	/** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
	public static IdentityAuditAction fromDb(String value) {
		if (value == null) {
			throw new IllegalArgumentException("identity audit action is null");
		}
		String normalized = value.trim().toLowerCase();
		for (IdentityAuditAction action : values()) {
			if (action.dbValue.equals(normalized)) {
				return action;
			}
		}
		throw new IllegalArgumentException("unknown identity audit action: " + value);
	}
}
