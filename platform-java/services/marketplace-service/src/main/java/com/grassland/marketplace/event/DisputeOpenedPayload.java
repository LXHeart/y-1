package com.grassland.marketplace.event;

/**
 * trust {@code DisputeOpened} 载荷（草场「争议对方通知」缺口补全）。
 *
 * <p>{@code disputeId}/{@code engagementRef} 必填；{@code openedByAccountId}/{@code openedByRole}
 * 决定对方是谁（merchant 开 → 对方=推荐官；recommender 开 → 对方=任务归属商家）；{@code organizationId} 可选。
 * trust 只存开争议者，对方账号仅经 {@code engagementRef}（= marketplace applicationId）间接引用——
 * marketplace 自有 task+application 两表，在本服务内解析对方，不反向调 trust。
 *
 * <p>{@code kind} 区分普通争议（{@code standard}）与 D-03 商家履约异议（{@code merchant_rejection}）。
 * 后者由本服务的 contest 主动开案、且已在同事务发过 {@code MerchantContested} 通知，故不得再派生
 * {@code EngagementDisputed}（否则推荐官收到两条通知）。trust {@code dispute_case.kind} 有
 * {@code DEFAULT 'standard'}，但**本字段按可选解析**——V5 之前在途的旧事件无此字段，
 * 若按必填会全部进 DLT 且不重投。
 */
public record DisputeOpenedPayload(
        String disputeId,
        String engagementRef,
        String organizationId,
        String openedByAccountId,
        String openedByRole,
        String kind) {

    /** D-03 商家履约异议：marketplace 自己发起、自己已通知，跨服务回环不得重复派生通知。 */
    public static final String KIND_MERCHANT_REJECTION = "merchant_rejection";

    /** 旧事件（trust V5 之前）无 kind → 视作普通争议，保留 Slice 12 的对方通知语义。 */
    public static final String KIND_STANDARD = "standard";

    public boolean isMerchantRejection() {
        return KIND_MERCHANT_REJECTION.equals(kind);
    }
}
