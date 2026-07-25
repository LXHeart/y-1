package com.grassland.trust.security;

/**
 * trust 域错误（草场 Epic 6 Slice 6A，仿 finance 的 FinanceException）：HTTP 状态码 + 中文消息。
 * controller 经 {@code @RestControllerAdvice TrustErrorHandler} 转 {@code {success:false,error}} JSON。
 */
public class TrustException extends RuntimeException {

    private final int status;

    public TrustException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
