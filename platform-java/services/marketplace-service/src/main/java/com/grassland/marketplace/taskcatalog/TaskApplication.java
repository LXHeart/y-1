package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 推荐官报名记录（application 聚合，HLD 5.3）。草场 Epic 4 Slice 4B。
 *
 * <p>{@code recommenderAccountId} = 报名者（断言 caller，recommender）；{@code taskId} 同库真 FK 引用 {@link Task}；
 * {@code status} 存小写 String（house style，见 {@link ApplicationStatus}）；{@code reviewedByAccountId} 为
 * accept/reject 的操作商家（caller，withdraw 时 null）；{@code decidedAt} 为 accept/reject 时间。
 *
 * <p>{@code bountyCents} = <b>accept 时冻结的赏金快照</b>（GL-P1-TASK-001：snapshot-pinning）。accept/结算读这列
 * 而非可变 {@code task.bounty_cents}——否则 accept 后改 task 赏金（全字段 revise）会让结算读到新值、走错 fund 分支。
 * 非 fund 任务（accept 时 bounty=0）这列为 0。pending 报名也带这列（create 时取 task 当前赏金），但只有在 accept
 * 落库时才「冻结」语义成立（pending 期间 task 赏金变会经 create 重新取值，不影响已 accept 的行）。
 *
 * <p>任务书 #96 C96-01（D96-01/D96-07）：{@code deliveryDeadlineAt}/{@code remedyDeadlineAt} = accept 时快照的
 * 交付截止与补救窗截止（NULL = 存量行/套餐推广/旧政策行，不受期限规则约束）；{@code engagementPolicyVersion} 非空
 * 才受交付期限/退出/终结规则管辖；{@code exitedAt}/{@code exitKind} 记退出或终结事实
 * （no_fault/negotiated/timeout）；{@code deliveryWorkflowStartedAt} 为交付看门狗 Temporal workflow 的派发标记
 * （延期批准时清空待重派）。估算展示列不作判定依据（同 {@code merchantConfirmDeadlineAt} 惯例）。
 */
public record TaskApplication(
        String id,
        String taskId,
        String recommenderAccountId,
        String status,
        String note,
        String reviewedByAccountId,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        long bountyCents,
        /** 商家确认窗口截止（D-03）：推荐官提交履约时设 = now + 窗口；null = 未进窗口。供轮询/UI 倒计时；
         *  真正到期由 Temporal ConfirmationWindowWorkflow Timer 驱动，此列是估算展示值，不作判定依据。 */
        Instant merchantConfirmDeadlineAt,
        /** D-03 自动确认时刻。仅窗口到期 activity 写；用于区别商家手动确认并支撑 Temporal activity 崩溃重试。 */
        Instant autoConfirmedAt,
        /** D-03 slice 2：商家对系统核实通过履约发起异议的时刻。contest 同时设 confirmed_at，供 reconciliation 落钱。 */
        Instant merchantRejectedAt,
        /** 商家拒绝理由（客服裁定证据摘要）。 */
        String rejectionReason,
        /** trust merchant_rejection dispute id（轮询/客服案件定位）。 */
        String merchantRejectionDisputeId,
        /** F6：商家 contest 的本地 durable claim。一旦存在，手动确认与 Timer auto-confirm 均不得再 capture。 */
        Instant contestRequestedAt,
        /** 客服 SLA workflow 已成功启动（确定性 workflow id；dispatcher 可补启）。 */
        Instant rejectionWorkflowStartedAt,
        Integer reputationLevelAtAccept,
        Long reputationPolicyVersionAtAccept,
        Integer settlementDelayDaysAtAccept,
        Integer commissionBonusBpsAtAccept,
        Boolean premiumSupportAtAccept,
        /** D-02：商家手动确认时申报的阶梯指标达成值（与 confirmed_at 同一 guarded UPDATE 冻结）；自动确认保持 null → 结算 hold。 */
        Long confirmedMetricValue,
        /** ADR-D12：accept 时冻结的霸王餐押金快照（镜像 bounty 快照；非押金任务为 0）。结算/取消按它分支资金方向。 */
        long freebieDepositCents,
        /** #96 C96-01：accept 快照的交付截止（NULL = 不受期限规则约束）。 */
        Instant deliveryDeadlineAt,
        /** #96 C96-01：交付截止 + 补救窗的终结线（与 deliveryDeadlineAt 成对快照）。 */
        Instant remedyDeadlineAt,
        /** 交付看门狗 workflow 派发标记；延期批准时清空触发重派。 */
        Instant deliveryWorkflowStartedAt,
        /** 退出/终结时刻（no_fault 退出、协商退出、超时终结共用）。 */
        Instant exitedAt,
        /** 退出/终结形态：no_fault / negotiated / timeout。 */
        String exitKind,
        /** 履约政策版本（D96-07）：非空才受期限/退出/终结规则约束；历史行 NULL 零回填。 */
        Integer engagementPolicyVersion
) {
    /** 兼容 #96 之前的全参构造调用方（既有测试）；期限/退出/政策字段为空（旧政策豁免，D96-07）。 */
    public TaskApplication(
            String id, String taskId, String recommenderAccountId, String status, String note,
            String reviewedByAccountId, Instant decidedAt, Instant createdAt, Instant updatedAt,
            Instant confirmedAt, long bountyCents, Instant merchantConfirmDeadlineAt, Instant autoConfirmedAt,
            Instant merchantRejectedAt, String rejectionReason, String merchantRejectionDisputeId,
            Instant contestRequestedAt, Instant rejectionWorkflowStartedAt,
            Integer reputationLevelAtAccept, Long reputationPolicyVersionAtAccept,
            Integer settlementDelayDaysAtAccept, Integer commissionBonusBpsAtAccept,
            Boolean premiumSupportAtAccept, Long confirmedMetricValue, long freebieDepositCents) {
        this(id, taskId, recommenderAccountId, status, note, reviewedByAccountId, decidedAt, createdAt, updatedAt,
                confirmedAt, bountyCents, merchantConfirmDeadlineAt, autoConfirmedAt, merchantRejectedAt,
                rejectionReason, merchantRejectionDisputeId, contestRequestedAt, rejectionWorkflowStartedAt,
                reputationLevelAtAccept, reputationPolicyVersionAtAccept, settlementDelayDaysAtAccept,
                commissionBonusBpsAtAccept, premiumSupportAtAccept, confirmedMetricValue, freebieDepositCents,
                null, null, null, null, null, null);
    }

    /** 是否霸王餐押金履约（ADR-D12：按冻结快照判资金方向，不读可变 task 行）。 */
    public boolean isFreebie() {
        return freebieDepositCents > 0;
    }

    /** #96 C96-01：是否受履约期限/退出/终结规则管辖（D96-07：仅政策版本落地后接受的报名）。 */
    public boolean underDeliveryPolicy() {
        return engagementPolicyVersion != null;
    }

    /** 兼容 V40（freebie_deposit_cents）之前的全参构造调用方（既有测试）；押金为 0。 */
    public TaskApplication(
            String id, String taskId, String recommenderAccountId, String status, String note,
            String reviewedByAccountId, Instant decidedAt, Instant createdAt, Instant updatedAt,
            Instant confirmedAt, long bountyCents, Instant merchantConfirmDeadlineAt, Instant autoConfirmedAt,
            Instant merchantRejectedAt, String rejectionReason, String merchantRejectionDisputeId,
            Instant contestRequestedAt, Instant rejectionWorkflowStartedAt,
            Integer reputationLevelAtAccept, Long reputationPolicyVersionAtAccept,
            Integer settlementDelayDaysAtAccept, Integer commissionBonusBpsAtAccept,
            Boolean premiumSupportAtAccept, Long confirmedMetricValue) {
        this(id, taskId, recommenderAccountId, status, note, reviewedByAccountId, decidedAt, createdAt, updatedAt,
                confirmedAt, bountyCents, merchantConfirmDeadlineAt, autoConfirmedAt, merchantRejectedAt,
                rejectionReason, merchantRejectionDisputeId, contestRequestedAt, rejectionWorkflowStartedAt,
                reputationLevelAtAccept, reputationPolicyVersionAtAccept, settlementDelayDaysAtAccept,
                commissionBonusBpsAtAccept, premiumSupportAtAccept, confirmedMetricValue, 0L);
    }

    /** 兼容 V32（confirmed_metric_value）之前的全参构造调用方（既有测试）；申报指标值为空。 */
    public TaskApplication(
            String id, String taskId, String recommenderAccountId, String status, String note,
            String reviewedByAccountId, Instant decidedAt, Instant createdAt, Instant updatedAt,
            Instant confirmedAt, long bountyCents, Instant merchantConfirmDeadlineAt, Instant autoConfirmedAt,
            Instant merchantRejectedAt, String rejectionReason, String merchantRejectionDisputeId,
            Instant contestRequestedAt, Instant rejectionWorkflowStartedAt,
            Integer reputationLevelAtAccept, Long reputationPolicyVersionAtAccept,
            Integer settlementDelayDaysAtAccept, Integer commissionBonusBpsAtAccept,
            Boolean premiumSupportAtAccept) {
        this(id, taskId, recommenderAccountId, status, note, reviewedByAccountId, decidedAt, createdAt, updatedAt,
                confirmedAt, bountyCents, merchantConfirmDeadlineAt, autoConfirmedAt, merchantRejectedAt,
                rejectionReason, merchantRejectionDisputeId, contestRequestedAt, rejectionWorkflowStartedAt,
                reputationLevelAtAccept, reputationPolicyVersionAtAccept, settlementDelayDaysAtAccept,
                commissionBonusBpsAtAccept, premiumSupportAtAccept, null);
    }

    /** 兼容既有测试/调用方的 D-03 core slice 构造器；未发生商家异议时新增字段均为空。 */
    public TaskApplication(
            String id, String taskId, String recommenderAccountId, String status, String note,
            String reviewedByAccountId, Instant decidedAt, Instant createdAt, Instant updatedAt,
            Instant confirmedAt, long bountyCents, Instant merchantConfirmDeadlineAt, Instant autoConfirmedAt) {
        this(id, taskId, recommenderAccountId, status, note, reviewedByAccountId, decidedAt, createdAt, updatedAt,
                confirmedAt, bountyCents, merchantConfirmDeadlineAt, autoConfirmedAt, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    public TaskApplication(
            String id, String taskId, String recommenderAccountId, String status, String note,
            String reviewedByAccountId, Instant decidedAt, Instant createdAt, Instant updatedAt,
            Instant confirmedAt, long bountyCents, Instant merchantConfirmDeadlineAt, Instant autoConfirmedAt,
            Instant merchantRejectedAt, String rejectionReason, String merchantRejectionDisputeId,
            Instant contestRequestedAt, Instant rejectionWorkflowStartedAt) {
        this(id, taskId, recommenderAccountId, status, note, reviewedByAccountId, decidedAt, createdAt, updatedAt,
                confirmedAt, bountyCents, merchantConfirmDeadlineAt, autoConfirmedAt, merchantRejectedAt,
                rejectionReason, merchantRejectionDisputeId, contestRequestedAt, rejectionWorkflowStartedAt,
                null, null, null, null, null, null);
    }
}
