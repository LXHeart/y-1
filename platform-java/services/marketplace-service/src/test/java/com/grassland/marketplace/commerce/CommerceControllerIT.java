package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/** End-to-end marketplace commerce lifecycle against PostgreSQL, with finance kept at its HTTP seam. */
class CommerceControllerIT extends MarketplaceItSupport {

    @MockitoBean
    FinanceCommerceClient finance;

    @BeforeEach
    void sandboxFinance() {
        when(finance.pay(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            return Mono.just("sandbox:payment:" + order.id());
        });
        when(finance.refund(any(Order.class), anyString())).thenReturn(Mono.empty());
        when(finance.split(any(Order.class))).thenReturn(Mono.empty());
        when(finance.split(any(Order.class), anyList())).thenReturn(Mono.empty());
    }

    @Test
    void packageRevisionDoesNotChangePaidOrderSnapshotAndRedemptionIsSingleUse() {
        String merchant = UUID.randomUUID().toString();
        String consumer = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();

        Map<String, Object> offer = createAndPublish(merchant, org, 1000, 3);
        Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), recommender);
        assertThat(order.get("status")).isEqualTo("paid");
        assertThat(order.get("packageVersion")).isEqualTo(1);
        assertThat(order.get("priceCents")).isEqualTo(1000);
        assertThat(order.get("recommenderAmountCents")).isEqualTo(100);
        assertThat(order.get("platformFeeCents")).isEqualTo(50);

        Map<String, Object> revisedBody = offerBody(org, 2500, 8);
        client().put().uri("/api/v2/merchant/packages/" + offer.get("id"))
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(revisedBody)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.priceCents").isEqualTo(2500);

        client().get().uri("/api/v2/orders/" + order.get("id"))
                .header("X-Grassland-Identity", sign(consumer, null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.packageVersion").isEqualTo(1)
                .jsonPath("$.data.priceCents").isEqualTo(1000);

        String code = String.valueOf(order.get("redeemCode"));
        client().post().uri("/api/v2/merchant/redemptions")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", code))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("redeemed");
        client().post().uri("/api/v2/merchant/redemptions")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", code))
                .exchange().expectStatus().isEqualTo(409);

        client().post().uri("/api/v2/orders/" + order.get("id") + "/review")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("rating", 5, "comment", "很好"))
                .exchange().expectStatus().isCreated();
    }

    @Test
    void merchantOrderExportSupportsCsvAndRealXlsx() {
        String merchant = UUID.randomUUID().toString();
        String consumer = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 1200, 2);
        createOrder(consumer, (String) offer.get("id"), null);
        String assertion = sign(merchant, "merchant", org, "finance_transaction");

        byte[] csv = client().get().uri(uri -> uri.path("/api/v2/merchant/orders/export")
                        .queryParam("organizationId", org).queryParam("format", "csv").build())
                .header("X-Grassland-Identity", assertion).exchange().expectStatus().isOk()
                .expectHeader().contentType("text/csv;charset=UTF-8")
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(csv).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        assertThat(new String(csv, StandardCharsets.UTF_8)).contains("order_id", consumer);

        byte[] xlsx = client().get().uri(uri -> uri.path("/api/v2/merchant/orders/export")
                        .queryParam("organizationId", org).queryParam("format", "xlsx").build())
                .header("X-Grassland-Identity", assertion).exchange().expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(xlsx).startsWith((byte) 'P', (byte) 'K');
    }

    @Test
    void lastInventoryCanOnlyBeSoldOnceAndRefundRestoresItOnce() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 8800, 1);

        Map<String, Object> first = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
        client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("packageId", offer.get("id")))
                .exchange().expectStatus().isEqualTo(409);

        String firstConsumer = String.valueOf(first.get("consumerAccountId"));
        client().post().uri("/api/v2/orders/" + first.get("id") + "/refund")
                .header("X-Grassland-Identity", sign(firstConsumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "change_of_mind"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("refunded");
        client().post().uri("/api/v2/orders/" + first.get("id") + "/refund")
                .header("X-Grassland-Identity", sign(firstConsumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "again"))
                .exchange().expectStatus().isEqualTo(409);

        client().get().uri("/api/v2/packages/" + offer.get("id"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.remainingStock").isEqualTo(1);
        createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
    }

    @Test
    void timeSlotInventoryOrdersReserveTheSlotAndRefundRestoresIt() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublishWithSlots(merchant, org, 5000, 5);

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> slots =
                (java.util.List<Map<String, Object>>) offer.get("inventorySlots");
        assertThat(slots).hasSize(2);
        String morning = (String) slots.get(0).get("id");
        String afternoon = (String) slots.get(1).get("id");
        assertThat(slots.get(0).get("remainingStock")).isEqualTo(1);

        Map<String, Object> booked = createOrderWithSlot(
                UUID.randomUUID().toString(), (String) offer.get("id"), morning, null);
        assertThat(booked.get("status")).isEqualTo("paid");

        client().get().uri("/api/v2/orders/" + booked.get("id"))
                .header("X-Grassland-Identity", sign(String.valueOf(booked.get("consumerAccountId")), null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.inventorySlotId").isEqualTo(morning)
                .jsonPath("$.data.slotStart").isEqualTo("2026-09-01T10:00:00Z")
                .jsonPath("$.data.slotEnd").isEqualTo("2026-09-01T11:00:00Z");

        client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("packageId", offer.get("id"), "inventorySlotId", morning))
                .exchange().expectStatus().isEqualTo(409);

        createOrderWithSlot(UUID.randomUUID().toString(), (String) offer.get("id"), afternoon, null);

        client().post().uri("/api/v2/orders/" + booked.get("id") + "/refund")
                .header("X-Grassland-Identity", sign(String.valueOf(booked.get("consumerAccountId")), null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "schedule_change"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("refunded");

        client().get().uri("/api/v2/packages/" + offer.get("id"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.inventorySlots[0].remainingStock").isEqualTo(1)
                .jsonPath("$.data.inventorySlots[1].remainingStock").isEqualTo(1);
    }

    @Test
    void afterSalesDisputeDetailIsVisibleToOwnerAndStoreStaffAndPartialResolutionRefunds() {
        String merchant = UUID.randomUUID().toString();
        String consumer = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
        Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), null);
        String merchantAuth = sign(merchant, "merchant", org, "finance_transaction");
        client().post().uri("/api/v2/merchant/redemptions")
                .header("X-Grassland-Identity", merchantAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", order.get("redeemCode")))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("redeemed");

        client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "到店后商家无法提供服务"))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.data.status").isEqualTo("after_sales_disputed");

        client().get().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
                .header("X-Grassland-Identity", sign(consumer, null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("open")
                .jsonPath("$.data.reason").isEqualTo("到店后商家无法提供服务");

        client().get().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null))
                .exchange().expectStatus().isForbidden();

        client().get().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
                .header("X-Grassland-Identity", merchantAuth)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("open");

        client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute/resolve")
                .header("X-Grassland-Identity", merchantAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("resolution", "refund", "amountCents", 3000, "reason", "协商部分退款"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("partially_refunded")
                .jsonPath("$.data.refundedAmountCents").isEqualTo(3000);

        client().get().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
                .header("X-Grassland-Identity", sign(consumer, null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("resolved")
                .jsonPath("$.data.resolution").isEqualTo("refund")
                .jsonPath("$.data.resolutionAmountCents").isEqualTo(3000)
                .jsonPath("$.data.resolutionReason").isEqualTo("协商部分退款");
    }

    @Test
    void partialRefundsAccumulateAndAttributionRebindReplacesAllocations() {
        String consumer = UUID.randomUUID().toString();
        String firstRecommender = UUID.randomUUID().toString();
        String secondRecommender = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(UUID.randomUUID().toString(), org, 10000, 5);
        Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), firstRecommender);

        client().post().uri("/api/v2/orders/" + order.get("id") + "/refund")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 2000, "reason", "少上一道菜"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("partially_refunded")
                .jsonPath("$.data.refundedAmountCents").isEqualTo(2000);

        client().get().uri("/api/v2/orders/" + order.get("id") + "/attribution")
                .header("X-Grassland-Identity", sign(consumer, null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data[0].recommenderAccountId").isEqualTo(firstRecommender)
                .jsonPath("$.data[0].shareBps").isEqualTo(1000);

        client().post().uri("/api/v2/orders/" + order.get("id") + "/attribution")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("allocations",
                        java.util.List.of(Map.of("recommenderAccountId", secondRecommender, "shareBps", 1500)),
                        "reason", "实际由另一位推荐官带客"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.recommenderAccountId").isEqualTo(secondRecommender);

        client().get().uri("/api/v2/orders/" + order.get("id") + "/attribution")
                .header("X-Grassland-Identity", sign(consumer, null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].recommenderAccountId").isEqualTo(secondRecommender)
                .jsonPath("$.data[0].shareBps").isEqualTo(1500)
                .jsonPath("$.data[0].amountCents").isEqualTo(1500);

        client().post().uri("/api/v2/orders/" + order.get("id") + "/refund")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "补齐剩余退款"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("refunded");

        client().post().uri("/api/v2/orders/" + order.get("id") + "/attribution")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("recommenderAccountId", firstRecommender, "recommenderShareBps", 1000))
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---------- 任务书 #53：管理端点信封分页 ----------

    /** admin orders 信封：offset 取第二页、total 与筛选同口径、钳制边界（保留 created_at DESC 排序）。 */
    @Test
    void adminOrdersEnvelopePaginatesAndClamps() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 1000, 5);
        Map<String, Object> firstOrder = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
        Map<String, Object> secondOrder = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
        String admin = signWithRole(UUID.randomUUID().toString(), "customer_service");

        // created_at DESC：后创建的订单在前；offset=1 恰好取到先创建的那条。
        client().get().uri("/api/admin/commerce/orders?limit=1")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].id").isEqualTo(secondOrder.get("id"))
                .jsonPath("$.data.limit").isEqualTo(1)
                .jsonPath("$.data.offset").isEqualTo(0)
                .jsonPath("$.data.total").isNumber();
        client().get().uri("/api/admin/commerce/orders?limit=1&offset=1")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].id").isEqualTo(firstOrder.get("id"))
                .jsonPath("$.data.offset").isEqualTo(1);

        // total 与筛选同口径：两次调用一致，且叠加 status 筛选不越过无筛选总数。
        Map<String, Object> unfiltered = dataOf(adminOrders(admin, null));
        Map<String, Object> paid = dataOf(adminOrders(admin, "paid"));
        assertThat((Integer) unfiltered.get("total"))
                .isEqualTo((Integer) dataOf(adminOrders(admin, null)).get("total"))
                .isGreaterThanOrEqualTo((Integer) paid.get("total"));
        assertThat(((java.util.List<?>) paid.get("items")))
                .allSatisfy(item -> assertThat(((Map<String, Object>) item).get("status")).isEqualTo("paid"));

        // 钳制边界：limit=0→50、limit>200→200、offset<0→0。
        client().get().uri("/api/admin/commerce/orders?limit=0")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.limit").isEqualTo(50);
        client().get().uri("/api/admin/commerce/orders?limit=201")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.limit").isEqualTo(200);
        client().get().uri("/api/admin/commerce/orders?offset=-5")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.offset").isEqualTo(0);
    }

    /**
     * admin redemptions：单条 SQL 统一排序分页——redeeming/redeemed 混合状态按 created_at DESC
     * 交错出现（不再按状态分组拼接），total 同口径，钳制边界同 orders。
     */
    @Test
    @SuppressWarnings("unchecked")
    void adminRedemptionsEnvelopeMixesStatusesOrderedByCreatedAt() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 900, 5);
        Map<String, Object> early = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
        Map<String, Object> late = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
        // 直写混合状态：先创建的置 redeemed，后创建的置 redeeming——旧两次查询拼接会按状态分组，
        // 新单条 SQL 应按 created_at DESC 让 late（redeeming）排在 early（redeemed）之前。
        db.sql("UPDATE consumer_order SET status = 'redeemed', redeemed_at = now()"
                + " WHERE id = CAST(:id AS uuid)").bind("id", early.get("id")).then().block();
        db.sql("UPDATE consumer_order SET status = 'redeeming'"
                + " WHERE id = CAST(:id AS uuid)").bind("id", late.get("id")).then().block();
        String admin = signWithRole(UUID.randomUUID().toString(), "finance");

        Map<String, Object> response = client().get().uri("/api/admin/commerce/redemptions?limit=200")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> data = dataOf(response);
        java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
        assertThat(items).allSatisfy(item ->
                assertThat(item.get("status")).isIn("redeeming", "redeemed"));
        int lateIndex = indexOf(items, late.get("id"));
        int earlyIndex = indexOf(items, early.get("id"));
        assertThat(lateIndex).isGreaterThanOrEqualTo(0);
        assertThat(earlyIndex).isGreaterThanOrEqualTo(0);
        assertThat(lateIndex).isLessThan(earlyIndex);
        int total = ((Number) data.get("total")).intValue();
        assertThat(total).isGreaterThanOrEqualTo(2);

        // 分页生效 + total 跨页同口径。
        client().get().uri("/api/admin/commerce/redemptions?limit=1&offset=1")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.total").isEqualTo(total)
                .jsonPath("$.data.offset").isEqualTo(1);

        // 钳制边界。
        client().get().uri("/api/admin/commerce/redemptions?limit=0")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.limit").isEqualTo(50);
        client().get().uri("/api/admin/commerce/redemptions?limit=999&offset=-1")
                .header("X-Grassland-Identity", admin)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.limit").isEqualTo(200)
                .jsonPath("$.data.offset").isEqualTo(0);
    }

    private Map<String, Object> adminOrders(String adminHeader, String status) {
        String uri = "/api/admin/commerce/orders?limit=200";
        if (status != null) {
            uri += "&status=" + status;
        }
        return client().get().uri(uri)
                .header("X-Grassland-Identity", adminHeader)
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("data");
    }

    private static int indexOf(java.util.List<Map<String, Object>> items, Object id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).get("id"))) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAndPublish(
            String merchant, String org, long priceCents, int stock) {
        Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(offerBody(org, priceCents, stock))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> offer = (Map<String, Object>) created.get("data");
        client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
        return offer;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createOrder(String consumer, String packageId, String recommender) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", packageId);
        if (recommender != null) body.put("recommenderAccountId", recommender);
        Map<String, Object> response = client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAndPublishWithSlots(String merchant, String org, long priceCents, int stock) {
        Map<String, Object> body = new LinkedHashMap<>(offerBody(org, priceCents, stock));
        body.put("inventorySlots", java.util.List.of(
                Map.of("slotStart", "2026-09-01T10:00:00Z", "slotEnd", "2026-09-01T11:00:00Z", "totalStock", 1),
                Map.of("slotStart", "2026-09-01T14:00:00Z", "slotEnd", "2026-09-01T15:00:00Z", "totalStock", 2)));
        Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> offer = (Map<String, Object>) created.get("data");
        client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
        return offer;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createOrderWithSlot(String consumer, String packageId, String slotId,
            String recommender) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", packageId);
        body.put("inventorySlotId", slotId);
        if (recommender != null) body.put("recommenderAccountId", recommender);
        Map<String, Object> response = client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }

    private static Map<String, Object> offerBody(String org, long priceCents, int stock) {
        return Map.of(
                "organizationId", org, "title", "双人到店套餐", "description", "测试套餐",
                "priceCents", priceCents, "totalStock", stock, "validDaysAfterPurchase", 30,
                "recommenderShareBps", 1000, "platformFeeBps", 500, "policyVersion", "commerce-v1");
    }
}
