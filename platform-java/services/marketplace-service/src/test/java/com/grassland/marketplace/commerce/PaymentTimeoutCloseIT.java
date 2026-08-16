package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #41：未支付订单 TTL 关单。锁住三件最不能靠人工点的事：
 * <ul>
 *   <li>关单 + 对称释放库存（包级与 slot 级），同事务，原因 payment_timeout 落 last_error；</li>
 *   <li>竞态双向互斥（D4）——支付先赢 claim 0 行不释放；claim 先赢支付尝试跳过（无 finance 调用）；</li>
 *   <li>幂等与封顶——重复 claim 无副作用；remaining==total 时 release 不越界；deadline NULL 不过期。</li>
 * </ul>
 * dispatcher 在 IT 默认关闭，故直接调 {@code service.cancelExpired}（同包可见），行为与 dispatcher 链一致。
 */
class PaymentTimeoutCloseIT extends MarketplaceItSupport {

    @MockitoBean
    FinanceCommerceClient finance;

    @Autowired
    CommerceService service;

    @Autowired
    CommerceRepository repository;

    @BeforeEach
    void sandboxFinanceDefaults() {
        when(finance.pay(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            return Mono.just("sandbox:payment:" + order.id());
        });
        when(finance.refund(any(Order.class), any())).thenReturn(Mono.empty());
        when(finance.split(any(Order.class))).thenReturn(Mono.empty());
        when(finance.split(any(Order.class), any())).thenReturn(Mono.empty());
    }

    /** 支付失败留 pending_payment（泄漏场景复现）：返回 201 订单体。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> createUnpaidOrder(String packageId, String slotId) {
        when(finance.pay(any(Order.class)))
                .thenReturn(Mono.error(new IllegalStateException("sandbox pay gateway down")));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("packageId", packageId);
        if (slotId != null) body.put("inventorySlotId", slotId);
        Map<String, Object> response = client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }

    private void expireOrder(String orderId) {
        db.sql("UPDATE consumer_order SET payment_deadline = now() - interval '1 second'"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", orderId).then().block();
    }

    private int packageRemaining(String versionId) {
        Integer value = db.sql(
                        "SELECT remaining_stock FROM commerce_package_inventory"
                                + " WHERE package_version_id = CAST(:v AS uuid)")
                .bind("v", versionId).map(r -> r.get("remaining_stock", Integer.class)).one().block();
        return value == null ? -1 : value;
    }

    private int slotRemaining(String slotId) {
        Integer value = db.sql(
                        "SELECT remaining_stock FROM commerce_package_inventory_slot"
                                + " WHERE id = CAST(:s AS uuid)")
                .bind("s", slotId).map(r -> r.get("remaining_stock", Integer.class)).one().block();
        return value == null ? -1 : value;
    }

    private String orderStatus(String orderId) {
        return db.sql("SELECT status FROM consumer_order WHERE id = CAST(:id AS uuid)")
                .bind("id", orderId).map(r -> r.get("status", String.class)).one().block();
    }

    private long outboxCount(String eventType, String orderId) {
        Long count = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox"
                        + " WHERE event_type = :et AND aggregate_id = :agg")
                .bind("et", eventType).bind("agg", orderId)
                .map(r -> r.get("c", Long.class)).one().block();
        return count == null ? 0 : count;
    }

    @Test
    void expiredPackageOrderIsCancelledAndPackageInventoryReleased() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
        String versionId = packageVersionId((String) offer.get("id"));

        Map<String, Object> order = createUnpaidOrder((String) offer.get("id"), null);
        assertThat(order.get("status")).isEqualTo("pending_payment");
        // 下单即扣：5 → 4
        assertThat(packageRemaining(versionId)).isEqualTo(4);
        // 新单 deadline 是行上快照（默认 900s，还未到）
        assertThat(order.get("paymentDeadline")).isNotNull();

        expireOrder((String) order.get("id"));
        List<Order> cancelled = service.cancelExpired(32).collectList().block();
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.get(0).id()).isEqualTo(order.get("id"));

        assertThat(orderStatus((String) order.get("id"))).isEqualTo("cancelled");
        // 关单同事务对称释放：4 → 5；原因 payment_timeout 落 last_error；事件同事务补发
        assertThat(packageRemaining(versionId)).isEqualTo(5);
        String lastError = db.sql("SELECT last_error FROM consumer_order WHERE id = CAST(:id AS uuid)")
                .bind("id", order.get("id")).map(r -> r.get("last_error", String.class)).one().block();
        assertThat(lastError).isEqualTo("payment_timeout");
        assertThat(outboxCount("ConsumerOrderCancelled", (String) order.get("id"))).isEqualTo(1);
    }

    @Test
    void expiredSlotOrderReleasesSlotInventory() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublishWithSlots(merchant, org, 5000, 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) offer.get("inventorySlots");
        String slotId = (String) slots.get(0).get("id");
        assertThat(slotRemaining(slotId)).isEqualTo(1);   // 建套餐时 totalStock=1

        Map<String, Object> order = createUnpaidOrder((String) offer.get("id"), slotId);
        assertThat(slotRemaining(slotId)).isEqualTo(0);   // 下单即扣 slot
        expireOrder((String) order.get("id"));
        assertThat(service.cancelExpired(32).collectList().block()).hasSize(1);

        // slot 级精确释放：0 → 1（按订单快照的 slotId，不按当前版本猜）
        assertThat(slotRemaining(slotId)).isEqualTo(1);
        assertThat(orderStatus((String) order.get("id"))).isEqualTo("cancelled");
    }

    @Test
    void unexpiredOrderStaysPendingAndInventoryStaysReserved() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
        String versionId = packageVersionId((String) offer.get("id"));

        Map<String, Object> order = createUnpaidOrder((String) offer.get("id"), null);
        // 不动 deadline（now+900s 未到）
        assertThat(service.cancelExpired(32).collectList().block()).isEmpty();
        assertThat(orderStatus((String) order.get("id"))).isEqualTo("pending_payment");
        assertThat(packageRemaining(versionId)).isEqualTo(4);
    }

    /** 竞态方向一（D4）：支付先赢（markPaid 落库）→ claim 条件 UPDATE 0 行，库存保留占用。 */
    @Test
    void paidOrderWinsRaceAndKeepsInventoryOccupied() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
        String versionId = packageVersionId((String) offer.get("id"));

        // sandbox pay 默认成功 → 下单即 paid
        Map<String, Object> order = createOrder((String) offer.get("id"));
        assertThat(order.get("status")).isEqualTo("paid");
        expireOrder((String) order.get("id"));   // 理论上不会发生（已 paid），模拟竞态窗口

        assertThat(service.cancelExpired(32).collectList().block()).isEmpty();
        assertThat(orderStatus((String) order.get("id"))).isEqualTo("paid");
        // paid 保留占用：仍 4，不释放
        assertThat(packageRemaining(versionId)).isEqualTo(4);
        assertThat(outboxCount("ConsumerOrderCancelled", (String) order.get("id"))).isZero();
    }

    /** 竞态方向二（D4）：claim 先赢（cancelled 终态）→ 支付尝试按状态守卫跳过，无 finance 调用。 */
    @Test
    void cancelledOrderWinsRaceAndPaymentAttemptSkips() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);

        Map<String, Object> order = createUnpaidOrder((String) offer.get("id"), null);
        expireOrder((String) order.get("id"));
        List<Order> cancelled = service.cancelExpired(32).collectList().block();
        assertThat(cancelled).hasSize(1);

        // dispatcher 语义：随后对同一订单 attemptPayment —— 状态守卫跳过，不打 finance。
        // （clearInvocations 先清掉下单时那次失败的 pay 调用记录，只统计取消之后的。）
        clearInvocations(finance);
        Order result = service.attemptPayment(cancelled.get(0)).block();
        assertThat(result.status()).isEqualTo("cancelled");
        verify(finance, never()).pay(any(Order.class));
    }

    /** 幂等：重复 claim 无副作用（第二次 0 行），release 封顶守卫不把库存刷爆。 */
    @Test
    void repeatedClaimIsIdempotentAndReleaseCapsAtTotal() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
        String versionId = packageVersionId((String) offer.get("id"));

        Map<String, Object> order = createUnpaidOrder((String) offer.get("id"), null);
        expireOrder((String) order.get("id"));

        assertThat(service.cancelExpired(32).collectList().block()).hasSize(1);
        // 第二轮：claim 0 行（状态已终态），库存维持 5 不重复释放
        assertThat(service.cancelExpired(32).collectList().block()).isEmpty();
        assertThat(packageRemaining(versionId)).isEqualTo(5);

        // 封顶：remaining==total（5/5）时直接 release → 0 行被守卫吸收，仍 5
        assertThat(repository.releaseInventory(versionId).block()).isNull();
        assertThat(packageRemaining(versionId)).isEqualTo(5);
    }

    /** NULL deadline 防御语义：终态/历史行（无快照）永不过期。 */
    @Test
    void nullDeadlineNeverExpires() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);

        Map<String, Object> order = createUnpaidOrder((String) offer.get("id"), null);
        db.sql("UPDATE consumer_order SET payment_deadline = NULL WHERE id = CAST(:id AS uuid)")
                .bind("id", order.get("id")).then().block();

        assertThat(service.cancelExpired(32).collectList().block()).isEmpty();
        assertThat(orderStatus((String) order.get("id"))).isEqualTo("pending_payment");
    }

    /** 下单 deadline 是行上快照（D1）：改快照值不改行为判定基准——到点与否只看行值。 */
    @Test
    void deadlineSnapshotOnRowDrivesExpiryNotConfig() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);

        Map<String, Object> order = createUnpaidOrder((String) offer.get("id"), null);
        // 把行上快照拨到 1ms 后并等到它过——判定只依赖行值，与进程内配置无关
        db.sql("UPDATE consumer_order SET payment_deadline = now() + interval '50 milliseconds'"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", order.get("id")).then().block();
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(service.cancelExpired(32).collectList().block()).hasSize(1);
        assertThat(orderStatus((String) order.get("id"))).isEqualTo("cancelled");
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAndPublish(String merchant, String org, long priceCents, int stock) {
        Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "关单测试套餐", "description", "TTL",
                        "priceCents", priceCents, "totalStock", stock, "validDaysAfterPurchase", 30,
                        "recommenderShareBps", 1000, "platformFeeBps", 500, "policyVersion", "commerce-v1"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> offer = (Map<String, Object>) created.get("data");
        client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
        return offer;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAndPublishWithSlots(String merchant, String org, long priceCents, int stock) {
        Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "时段关单套餐", "description", "TTL slot",
                        "priceCents", priceCents, "totalStock", stock, "validDaysAfterPurchase", 30,
                        "recommenderShareBps", 1000, "platformFeeBps", 500, "policyVersion", "commerce-v1",
                        "inventorySlots", List.of(Map.of(
                                "slotStart", "2026-10-01T10:00:00Z", "slotEnd", "2026-10-01T11:00:00Z",
                                "totalStock", 1))))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> offer = (Map<String, Object>) created.get("data");
        client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
        return offer;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createOrder(String packageId) {
        Map<String, Object> response = client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("packageId", packageId))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }

    private String packageVersionId(String packageId) {
        return db.sql("""
                        SELECT v.id::text AS vid FROM commerce_package_version v
                         JOIN commerce_package p ON p.id = v.package_id
                         WHERE p.id = CAST(:p AS uuid) AND v.version = p.current_version
                        """)
                .bind("p", packageId).map(r -> r.get("vid", String.class)).one().block();
    }
}
