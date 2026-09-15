package com.grassland.finance.commerce;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 消费支付权威事实查询（任务书 #103 C103-06 / §6.3）：只读聚合 payment/refunds/split 与全部
 * allocation，供 marketplace 对账与经营事实投影使用。金额全部取 Finance 持久行，不从请求回显；
 * 不触发支付、退款或补账。payment 缺行 → 空（调用方 404）；组织不符按空处理（不泄露他组织资源）。
 */
@Component
public class ConsumerPaymentFactsService {

	private final ConsumerPaymentRepository payments;

	public ConsumerPaymentFactsService(ConsumerPaymentRepository payments) {
		this.payments = payments;
	}

	public Mono<Map<String, Object>> find(String orderRef, String organizationId) {
		return payments.findPayment(orderRef).flatMap(payment -> {
			if (!payment.organizationId().equals(organizationId)) {
				return Mono.empty();
			}
			Mono<List<ConsumerPaymentRepository.Refund>> refundsM = payments.findRefunds(orderRef).collectList();
			Mono<ConsumerPaymentRepository.Split> splitM = payments.findSplit(orderRef);
			Mono<List<ConsumerPaymentRepository.SplitAllocation>> allocationsM = payments.findSplitAllocations(orderRef)
					.collectList();
			return Mono.zip(refundsM, splitM.map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()),
					allocationsM)
					.map(tuple -> body(payment, tuple.getT1(), tuple.getT2().orElse(null), tuple.getT3()));
		});
	}

	private static Map<String, Object> body(ConsumerPaymentRepository.Payment payment,
			List<ConsumerPaymentRepository.Refund> refunds, ConsumerPaymentRepository.Split split,
			List<ConsumerPaymentRepository.SplitAllocation> allocations) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("orderRef", payment.orderRef());
		body.put("organizationId", payment.organizationId());
		Map<String, Object> paymentView = new LinkedHashMap<>();
		paymentView.put("operationId", payment.operationId());
		paymentView.put("amountCents", payment.amountCents());
		paymentView.put("status", payment.status());
		paymentView.put("paidAt", payment.createdAt() == null ? null : payment.createdAt().toString());
		body.put("payment", paymentView);
		body.put("refunds", refunds.stream().map(r -> {
			Map<String, Object> view = new LinkedHashMap<>();
			view.put("operationId", r.operationId());
			view.put("amountCents", r.amountCents());
			view.put("occurredAt", r.createdAt() == null ? null : r.createdAt().toString());
			return view;
		}).toList());
		if (split == null) {
			body.put("split", null);
		} else {
			Map<String, Object> splitView = new LinkedHashMap<>();
			splitView.put("operationId", split.operationId());
			splitView.put("status", split.status());
			splitView.put("completedAt", split.completedAt() == null ? null : split.completedAt().toString());
			splitView.put("netTotalCents",
					payment.amountCents() - payment.refundedAmountCents() >= 0
							? payment.amountCents() - payment.refundedAmountCents()
							: 0);
			splitView.put("merchantAmountCents", split.merchantAmountCents());
			splitView.put("platformFeeCents", split.platformFeeCents());
			List<ConsumerPaymentRepository.SplitAllocation> source = allocations.isEmpty()
					&& split.recommenderAmountCents() > 0
							? List.of(new ConsumerPaymentRepository.SplitAllocation(split.recommenderAccountId(),
									split.recommenderAmountCents()))
							: allocations;
			splitView.put("allocations", source.stream().map(a -> {
				Map<String, Object> view = new LinkedHashMap<>();
				view.put("recommenderAccountId", a.recommenderAccountId());
				view.put("amountCents", a.amountCents());
				return view;
			}).toList());
			body.put("split", splitView);
		}
		return body;
	}
}
