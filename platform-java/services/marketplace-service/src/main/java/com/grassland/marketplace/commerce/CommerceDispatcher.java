package com.grassland.marketplace.commerce;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Repairs cross-service gaps and triggers automatic refunds for expired
 * unredeemed orders.
 *
 * <p>
 * 任务书 #41（D2）：同时承接未支付订单 TTL 关单——{@code claimPaymentExpired}
 * 在捞单（pendingDispatch） **之前**执行，过期的 pending_payment 已被置
 * cancelled（终态）不再被捞出，「到期不再尝试支付」由执行顺序保证； 未过期的照旧 attemptPayment。与支付成功的竞态由 DB 状态守卫
 * + 资金操作占位（审查修复 01 R01）单边胜出。
 *
 * <p>
 * 审查修复 01：{@code splitting}（分账占位者崩溃后的恢复重放）与 {@code partially_refunded}
 * （已核销部分退款后的净额分账，R03）进入分账分发；收尾后再驱动一轮资金操作恢复扫描 （payment×cancelled
 * 与取消补偿退款，C01-A/B）。
 */
@Component
@ConditionalOnProperty(name = "marketplace.commerce.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
public class CommerceDispatcher {

	private final CommerceService service;
	private final int batchSize;
	private final AtomicBoolean running = new AtomicBoolean();

	public CommerceDispatcher(CommerceService service,
			@Value("${marketplace.commerce.dispatcher-batch-size:32}") int batchSize) {
		this.service = service;
		this.batchSize = Math.max(1, batchSize);
	}

	@Scheduled(fixedDelayString = "${marketplace.commerce.dispatcher-poll-ms:3000}")
	public void dispatch() {
		if (!running.compareAndSet(false, true))
			return;
		service.claimExpired(batchSize).flatMap(order -> service.attemptRefund(order, "automatic_expiry"), 4)
				.thenMany(service.cancelExpired(batchSize)).thenMany(service.pendingDispatch(batchSize))
				.flatMap(order -> switch (order.status()) {
					case "pending_payment" -> service.attemptPayment(order);
					case "refund_pending" -> service.attemptRefund(order, "dispatcher_retry");
					// 任务书 #75 D3：redeemed（冷静期已满，SQL 已滤未到期行）与 legacy redeeming 同走分账收尾；
					// 审查修复 01：splitting=崩溃恢复重放（幂等），partially_refunded=已核销净额分账（R03）。
					case "redeeming", "redeemed", "partially_refunded", "splitting" -> service.attemptSplit(order);
					default -> Flux.<CommerceModels.Order>empty().next();
				}, 4)
				// 资金操作恢复（R01/C01-A）：取消后补偿与回复丢失重放，按 next_attempt_at/租约认领。
				.thenMany(service.recoverFundOperations(batchSize)).doFinally(ignored -> running.set(false))
				.subscribe(ignored -> {
				}, error -> running.set(false));
	}
}
