package com.grassland.marketplace.ops;

/**
 * ops_case 已接入的来源分类（GL-P1-OPS-001 Stage 1）。
 *
 * <p>刻意做成常量而非 enum：{@code source_kind} 是 DB 里的 varchar，Stage 2 会加 {@code dlt_message},
 * 而已登记的历史行不能因为枚举收窄而读不出来。
 *
 * <p><b>不接入</b> Verification {@code inconclusive} 于<b>结算</b>闸门：按设计它<b>永不阻断结算</b>
 * （{@code VerificationChecker} 只有 {@code failed} 阻断），属于「待人工判定」而非「已阻塞」，
 * 混进阻断队列会让运营误以为有资金卡住。它由独立的待判定查询覆盖（Stage 3）。
 * 注意区隔：2026-09-07 业务审查 C02 后，{@code inconclusive} 会阻断<b>自动确认</b>
 * （{@link #AUTO_CONFIRM_HELD}），那是确认侧的人工复核队列，不是资金阻断队列。
 */
public final class OpsCaseSource {

    /**
     * 对账阻断：{@code settlement_reconciliation.status='blocked'}（V8 表已落库），
     * {@code sourceRef} = {@code source_event_id}。reason 形如 {@code finance_blocked}
     * （finance 侧 manual_clawback_required）/ {@code finance_conflict} / {@code finance_missing}。
     */
    public static final String SETTLEMENT_BLOCKED = "settlement_blocked";

    /**
     * 结算暂缓：{@code SettlementActivityImpl} 的 hold，{@code sourceRef} = {@code task_application.id}。
     * reason = {@code open_dispute} / {@code verification_failed}。
     * <b>此前只有一条 outbox 事件、无任何持久行</b>，运营无从知道当前有多少笔被暂缓 —— 本 case 是它唯一的可查载体。
     */
    public static final String SETTLEMENT_HELD = "settlement_held";

    /**
     * 商家拒绝系统核实通过的履约（D-03 §2）：直送客服裁定，sourceRef = trust disputeId，
     * applicationId = task_application.id，reason = 商家拒绝理由。涉资金终局，severity=high。
     * 运营队列可直接拿 sourceRef 调 trust final-decision，无需跨服务反查映射。
     */
    public static final String MERCHANT_REJECTION = "merchant_rejection";

    /**
     * 死信消息（Stage 2）：{@code sourceRef} = {@code topic:partition:offset}（Kafka 位点天然幂等，
     * 消费者重启重读同一条不会开出第二张单）。reason = 原 topic。
     */
    public static final String DLT_MESSAGE = "dlt_message";

    /**
     * 自动确认暂缓（业务审查 2026-09-07 C02）：确认窗口到期时该交付物的<b>生效核验结论</b>为
     * {@code failed}/{@code inconclusive}，default-approve 缺乏证据支撑——不自动确认、不结算，
     * 转人工复核（商家仍可手动确认/退回，运营可改判核验 override）。{@code sourceRef} = submissionId，
     * reason = {@code verification_failed} / {@code verification_inconclusive}。
     */
    public static final String AUTO_CONFIRM_HELD = "auto_confirm_held";

    private OpsCaseSource() {
    }

    /** 资金相关阻断/裁定置 high（对账阻断或客服裁定意味着钱停在中间态）。 */
    public static String severityOf(String sourceKind) {
        return SETTLEMENT_BLOCKED.equals(sourceKind) || MERCHANT_REJECTION.equals(sourceKind)
                ? "high" : "normal";
    }
}
