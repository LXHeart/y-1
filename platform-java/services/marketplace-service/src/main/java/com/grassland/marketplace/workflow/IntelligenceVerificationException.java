package com.grassland.marketplace.workflow;

/**
 * intelligence 履约 AI 视觉核验跨服务调用瞬态失败（草场 Slice 11 Verification Stage 4）。
 *
 * <p>{@link IntelligenceVerificationClient#analyze} 遇非 200 状态（4xx/5xx、intelligence 不可用）时抛出。
 * marketplace 的核验编排把它降级为单项 {@code ai_visual} check 的 {@code inconclusive}——不拖垮整次履约核验
 * （链接可达性仍独立给出结论），故此异常不映射给终端用户。
 */
public class IntelligenceVerificationException extends RuntimeException {

    public IntelligenceVerificationException(String message) {
        super(message);
    }
}
