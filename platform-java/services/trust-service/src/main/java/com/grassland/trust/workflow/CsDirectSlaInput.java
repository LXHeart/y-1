package com.grassland.trust.workflow;

/**
 * 客服直裁 SLA workflow 入参（任务书 #74 卡 A）。{@code slaSeconds} 由 starter 从
 * {@code AdjudicationProperties.csDirectSlaSecondsEffective()} 折算，workflow 内不读 env。
 */
public record CsDirectSlaInput(String disputeId, long slaSeconds) {
}
