package com.grassland.finance.security;

/**
 * finance 域错误（仿 marketplace 的 MarketplaceException）：HTTP 状态码 + 中文消息。
 * controller 经 {@code @RestControllerAdvice FinanceErrorHandler} 转 {@code {success:false,error}} JSON。
 */
public class FinanceException extends RuntimeException {

    private final int status;

    public FinanceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
