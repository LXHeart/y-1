package com.grassland.marketplace.commerce;

import java.time.Instant;

/** Immutable commerce read models shared inside marketplace-service. */
public final class CommerceModels {
	private CommerceModels() {
	}

	public record Offer(String id, String organizationId, String storeId, String taskId, String ownerAccountId,
			String status, int currentVersion, Instant createdAt, Instant updatedAt, Instant publishedAt,
			Instant offSaleAt) {
	}

	public record OfferVersion(String id, String packageId, int version, String title, String description,
			long priceCents, int totalStock, Instant fixedRedeemDeadline, Integer validDaysAfterPurchase,
			int recommenderShareBps, int platformFeeBps, int merchantShareBps, String policyVersion, String createdBy,
			Instant createdAt, Long recommenderFixedCents) {

		/** 便捷构造：任务书 #75 之前的签名（固定佣 null = 比例形态）。 */
		public OfferVersion(String id, String packageId, int version, String title, String description, long priceCents,
				int totalStock, Instant fixedRedeemDeadline, Integer validDaysAfterPurchase, int recommenderShareBps,
				int platformFeeBps, int merchantShareBps, String policyVersion, String createdBy, Instant createdAt) {
			this(id, packageId, version, title, description, priceCents, totalStock, fixedRedeemDeadline,
					validDaysAfterPurchase, recommenderShareBps, platformFeeBps, merchantShareBps, policyVersion,
					createdBy, createdAt, null);
		}

		/** 任务书 #75 D2：固定佣形态（recommender_fixed_cents 非空 ⇔ 比例 bps=0 为形式值）。 */
		public boolean isFixedCommission() {
			return recommenderFixedCents != null;
		}
	}

	public record InventorySlot(String id, String packageVersionId, String storeId, Instant slotStart, Instant slotEnd,
			int totalStock, int remainingStock) {
	}

	public record OfferDetail(Offer offer, OfferVersion version, int remainingStock,
			java.util.List<InventorySlot> inventorySlots) {
	}

	public record Order(String id, String consumerAccountId, String organizationId, String storeId, String taskId,
			String packageId, String packageVersionId, int packageVersion, String packageTitle,
			String recommenderAccountId, long priceCents, int recommenderShareBps, int platformFeeBps,
			int merchantShareBps, long recommenderAmountCents, long platformFeeCents, long merchantAmountCents,
			String policyVersion, String status, long refundedAmountCents, Long refundRequestedAmountCents,
			String refundReason, String inventorySlotId, String redeemCodeHash, Instant redeemDeadline,
			Instant paymentDeadline, String paymentOperationId, String refundOperationId, String splitOperationId,
			String providerRef, String lastError, int version, Instant createdAt, Instant paidAt, Instant redeemedAt,
			Instant refundedAt, Instant updatedAt, Instant slotStart, Instant slotEnd, Instant splitEligibleAt,
			Instant splitCompletedAt) {

		/** 便捷构造：任务书 #75 之前的签名（冷静期两列 null）。 */
		public Order(String id, String consumerAccountId, String organizationId, String storeId, String taskId,
				String packageId, String packageVersionId, int packageVersion, String packageTitle,
				String recommenderAccountId, long priceCents, int recommenderShareBps, int platformFeeBps,
				int merchantShareBps, long recommenderAmountCents, long platformFeeCents, long merchantAmountCents,
				String policyVersion, String status, long refundedAmountCents, Long refundRequestedAmountCents,
				String refundReason, String inventorySlotId, String redeemCodeHash, Instant redeemDeadline,
				Instant paymentDeadline, String paymentOperationId, String refundOperationId, String splitOperationId,
				String providerRef, String lastError, int version, Instant createdAt, Instant paidAt,
				Instant redeemedAt, Instant refundedAt, Instant updatedAt, Instant slotStart, Instant slotEnd) {
			this(id, consumerAccountId, organizationId, storeId, taskId, packageId, packageVersionId, packageVersion,
					packageTitle, recommenderAccountId, priceCents, recommenderShareBps, platformFeeBps,
					merchantShareBps, recommenderAmountCents, platformFeeCents, merchantAmountCents, policyVersion,
					status, refundedAmountCents, refundRequestedAmountCents, refundReason, inventorySlotId,
					redeemCodeHash, redeemDeadline, paymentDeadline, paymentOperationId, refundOperationId,
					splitOperationId, providerRef, lastError, version, createdAt, paidAt, redeemedAt, refundedAt,
					updatedAt, slotStart, slotEnd, null, null);
		}
	}

	public record AfterSalesDispute(String id, String orderId, String consumerAccountId, String reason, String status,
			String resolution, Long resolutionAmountCents, String resolutionReason, String refundOperationId,
			Instant createdAt, Instant resolvedAt) {
	}

	public record Review(String id, String orderId, String consumerAccountId, int rating, String comment,
			Instant createdAt) {
	}
}
