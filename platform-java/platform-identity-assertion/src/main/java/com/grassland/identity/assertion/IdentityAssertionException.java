package com.grassland.identity.assertion;

/**
 * 断言编解码/签名失败（payload 损坏、JSON 解析失败、HMAC 计算异常等）。
 *
 * <p>验签路径（{@link IdentityAssertionSigner#verify}）<b>不抛</b>本异常——任何失败一律返回 {@code Optional.empty()}，
 * 调用方据此回退到 cookie 鉴权（降级而非宕机）。本异常仅用于签发路径（secret 未配置）等编程错误。
 */
public class IdentityAssertionException extends RuntimeException {

    public IdentityAssertionException(String message) {
        super(message);
    }

    public IdentityAssertionException(String message, Throwable cause) {
        super(message, cause);
    }
}
