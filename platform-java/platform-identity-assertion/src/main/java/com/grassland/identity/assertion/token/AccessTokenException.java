package com.grassland.identity.assertion.token;

/**
 * 移动端 access token 编解码失败（GL-P3-IDENTITY-001）。
 *
 * <p>仅用于 payload 编解码层面的硬失败；验签失败统一返回 {@link java.util.Optional#empty()}（同
 * {@link com.grassland.identity.assertion.IdentityAssertionSigner} 的约定），不抛异常。
 */
public class AccessTokenException extends RuntimeException {

    public AccessTokenException(String message) {
        super(message);
    }

    public AccessTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
