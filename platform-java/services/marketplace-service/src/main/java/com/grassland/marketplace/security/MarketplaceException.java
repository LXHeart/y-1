package com.grassland.marketplace.security;

/**
 * marketplace 域错误（仿 identity 的 IdentityException）：HTTP 状态码 + 中文消息。
 * controller 经 {@code @ExceptionHandler} 转 {@code {success:false,error}} JSON（与 legacy 兼容格式一致）。
 */
public class MarketplaceException extends RuntimeException {

    private final int status;
    private final String blockedReason;

    public MarketplaceException(int status, String message) {
        this(status, message, null);
    }

    /**
     * 任务书 #97 D97-01：可机器读的拒绝原因（如 {@code settled_no_refund}）——错误信封在非空时
     * 附加 {@code blockedReason} 键，客户端据此渲染禁用态，不靠文案推断。
     */
    public MarketplaceException(int status, String message, String blockedReason) {
        super(message);
        this.status = status;
        this.blockedReason = blockedReason;
    }

    public int status() {
        return status;
    }

    public String blockedReason() {
        return blockedReason;
    }
}
