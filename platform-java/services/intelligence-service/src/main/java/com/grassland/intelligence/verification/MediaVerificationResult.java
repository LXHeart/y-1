package com.grassland.intelligence.verification;

import java.util.UUID;

/**
 * 单张附件的 AI 视觉核验结果（草场 Slice 11 Verification Stage 3）。
 *
 * <p>{@code status} 为 tri-state（与履约核验聚合态同词表）：
 * <ul>
 *   <li>{@code passed} — 截图明显是真实、与任务相关的平台内容证据。</li>
 *   <li>{@code failed} — 截图明显造假、无关或张冠李戴。</li>
 *   <li>{@code inconclusive} — 信息不足/画面模糊/AI 或存储暂不可用，无法判定。</li>
 * </ul>
 * {@code detail} 为面向商家的简短中文理由，可空。
 *
 * @param mediaId 附件 media_reference id
 * @param status  tri-state（passed / failed / inconclusive）
 * @param detail  简短理由，可空
 */
public record MediaVerificationResult(UUID mediaId, String status, String detail) {
}
