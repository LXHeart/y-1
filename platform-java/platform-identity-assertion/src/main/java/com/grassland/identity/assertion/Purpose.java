package com.grassland.identity.assertion;

/**
 * 断言用途密钥绑定（GL-P0-ASSERT-001 分钥）。
 * <ul>
 *   <li>{@code USER} — 终端用户断言（edge-bff 签发，callerKind=user/null）。</li>
 *   <li>{@code SERVICE} — 领域服务间断言（服务现签，callerKind=service + principal）。</li>
 * </ul>
 *
 * <p>绑定目的：
 * <ul>
 *   <li>user 钥不可签 service 断言（防服务冒充终端用户）。</li>
 *   <li>service 钥的 {@code issuer} 必须与 {@code principal} 一致（防冒充别的服务）。</li>
 * </ul>
 */
public enum Purpose {
    /** 终端用户断言（edge-bff → 各领域）。 */
    USER("user"),

    /** 领域服务间断言（marketplace/trust → finance/intelligence/marketplace）。 */
    SERVICE("service");

    private final String value;

    Purpose(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 从断言的 {@code callerKind} 推导用途（服务断言 callerKind=service）。 */
    public static Purpose fromAssertion(boolean isService) {
        return isService ? SERVICE : USER;
    }

    /** 该用途是否要求 assertion.isService() 为 true。 */
    public boolean requiresServiceCaller() {
        return this == SERVICE;
    }
}
