package com.grassland.marketplace.security;

/**
 * marketplace 域错误（仿 identity 的 IdentityException）：HTTP 状态码 + 中文消息。
 * controller 经 {@code @ExceptionHandler} 转 {@code {success:false,error}} JSON（与 legacy 兼容格式一致）。
 */
public class MarketplaceException extends RuntimeException {

    private final int status;

    public MarketplaceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
