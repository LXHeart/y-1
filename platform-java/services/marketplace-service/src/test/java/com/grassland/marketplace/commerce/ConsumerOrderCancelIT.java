package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 消费者主动取消未支付订单（任务书 #41 尾巴）。锁住：
 * <ul>
 * <li>仅本人 + pending_payment 可取消——claim 条件 UPDATE 单边胜出，同事务释放库存 + 补发
 * {@code ConsumerOrderCancelled}（与超时关单同族，原因 consumer_cancelled）；</li>
 * <li>fail-closed：已支付 409（与 markPaid 竞态由状态机单边胜出）、非本人/不存在 404、重复取消 409；</li>
 * <li>slot 级订单按行上快照精确归还。</li>
 * </ul>
 */
class ConsumerOrderCancelIT extends MarketplaceItSupport {

	@MockitoBean
	FinanceCommerceClient finance;

	@Autowired
	CommerceService service;

	@BeforeEach
	void sandboxFinanceDefaults() {
		when(finance.pay(any(Order.class))).thenAnswer(inv -> {
			Order order = inv.getArgument(0);
			return Mono.just("sandbox:payment:" + order.id());
		});
		when(finance.refund(any(Order.class), any())).thenReturn(Mono.empty());
		when(finance.split(any(Order.class), any())).thenReturn(Mono.empty());
	}

	/** 支付失败留 pending_payment：以指定账号下单，返回订单体。 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> createUnpaidOrder(String accountId, String packageId, String slotId) {
		when(finance.pay(any(Order.class)))
				.thenReturn(Mono.error(new IllegalStateException("sandbox pay gateway down")));
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("packageId", packageId);
		if (slotId != null)
			body.put("inventorySlotId", slotId);
		Map<String, Object> response = client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(accountId, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	@Test
	@DisplayName("本人取消待支付单 → 200 cancelled + 同事务释放库存 + ConsumerOrderCancelled 事件")
	void ownerCancelsPendingOrderReleasingInventory() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		String versionId = packageVersionId((String) offer.get("id"));

		Map<String, Object> order = createUnpaidOrder(consumer, (String) offer.get("id"), null);
		assertThat(order.get("status")).isEqualTo("pending_payment");
		assertThat(packageRemaining(versionId)).isEqualTo(4);

		client().post().uri("/api/v2/orders/" + order.get("id") + "/cancel")
				.header("X-Grassland-Identity", sign(consumer, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("cancelled").jsonPath("$.data.lastError")
				.isEqualTo("consumer_cancelled");

		assertThat(packageRemaining(versionId)).isEqualTo(5);
		assertThat(outboxCount("ConsumerOrderCancelled", (String) order.get("id"))).isEqualTo(1);
	}

	@Test
	@DisplayName("slot 级订单取消：按行上快照 slotId 精确归还")
	void slotOrderCancelReleasesSlotInventory() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishWithSlots(merchant, org, 5000, 5);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> slots = (List<Map<String, Object>>) offer.get("inventorySlots");
		String slotId = (String) slots.get(0).get("id");

		Map<String, Object> order = createUnpaidOrder(consumer, (String) offer.get("id"), slotId);
		assertThat(slotRemaining(slotId)).isEqualTo(0);

		client().post().uri("/api/v2/orders/" + order.get("id") + "/cancel")
				.header("X-Grassland-Identity", sign(consumer, null)).exchange().expectStatus().isOk();

		assertThat(slotRemaining(slotId)).isEqualTo(1);
		assertThat(orderStatus((String) order.get("id"))).isEqualTo("cancelled");
	}

	@Test
	@DisplayName("重复取消 → 409；已支付单 → 409（与 markPaid 竞态状态机单边胜出，库存保留）")
	void repeatedOrPaidCancelRejectedWith409() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		String versionId = packageVersionId((String) offer.get("id"));

		Map<String, Object> order = createUnpaidOrder(consumer, (String) offer.get("id"), null);
		client().post().uri("/api/v2/orders/" + order.get("id") + "/cancel")
				.header("X-Grassland-Identity", sign(consumer, null)).exchange().expectStatus().isOk();
		// 第二次取消：已终态 → 409，不重复释放
		client().post().uri("/api/v2/orders/" + order.get("id") + "/cancel")
				.header("X-Grassland-Identity", sign(consumer, null)).exchange().expectStatus().isEqualTo(409);
		assertThat(packageRemaining(versionId)).isEqualTo(5);

		// sandbox 支付成功的单（paid）：先恢复 pay 成功桩（createUnpaidOrder 桩成了 error）
		when(finance.pay(any(Order.class))).thenAnswer(inv -> {
			Order paidOrder = inv.getArgument(0);
			return Mono.just("sandbox:payment:" + paidOrder.id());
		});
		Map<String, Object> paid = createOrder(consumer, (String) offer.get("id"));
		assertThat(paid.get("status")).isEqualTo("paid");
		client().post().uri("/api/v2/orders/" + paid.get("id") + "/cancel")
				.header("X-Grassland-Identity", sign(consumer, null)).exchange().expectStatus().isEqualTo(409);
		assertThat(packageRemaining(versionId)).isEqualTo(4); // 5 - 1（取消释放回满）- 1（paid 占用）
	}

	@Test
	@DisplayName("非本人 → 404（存在性不泄漏）；未登录 → 401")
	void foreignAccountGets404AndAnonymousGets401() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		Map<String, Object> order = createUnpaidOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);

		client().post().uri("/api/v2/orders/" + order.get("id") + "/cancel")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null)).exchange().expectStatus()
				.isNotFound();
		client().post().uri("/api/v2/orders/" + order.get("id") + "/cancel").exchange().expectStatus().isUnauthorized();
		assertThat(orderStatus((String) order.get("id"))).isEqualTo("pending_payment");
	}

	// ---------- helpers ----------

	@SuppressWarnings("unchecked")
	private Map<String, Object> createAndPublish(String merchant, String org, long priceCents, int stock) {
		Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("organizationId", org, "title", "取消测试套餐", "description", "cancel", "priceCents",
						priceCents, "totalStock", stock, "validDaysAfterPurchase", 30, "recommenderShareBps", 1000,
						"platformFeeBps", 500, "policyVersion", "commerce-v1"))
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> offer = (Map<String, Object>) created.get("data");
		client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk();
		return offer;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createAndPublishWithSlots(String merchant, String org, long priceCents, int stock) {
		Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("organizationId", org, "title", "时段取消套餐", "description", "cancel slot", "priceCents",
						priceCents, "totalStock", stock, "validDaysAfterPurchase", 30, "recommenderShareBps", 1000,
						"platformFeeBps", 500, "policyVersion", "commerce-v1", "inventorySlots",
						List.of(Map.of("slotStart", "2026-10-01T10:00:00Z", "slotEnd", "2026-10-01T11:00:00Z",
								"totalStock", 1))))
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> offer = (Map<String, Object>) created.get("data");
		client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk();
		return offer;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createOrder(String accountId, String packageId) {
		Map<String, Object> response = client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(accountId, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("packageId", packageId)).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private String packageVersionId(String packageId) {
		return db.sql("""
				SELECT v.id::text AS vid FROM commerce_package_version v
				 JOIN commerce_package p ON p.id = v.package_id
				WHERE p.id = CAST(:p AS uuid) AND v.version = p.current_version
				""").bind("p", packageId).map(r -> r.get("vid", String.class)).one().block();
	}

	private int packageRemaining(String versionId) {
		Integer value = db
				.sql("SELECT remaining_stock FROM commerce_package_inventory"
						+ " WHERE package_version_id = CAST(:v AS uuid)")
				.bind("v", versionId).map(r -> r.get("remaining_stock", Integer.class)).one().block();
		return value == null ? -1 : value;
	}

	private int slotRemaining(String slotId) {
		Integer value = db
				.sql("SELECT remaining_stock FROM commerce_package_inventory_slot" + " WHERE id = CAST(:s AS uuid)")
				.bind("s", slotId).map(r -> r.get("remaining_stock", Integer.class)).one().block();
		return value == null ? -1 : value;
	}

	private String orderStatus(String orderId) {
		return db.sql("SELECT status FROM consumer_order WHERE id = CAST(:id AS uuid)").bind("id", orderId)
				.map(r -> r.get("status", String.class)).one().block();
	}

	private long outboxCount(String eventType, String orderId) {
		Long count = db
				.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox"
						+ " WHERE event_type = :et AND aggregate_id = :agg")
				.bind("et", eventType).bind("agg", orderId).map(r -> r.get("c", Long.class)).one().block();
		return count == null ? 0 : count;
	}
}
