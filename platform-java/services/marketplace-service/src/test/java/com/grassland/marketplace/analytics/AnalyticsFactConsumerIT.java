package com.grassland.marketplace.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.MarketplaceItSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 任务书 #103 C103-16：指标消费者契约（Controller 层）——响应/导出/看板统一消费经营事实。
 *
 * <ul>
 * <li>报表响应携带 §6.6 全部口径元信息与增量字段（与
 * {@code tests/fixtures/task-103/analytics-export-golden.json} 的 keys 同源）；</li>
 * <li>导出 CSV 前置口径注释行 + 响应头（与 JSON 同一 report，不二次单独算钱）；</li>
 * <li>分账事实缺投影 → 503 analytics_facts_incomplete（blockedReason），可重试恢复 200；</li>
 * <li>序列响应带 windowBasis/attributionWindowBasis 两条时间轴标注；</li>
 * <li>Ops 看板：已结只读事实 + 缺投影计数/标注（不进金额）。</li>
 * </ul>
 */
class AnalyticsFactConsumerIT extends MarketplaceItSupport {

	@Autowired
	private DatabaseClient db;

	@Autowired
	private com.grassland.marketplace.commerce.CommerceSettlementFactRepository settlementFacts;

	private static JsonNode golden;

	@BeforeAll
	static void loadGolden() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Path fixture = Path.of("..", "..", "..", "tests", "fixtures", "task-103", "analytics-export-golden.json");
		if (!Files.exists(fixture)) {
			throw new IllegalStateException("fixture not found: " + fixture.toAbsolutePath());
		}
		golden = mapper.readTree(Files.readString(fixture));
	}

	@Test
	@DisplayName("TC103-16-01/02 报表响应携带事实口径元信息与增量字段；导出同源")
	void reportCarriesFactsMetaAndExportSharesSource() {
		String merchant = UUID.randomUUID().toString();
		String organizationId = UUID.randomUUID().toString();

		byte[] report = client().get()
				.uri(uri -> uri.path("/api/admin/analytics/business").queryParam("organizationId", organizationId)
						.build())
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance")).exchange()
				.expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
		JsonNode body;
		try {
			body = new ObjectMapper().readTree(report).path("data");
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
		// 空数据含元信息（E01/E08）：金标准 keys 全在场（值为 0/complete 也要显式）
		for (JsonNode key : golden.path("keys")) {
			assertThat(body.has(key.asText())).as("报表缺字段 %s", key.asText()).isTrue();
		}
		assertThat(body.path("metricVersion").asText()).isEqualTo("commerce-facts-v2");
		assertThat(body.path("windowBasis").asText()).isEqualTo("order_created_at");
		assertThat(body.path("attributionWindowBasis").asText()).isEqualTo("event_occurred_at");
		assertThat(body.path("dataCompleteness").asText()).isEqualTo("complete");

		// 导出：CSV 注释行 + 响应头与 JSON 同源
		var exportResult = client().get()
				.uri(uri -> uri.path("/api/admin/analytics/business/export")
						.queryParam("organizationId", organizationId).queryParam("format", "csv").build())
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance")).exchange()
				.expectStatus().isOk().expectHeader().contentType("text/csv;charset=UTF-8").expectBody(byte[].class)
				.returnResult();
		String csv = new String(exportResult.getResponseBody(), StandardCharsets.UTF_8);
		assertThat(csv).contains("# 口径=commerce-facts-v2", "窗口基准=order_created_at", "完整度=complete", "单位=分（整数）");
		assertThat(exportResult.getResponseHeaders().getFirst("X-Analytics-Metric-Version"))
				.isEqualTo("commerce-facts-v2");
		assertThat(exportResult.getResponseHeaders().getFirst("X-Analytics-Data-Completeness")).isEqualTo("complete");
	}

	@Test
	@DisplayName("TC103-16-03 缺投影 → 503 analytics_facts_incomplete；补投影后恢复 200 完整报表")
	void missingFactsYield503ThenRecovers() {
		String merchant = UUID.randomUUID().toString();
		String organizationId = UUID.randomUUID().toString();
		String orderId = seedSettledOrderWithoutFact(organizationId);

		// 缺投影：报表与导出都 503（不返回假完整零收入报表）
		client().get()
				.uri(uri -> uri.path("/api/admin/analytics/business").queryParam("organizationId", organizationId)
						.build())
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance")).exchange()
				.expectStatus().isEqualTo(503).expectBody().jsonPath("$.error")
				.value(v -> assertThat(String.valueOf(v)).contains("结算数据待核对")).jsonPath("$.blockedReason")
				.isEqualTo("analytics_facts_incomplete");
		client().get()
				.uri(uri -> uri.path("/api/admin/analytics/business/export")
						.queryParam("organizationId", organizationId).queryParam("format", "csv").build())
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance")).exchange()
				.expectStatus().isEqualTo(503);

		// 补投影（C18 的重放路径）：恢复 200 且金标准值正确
		settlementFacts.recordVerified(
				new com.grassland.marketplace.commerce.CommerceSettlementFactRepository.VerifiedFact(orderId,
						organizationId, "split:" + orderId, 10000, 3000, 7000, 5950, 350, 700, Instant.now(),
						Instant.now()),
				List.of(new com.grassland.marketplace.commerce.CommerceSettlementFactRepository.Allocation(
						UUID.randomUUID().toString(), 700)))
				.block(Duration.ofSeconds(5));
		client().get()
				.uri(uri -> uri.path("/api/admin/analytics/business").queryParam("organizationId", organizationId)
						.build())
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.dataCompleteness").isEqualTo("complete")
				.jsonPath("$.data.grossGmvCents").isEqualTo(golden.path("goldenCase").path("grossGmvCents").asLong())
				.jsonPath("$.data.refundedGmvCents")
				.isEqualTo(golden.path("goldenCase").path("refundedGmvCents").asLong())
				.jsonPath("$.data.recommenderRevenueCents")
				.isEqualTo(golden.path("goldenCase").path("recommenderRevenueCents").asLong())
				.jsonPath("$.data.merchantRevenueCents")
				.isEqualTo(golden.path("goldenCase").path("merchantRevenueCents").asLong())
				.jsonPath("$.data.platformFeeCents")
				.isEqualTo(golden.path("goldenCase").path("platformFeeCents").asLong());
	}

	@Test
	@DisplayName("序列响应标注订单/归因两条时间轴；granularity 非法拒绝")
	void seriesCarriesDualWindowBasisLabels() {
		String merchant = UUID.randomUUID().toString();
		String organizationId = UUID.randomUUID().toString();
		String from = Instant.now().minusSeconds(86400 * 7).toString();
		String to = Instant.now().toString();

		client().get()
				.uri(uri -> uri.path("/api/analytics/series").queryParam("organizationId", organizationId)
						.queryParam("from", from).queryParam("to", to).build())
				.header("X-Grassland-Identity", sign(merchant, "merchant", organizationId, "basic_publish")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.windowBasis").isEqualTo("order_created_at")
				.jsonPath("$.data.attributionWindowBasis").isEqualTo("event_occurred_at").jsonPath("$.data.timezone")
				.isEqualTo("Asia/Shanghai").jsonPath("$.data.metricVersion").isEqualTo("commerce-facts-v2");

		client().get()
				.uri(uri -> uri.path("/api/analytics/series").queryParam("organizationId", organizationId)
						.queryParam("from", from).queryParam("to", to).queryParam("granularity", "hour").build())
				.header("X-Grassland-Identity", sign(merchant, "merchant", organizationId, "basic_publish")).exchange()
				.expectStatus().isBadRequest();
	}

	@Test
	@DisplayName("Ops 看板：已结只读事实来源；缺投影计数与 partial 标注")
	void opsDashboardSettledReadsFactsWithMissingAnnotation() {
		String orderId = seedSettledOrderWithoutFact(UUID.randomUUID().toString());
		String orgId = db.sql("SELECT organization_id::text FROM consumer_order WHERE id = CAST(:id AS uuid)")
				.bind("id", orderId).map((row, meta) -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));

		client().get().uri("/api/admin/commerce/ops-dashboard?days=7")
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.dataCompleteness").isEqualTo("partial")
				.jsonPath("$.data.missingSettlementFactCount").isEqualTo(1);
	}

	// ---------- helpers ----------

	/** 已分账（split_completed_at 已落）但无事实投影的历史单：价格 10000/退 3000（refund 事实）。 */
	private String seedSettledOrderWithoutFact(String organizationId) {
		String orderId = UUID.randomUUID().toString();
		String packageId = UUID.randomUUID().toString();
		String versionId = UUID.randomUUID().toString();
		db.sql("INSERT INTO commerce_package(id, organization_id, owner_account_id, status)"
				+ " VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:owner AS uuid), 'published')")
				.bind("id", packageId).bind("org", organizationId).bind("owner", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(5));
		db.sql("""
				INSERT INTO commerce_package_version(
				  id, package_id, version, title, price_cents, total_stock, valid_days_after_purchase,
				  recommender_share_bps, platform_fee_bps, merchant_share_bps, policy_version, created_by)
				VALUES (CAST(:id AS uuid), CAST(:package AS uuid), 1, 'consumer-golden', 10000, 100, 30,
				        1000, 500, 8500, 'commerce-v1', CAST(:owner AS uuid))
				""").bind("id", versionId).bind("package", packageId).bind("owner", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(5));
		db.sql("""
				INSERT INTO consumer_order(
				  id, consumer_account_id, organization_id, package_id, package_version_id, package_version,
				  package_title, price_cents, recommender_share_bps, platform_fee_bps, merchant_share_bps,
				  recommender_amount_cents, platform_fee_cents, merchant_amount_cents, policy_version, status,
				  recommender_account_id, redeem_code_hash, redeem_deadline, payment_operation_id,
				  refunded_amount_cents, created_at, paid_at, redeemed_at, split_completed_at)
				VALUES (CAST(:id AS uuid), CAST(:consumer AS uuid), CAST(:org AS uuid), CAST(:package AS uuid),
				        CAST(:version AS uuid), 1, 'consumer-golden', 10000, 1000, 500, 8500,
				        1000, 500, 8500, 'commerce-v1', 'redeemed',
				        CAST(:rec AS uuid), :redeemHash, now() + interval '30 days', :paymentOperation,
				        3000, now() - interval '2 days', now() - interval '2 days', now() - interval '1 day',
				        now())
				""").bind("id", orderId).bind("consumer", UUID.randomUUID().toString()).bind("org", organizationId)
				.bind("package", packageId).bind("version", versionId).bind("rec", UUID.randomUUID().toString())
				.bind("redeemHash", UUID.randomUUID().toString().replace("-", ""))
				.bind("paymentOperation", "payment:" + orderId).then().block(Duration.ofSeconds(5));
		db.sql("""
				INSERT INTO consumer_order_refund(id, order_id, operation_id, amount_cents, source, occurred_at)
				VALUES (gen_random_uuid(), CAST(:order AS uuid), :operation, 3000, 'consumer_it',
				        now() - interval '1 day')
				""").bind("order", orderId).bind("operation", "refund:" + orderId).then().block(Duration.ofSeconds(5));
		return orderId;
	}
}
