package com.grassland.marketplace.workflow;

/**
 * intelligence media 中转调用瞬态失败（草场 Slice 11 Stage 2）。metadata/download-url 遇非预期 HTTP 状态
 * （非 200/404）时抛出，由 controller 映射为 5xx。media 不存在/不可用（purpose 不符/非活跃/过期/已删）
 * 是 intelligence 的正常 404，被 {@link IntelligenceMediaClient} 映射为 {@code Mono.empty()}，不抛此异常。
 */
public class IntelligenceMediaException extends RuntimeException {

    public IntelligenceMediaException(String message) {
        super(message);
    }
}
