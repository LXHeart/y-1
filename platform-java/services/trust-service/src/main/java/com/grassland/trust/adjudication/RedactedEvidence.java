package com.grassland.trust.adjudication;

/**
 * 审判官/客服可见的脱敏证据视图（GL-P2-TRUST-001 T2 / D-10）。
 *
 * <p>剥离 {@code submittedByAccountId}（uploader 身份不进审判视图）。
 * {@code content} 已脱敏：文本证据经 PII 掩码；截图证据只给 {@code media:<id>} 句柄（前端经鉴权链路取图，trust 不回原字节）。
 */
public record RedactedEvidence(String id, String kind, String caption, String content) {
}
