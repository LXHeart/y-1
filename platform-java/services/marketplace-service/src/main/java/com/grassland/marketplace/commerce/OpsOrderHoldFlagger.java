package com.grassland.marketplace.commerce;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 C98-05 / D98-05：异常订单自动标记看门狗（照 #97 C97-03 dispatcher 范式）。
 *
 * <p>
 * 三条规则 v1（阈值全部可配，短窗=各自统计窗）：
 * <ol>
 * <li>referral_refund_rate：同推荐官近窗归因订单退款率超标 → 标记其未退款订单；</li>
 * <li>appeal_burst：同推荐官近窗归因申诉集中度超标 → 标记被申诉订单；</li>
 * <li>rlid_order_burst：同 rlid 近窗订单量激增超标 → 标记该链接新订单。</li>
 * </ol>
 * 只写 flagged 候选行（唯一索引幂等），不碰钱、不阻断结算——held 须人工确认（OpsOrderHoldService）。
 */
@Component
@ConditionalOnProperty(name = "marketplace.ops.order-hold-flagger-enabled", havingValue = "true", matchIfMissing = true)
public class OpsOrderHoldFlagger {

	private static final Logger log = LoggerFactory.getLogger(OpsOrderHoldFlagger.class);

	private final OpsOrderHoldRepository holds;
	private final OpsOrderHoldService service;
	private final int batchSize;

	public OpsOrderHoldFlagger(OpsOrderHoldRepository holds, OpsOrderHoldService service,
			@Value("${marketplace.ops.order-hold-flagger-batch-size:50}") int batchSize) {
		this.holds = holds;
		this.service = service;
		this.batchSize = Math.max(1, Math.min(batchSize, 200));
	}

	@Scheduled(fixedDelayString = "${marketplace.ops.order-hold-flagger-poll-ms:120000}")
	public void dispatch() {
		service.evaluateRules(batchSize).collectList().doOnNext(flagged -> {
			if (!flagged.isEmpty()) {
				log.warn("任务书 #98 自动标记异常订单候选 {} 行（flagged 不碰钱，待人工确认）", flagged.size());
			}
		}).onErrorResume(error -> {
			log.error("异常订单标记扫描失败", error);
			return Mono.just(List.of());
		}).block();
	}
}
