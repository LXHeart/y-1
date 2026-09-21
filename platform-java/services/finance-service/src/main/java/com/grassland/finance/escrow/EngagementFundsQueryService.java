package com.grassland.finance.escrow;

import com.grassland.finance.freebie.FreebieEscrow;
import com.grassland.finance.freebie.FreebieEscrowRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 退出资金权威事实查询（任务书 #103 C103-03 / §6.2）：只读聚合本域 funds_reservation 与 freebie_escrow
 * 的已提交事实，供 marketplace 恢复 worker 核实原经济键的实际结果。
 *
 * <p>
 * 金额全部取 Finance 本域持久行，不从请求回显；没有任何腿时 exists=false、金额为 0——调用方只有在 冻结快照该腿为 0 时才可判
 * not_required。本服务不触发任何资金命令。
 */
@Component
public class EngagementFundsQueryService {

	private final ReservationRepository reservations;
	private final FreebieEscrowRepository freebies;

	public EngagementFundsQueryService(ReservationRepository reservations, FreebieEscrowRepository freebies) {
		this.reservations = reservations;
		this.freebies = freebies;
	}

	public Mono<Map<String, Object>> find(String engagementRef, String organizationId) {
		return Mono.zip(reservations.findByEngagementRef(engagementRef).defaultIfEmpty(noneReservation()),
				freebies.findByEngagementRef(engagementRef).defaultIfEmpty(noneFreebie())).flatMap(tuple -> {
					if (tuple.getT1().id() == null && tuple.getT2().id() == null) {
						// §6.2：404 无事实——两腿都不存在时不返回 exists=false 的空报表。
						return Mono.error(new IllegalArgumentException("no engagement facts"));
					}
					return Mono.just(body(engagementRef, organizationId, tuple.getT1(), tuple.getT2()));
				});
	}

	private static FundsReservation noneReservation() {
		return new FundsReservation(null, null, null, null, 0, "absent", null, null, 0, 0, null, null, null, 0);
	}

	private static FreebieEscrow noneFreebie() {
		return new FreebieEscrow(null, null, null, null, null, 0, "absent", null, null);
	}

	private static Map<String, Object> body(String engagementRef, String organizationId, FundsReservation bounty,
			FreebieEscrow deposit) {
		boolean bountyExists = bounty.id() != null;
		boolean depositExists = deposit.id() != null;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("engagementRef", engagementRef);
		m.put("organizationId", organizationId);
		m.put("observedAt", Instant.now().toString());

		Map<String, Object> b = new LinkedHashMap<>();
		b.put("exists", bountyExists);
		b.put("originalReservedCents", 0L);
		b.put("payeeAccountId", null);
		b.put("capturedCents", 0L);
		b.put("releasedCents", 0L);
		b.put("operationReferences", List.of());
		if (bountyExists) {
			if (!organizationId.equals(bounty.organizationId())) {
				throw new IllegalArgumentException("reservation organization mismatch");
			}
			b.put("originalReservedCents", bounty.amountCents());
			b.put("payeeAccountId", bounty.payeeAccountId());
			// 退出快照冻结的是里程碑毛额；净入账另含平台抽成/补贴，不能用于经济腿核对。
			long captured = "captured".equals(bounty.status()) ? bounty.effectiveSettlementAmountCents() : 0;
			// capture 与差额返还在同一事务提交，不再需要对 captured 预留发 release 命令。
			long released = switch (bounty.status()) {
				case "released" -> bounty.amountCents();
				case "captured", "refunded" -> bounty.amountCents() - bounty.effectiveSettlementAmountCents();
				default -> 0;
			};
			b.put("capturedCents", captured);
			b.put("releasedCents", released);
			b.put("status", bounty.status());
			b.put("operationReferences", List.of(bounty.id()));
		}
		m.put("bounty", b);

		Map<String, Object> d = new LinkedHashMap<>();
		d.put("exists", depositExists);
		d.put("amountCents", 0L);
		d.put("refundedCents", 0L);
		d.put("compensatedCents", 0L);
		d.put("operationReferences", List.of());
		if (depositExists) {
			if (!organizationId.equals(deposit.organizationId())) {
				throw new IllegalArgumentException("freebie organization mismatch");
			}
			d.put("amountCents", deposit.amountCents());
			d.put("refundedCents", FreebieEscrow.STATUS_REFUNDED.equals(deposit.status()) ? deposit.amountCents() : 0);
			d.put("compensatedCents",
					FreebieEscrow.STATUS_COMPENSATED.equals(deposit.status()) ? deposit.amountCents() : 0);
			d.put("status", deposit.status());
			d.put("operationReferences", List.of(deposit.id()));
		}
		m.put("deposit", d);
		return m;
	}
}
