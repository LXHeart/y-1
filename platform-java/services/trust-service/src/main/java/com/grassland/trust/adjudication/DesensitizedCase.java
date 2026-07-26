package com.grassland.trust.adjudication;

import com.grassland.trust.dispute.DisputeCase;

/**
 * 脱敏争议视图（草场 Epic 6 Slice 6C / HLD D-10）——审判官投票时看到的案件，<b>剥离原始 account_id / PII</b>。
 *
 * <p><b>Provisional（D-10 占位）</b>：本轮仅做最小角色标签化（当事人按 {@code openedByRole} 标「商家方 / 推荐官方」，
 * 隐藏账号身份但保留角色上下文供审判官判断）。HLD D-10 完整脱敏规则（数据保留 / 导出 / 区域 / 账号→确定性伪名哈希 /
 * 证据摘要自动生成）为 DECISION REQUIRED，待终审定稿后替换本占位。
 *
 * <p>{@code evidenceSummary} 现为占位文本；真实证据经 {@code evidence_ref} 解析留待 D-10。
 */
public record DesensitizedCase(
        String disputeId,
        String engagementRef,
        String reason,
        String openedByRole,
        String openerLabel,
        String counterpartyLabel,
        String evidenceSummary,
        int round) {

    /** 从争议聚合构建脱敏视图（不接触原始 account_id）。 */
    public static DesensitizedCase from(DisputeCase d) {
        boolean merchantOpened = "merchant".equalsIgnoreCase(d.openedByRole());
        String opener = merchantOpened ? "商家方" : "推荐官方";
        String counterparty = merchantOpened ? "推荐官方" : "商家方";
        return new DesensitizedCase(
                d.id(),
                d.engagementRef(),
                d.reason(),
                d.openedByRole(),
                opener,
                counterparty,
                evidenceSummaryPlaceholder(d),
                d.round());
    }

    /** 证据摘要占位：D-10 完整规则落地前，仅指向 {@code evidence_ref} 句柄（可能为空）。 */
    private static String evidenceSummaryPlaceholder(DisputeCase d) {
        return d.evidenceRef() == null || d.evidenceRef().isBlank()
                ? "（证据摘要待 D-10 脱敏规则终审）"
                : "证据句柄：" + d.evidenceRef();
    }
}
