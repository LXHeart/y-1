package com.grassland.trust.adjudication;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审判 adjudication 可配策略（草场 Epic 6 Slice 6C / HLD §5.5、§9.3、§10.5）。
 *
 * <p>HLD 把「完整审判」列为 90 天非目标、客服手动裁决为生产兜底（§15.2），D-06/D-10 为 DECISION REQUIRED。
 * 以下阈值是 <b>sandbox/dev 默认</b>，逐条标注 provisional：待 HLD 终审后定稿。
 *
 * <ul>
 *   <li>{@code panelSize} — 审判面板人数（默认 7；7 官多数决需 ≥4 同方）。</li>
 *   <li>{@code voteWindowHours} — 每轮投票窗口（默认 24h；dev/test 经 env 缩短）。</li>
 *   <li>{@code maxRounds} — 平票重开上限（默认 2；超限转客服兜底）。</li>
 *   <li>{@code appealWindowHours} — 判决后上诉窗口（默认 48h；过期平淡 → 终局）。</li>
 *   <li>{@code judgeEligibilityTier} — 审判官资格等级阈值（声誉模块未建，占位；默认 1 = 全部 active 审判官）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "trust.adjudication")
public record AdjudicationProperties(
        int panelSize,
        int voteWindowHours,
        int maxRounds,
        int appealWindowHours,
        int judgeEligibilityTier) {

    public AdjudicationProperties {
        if (panelSize <= 0) {
            panelSize = 7;
        }
        if (voteWindowHours <= 0) {
            voteWindowHours = 24;
        }
        if (maxRounds <= 0) {
            maxRounds = 2;
        }
        if (appealWindowHours <= 0) {
            appealWindowHours = 48;
        }
        if (judgeEligibilityTier <= 0) {
            judgeEligibilityTier = 1;
        }
    }
}
