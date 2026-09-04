package com.grassland.identity.auth;

/** Redis nonce 存储不可用：fail-closed（端点映射 503），不降级放行。 */
public class CrossAppTokenStoreException extends RuntimeException {
	public CrossAppTokenStoreException() {
		super("cross-app token store unavailable");
	}
}
