package com.grassland.intelligence.security;

/**
 * intelligence 域错误（仿 marketplace 的 MarketplaceException）：HTTP 状态码 + 中文消息。
 * controller 经 {@code @ExceptionHandler} 转 {@code {success:false,error}} JSON（与 legacy 兼容格式一致）。
 */
public class IntelligenceException extends RuntimeException {

    private final int status;
    private final String code;

    public IntelligenceException(int status, String message) {
        this(status, null, message);
    }

    public IntelligenceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
