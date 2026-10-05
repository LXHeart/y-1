package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.AnalyticsAdvice;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Pure task read-model projections shared by the query and command HTTP
 * adapters.
 */
public final class TaskBodies {
	private TaskBodies() {
	}

	static Map<String, Object> toBody(Task task) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", task.id());
		m.put("ownerAccountId", task.ownerAccountId());
		m.put("organizationId", task.organizationId());
		if (task.storeId() != null) {
			m.put("storeId", task.storeId());
		}
		m.put("title", task.title());
		m.put("description", task.description());
		m.put("status", task.status());
		m.put("contentForm", task.contentForm());
		m.put("platform", task.platform());
		m.put("maxSlots", task.maxSlots());
		m.put("bountyCents", task.bountyCents());
		m.put("freebieDepositCents", task.freebieDepositCents());
		// 任务书 #62：仅在有目标问题时出现（缺省不出字段，旧前端零影响）
		if (task.question().present()) {
			m.put("questionText", task.question().text());
			if (task.question().ref() != null) {
				m.put("questionRef", task.question().ref());
			}
		}
		m.put("minRecommenderLevel", task.minRecommenderLevel());
		m.put("requirements", task.requirements());
		// 任务书 #96 C96-04：发布合同字段（预览页/表单回显消费）
		m.put("reviewRequired", task.requiresReview());
		m.put("deliveryDeadlineDays", task.deliveryDeadlineDays());
		m.put("cancelPolicy",
				task.cancelPolicyJson() == null ? null : ApplicationBodies.parsedJson(task.cancelPolicyJson()));
		// 任务书 #75：套餐推广任务标识（前端据此渲染「套餐推广」badge 与套餐摘要行）。
		if (task.commercePackageId() != null) {
			m.put("commercePackageId", task.commercePackageId());
		}
		m.put("version", task.version());
		m.put("applicationDeadline", task.applicationDeadline() == null ? null : task.applicationDeadline().toString());
		m.put("autoAcceptMinLevel", task.autoAcceptMinLevel());
		// 任务书 #53：审核视图字段仅 rejected 视图（最新决定为驳回的 draft）有值，其余路径恒 null。
		m.put("lastReviewAction", task.lastReviewAction());
		m.put("lastReviewNote", task.lastReviewNote());
		m.put("lastReviewedAt", task.lastReviewAt() == null ? null : task.lastReviewAt().toString());
		// 商家端驳回回显：仅当任务仍 draft 且最新一条审核记录为 rejected 时非 null，
		// 避免已上架任务泄漏历史驳回（重新提交/通过后不再显示）。
		boolean showRejected = TaskStatus.DRAFT.dbValue().equals(task.status())
				&& "rejected".equals(task.lastReviewAction());
		m.put("lastRejectedNote", showRejected ? task.lastReviewNote() : null);
		m.put("lastRejectedAt", showRejected && task.lastReviewAt() != null ? task.lastReviewAt().toString() : null);
		m.put("publishedAt", task.publishedAt() == null ? null : task.publishedAt().toString());
		m.put("cancelledAt", task.cancelledAt() == null ? null : task.cancelledAt().toString());
		m.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
		return m;
	}

	static Map<String, Object> progressBody(Task task, TaskProgress facts) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("totalApplications", facts.totalApplications());
		body.put("pendingApplications", facts.pendingApplications());
		body.put("reservingApplications", facts.reservingApplications());
		body.put("acceptedApplications", facts.acceptedApplications());
		// PRD §2.3：已报名成功人数（accepted + reserving）——前端据此禁用「编辑」并给行内原因。
		body.put("acceptedApplicationCount", facts.acceptedApplications() + facts.reservingApplications());
		body.put("rejectedApplications", facts.rejectedApplications());
		body.put("withdrawnApplications", facts.withdrawnApplications());
		body.put("refundedApplications", facts.refundedApplications());
		body.put("occupiedSlots", facts.occupiedSlots());
		body.put("maxSlots", task.maxSlots());
		body.put("remainingSlots",
				task.maxSlots() == null ? null : Math.max(0, task.maxSlots() - facts.occupiedSlots()));
		body.put("submittedDeliverables", facts.submittedDeliverables());
		body.put("confirmedDeliverables", facts.confirmedDeliverables());
		body.put("settledEngagements", facts.settledEngagements());
		body.put("reservedBountyCents", facts.reservedBountyCents());
		body.put("settledBountyCents", facts.settledBountyCents());
		return body;
	}

	static Map<String, Object> dashboardBody(MerchantDashboard dashboard, BusinessReport report) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("organizationId", dashboard.organizationId());
		body.put("storeId", dashboard.storeId());
		body.put("taskCount", dashboard.taskCount());
		body.put("publishedTaskCount", dashboard.publishedTaskCount());
		body.put("totalApplications", dashboard.totalApplications());
		body.put("acceptedApplications", dashboard.acceptedApplications());
		body.put("confirmedDeliverables", dashboard.confirmedDeliverables());
		body.put("settledEngagements", dashboard.settledEngagements());
		body.put("reservedBountyCents", dashboard.reservedBountyCents());
		body.put("settledBountyCents", dashboard.settledBountyCents());
		body.put("applicationAcceptanceRate", dashboard.applicationAcceptanceRate());
		body.put("averageRating", dashboard.averageRating());
		var attribution = report.attribution();
		Map<String, Object> marketing = new LinkedHashMap<>();
		marketing.put("exposureCollected", attribution.exposures() > 0);
		marketing.put("interactionCollected", attribution.interactions() > 0);
		marketing.put("conversionCollected", attribution.conversions() > 0);
		marketing.put("exposures", attribution.exposures());
		marketing.put("interactions", attribution.interactions());
		marketing.put("conversions", attribution.conversions());
		marketing.put("attributedRevenueCents", attribution.attributedRevenueCents());
		marketing.put("attributedRefundCents", attribution.attributedRefundCents());
		marketing.put("dataQuality", attribution.dataQuality());
		marketing.put("status", attribution.status());
		marketing.put("roi", attribution.roi() == null ? "unavailable" : attribution.roi());
		marketing.put("roiFormula", "(attributedRevenue-attributedRefund-settledBounty)/settledBounty");
		body.put("marketingMetrics", marketing);
		var guidance = AnalyticsAdvice.evaluate(report);
		body.put("advice", guidance.advice());
		body.put("alerts", guidance.alerts());
		body.put("businessMetrics",
				Map.of("orders", report.orders(), "paidOrders", report.paidOrders(), "redeemedOrders",
						report.redeemedOrders(), "refundedOrders", report.refundedOrders(), "grossGmvCents",
						report.grossGmvCents(), "refundedGmvCents", report.refundedGmvCents(), "netGmvCents",
						report.netGmvCents(), "merchantRevenueCents", report.merchantRevenueCents(), "platformFeeCents",
						report.platformFeeCents(), "recommenderRevenueCents", report.recommenderRevenueCents()));
		return body;
	}

	static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	static String searchQuery(String value) {
		String query = blankToNull(value == null ? null : value.trim());
		if (query == null)
			return null;
		if (query.length() > 100)
			throw new MarketplaceException(400, "q 最长 100 字符");
		return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	static <T> Mono<T> fail(int status, String message) {
		return Mono.error(new MarketplaceException(status, message));
	}

	static Map<String, Object> cancelBody(TaskCancellationService.Result result) {
		Map<String, Object> body = toBody(result.task());
		body.put("pendingCancelled", result.pendingCancelled());
		body.put("refundedCount", result.refundedCount());
		body.put("compensationPending", result.compensationPending());
		body.put("settledWithCompensation", result.settledWithCompensation());
		return body;
	}

}
