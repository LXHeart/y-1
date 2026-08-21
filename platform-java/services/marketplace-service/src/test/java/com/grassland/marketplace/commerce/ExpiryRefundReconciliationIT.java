package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 退款「至多一次」对账闭环（任务书 #41 尾条）：到期自动退款全链路 + 响应丢失后重试的安全性。
 *
 * <p>「至多一次」由 finance 四层 operation_id 幂等保证（refund 表唯一键/预留守卫/账本部分唯一/
 * provider operation 唯一）；marketplace 的职责是<b>确定性重试键</b>——同一订单重试恒用同一
 * {@code refund_operation_id}，finance 命中即重放。锁住：
 * <ul>
 *   <li>到期自动退款能走完（claimExpired 补 refund_requested_amount_cents，修复前永久卡
 *       refund_pending——finance 已退款而订单状态不落）；</li>
 *   <li>响应丢失重试：finance 二次调用仍收同一 operationId（幂等重放不双退的前提）；
 *       重复 markRefunded 幂等（终态守卫 0 行回退原行）。</li>
 * </ul>
 */
class ExpiryRefundReconciliationIT extends MarketplaceItSupport {

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
    }

    @Test
    @DisplayName("到期未核销 → claimExpired → attemptRefund → refunded + 补库存；此前卡死 refund_pending")
    void expiredRedeemDeadlineAutoRefundCompletes() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
        String versionId = packageVersionId((String) offer.get("id"));

        Map<String, Object> order = createPaidOrder((String) offer.get("id"));
        assertThat(packageRemaining(versionId)).isEqualTo(4);
        expireRedeemDeadline((String) order.get("id"));

        List<Order> claimed = service.claimExpired(32).collectList().block();
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).status()).isEqualTo("refund_pending");

        // 此前 bug：claimExpired 不写 refund_requested_amount_cents → markRefunded 0 行 →
        // 永久 refund_pending（finance 幂等空转）。修复后 attemptRefund 一次走完。
        Order refunded = service.attemptRefund(claimed.get(0), "automatic_expiry").block();
        assertThat(refunded.status()).isEqualTo("refunded");
        assertThat(refunded.refundedAmountCents()).isEqualTo(10000L);
        // 未核销全额退 → 补库存：4 → 5
        assertThat(packageRemaining(versionId)).isEqualTo(5);
        assertThat(outboxCount("ConsumerOrderRefunded", (String) order.get("id"))).isEqualTo(1);
    }

    @Test
    @DisplayName("响应丢失重试安全：重试恒用同一 refund_operation_id（finance 命中即重放，不双退）")
    void responseLostRetryReusesDeterministicOperationId() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 8000, 5);

        Map<String, Object> order = createPaidOrder((String) offer.get("id"));
        expireRedeemDeadline((String) order.get("id"));
        Order claimed = service.claimExpired(32).collectList().block().get(0);
        String operationId = claimed.refundOperationId();
        assertThat(operationId).isEqualTo("commerce-refund:" + order.get("id"));

        // 第一次：finance 成功但 marketplace 没收到（模拟响应丢失——markRefunded 未执行）
        ArgumentCaptor<Order> refundCaptor = ArgumentCaptor.forClass(Order.class);
        when(finance.refund(any(Order.class), any()))
                .thenAnswer(inv -> Mono.error(new IllegalStateException("response lost")))
                .thenReturn(Mono.empty());
        Order errored = service.attemptRefund(claimed, "automatic_expiry").block();
        assertThat(errored.status()).isEqualTo("refund_pending");   // recordError 留在 refund_pending

        // 第二次重试（dispatcher 语义）：同一 operationId → finance 幂等重放 → markRefunded 落终态
        Order retried = service.attemptRefund(claimed, "automatic_expiry").block();
        assertThat(retried.status()).isEqualTo("refunded");
        verify(finance, times(2)).refund(refundCaptor.capture(), any());
        assertThat(refundCaptor.getAllValues())
                .allMatch(sent -> operationId.equals(sent.refundOperationId()));

        // 终态后重复 attemptRefund 幂等：状态守卫直接返回原行，不再打 finance
        clearInvocations(finance);
        Order again = service.attemptRefund(retried, "automatic_expiry").block();
        assertThat(again.status()).isEqualTo("refunded");
        verify(finance, never()).refund(any(), any());
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAndPublish(String merchant, String org, long priceCents, int stock) {
        Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "到期退款套餐", "description", "expiry refund",
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
    private Map<String, Object> createPaidOrder(String packageId) {
        Map<String, Object> response = client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("packageId", packageId))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> order = (Map<String, Object>) response.get("data");
        assertThat(order.get("status")).isEqualTo("paid");
        return order;
    }

    private void expireRedeemDeadline(String orderId) {
        db.sql("UPDATE consumer_order SET redeem_deadline = now() - interval '1 second'"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", orderId).then().block();
    }

    private String packageVersionId(String packageId) {
        return db.sql("""
                        SELECT v.id::text AS vid FROM commerce_package_version v
                         JOIN commerce_package p ON p.id = v.package_id
                        WHERE p.id = CAST(:p AS uuid) AND v.version = p.current_version
                        """)
                .bind("p", packageId).map(r -> r.get("vid", String.class)).one().block();
    }

    private int packageRemaining(String versionId) {
        Integer value = db.sql(
                        "SELECT remaining_stock FROM commerce_package_inventory"
                                + " WHERE package_version_id = CAST(:v AS uuid)")
                .bind("v", versionId).map(r -> r.get("remaining_stock", Integer.class)).one().block();
        return value == null ? -1 : value;
    }

    private long outboxCount(String eventType, String orderId) {
        Long count = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox"
                        + " WHERE event_type = :et AND aggregate_id = :agg")
                .bind("et", eventType).bind("agg", orderId)
                .map(r -> r.get("c", Long.class)).one().block();
        return count == null ? 0 : count;
    }
}
