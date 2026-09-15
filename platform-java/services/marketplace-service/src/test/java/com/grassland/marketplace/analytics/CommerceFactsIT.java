package com.grassland.marketplace.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceSettlementFactRepository;
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
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 任务书 #103 C103-15：统一经营事实金标准（§6.6 八情形 + 幂等/冲突 + EXPLAIN 索引检验）。
 *
 * <p>
 * fixture {@code tests/fixtures/task-103/commerce-facts.json}（价格 10000，三方冻结
 * 1000/8500/500）逐情形落库后由 {@link CommerceFactsRepository#query} 复算——
 * 状态白名单被权威事实取代：未支付取消不计收入、退款只认成功事实、已结只认 settlement fact、 待结按 NetSplitAllocation
 * 净额预估。每情形独立组织，断言不受共享容器累积影响。
 */
class CommerceFactsIT extends MarketplaceItSupport {

	@Autowired
	private CommerceFactsRepository facts;

	@Autowired
	private CommerceSettlementFactRepository settlementFacts;

	@Autowired
	private DatabaseClient db;

	private static JsonNode golden;

	@BeforeAll
	static void loadGolden() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Path fixture = Path.of("..", "..", "..", "tests", "fixtures", "task-103", "commerce-facts.json");
		if (!Files.exists(fixture)) {
			throw new IllegalStateException("fixture not found: " + fixture.toAbsolutePath());
		}
		golden = mapper.readTree(Files.readString(fixture));
	}

	@Test
	@DisplayName("TC103-15-01/05 金标准八情形：支付/退款/核销/已结/待结全字段与 §6.6 一致")
	void goldenCasesMatchUnifiedFacts() {
		for (JsonNode scenario : golden.path("cases")) {
			String orgId = seedScenario(scenario);
			CommerceFactsRepository.Facts result = facts.query(orgId, null, null, null, null)
					.block(Duration.ofSeconds(5));
			JsonNode expect = scenario.path("expect");
			long[] settled = amounts(expect, "settled");
			long[] pending = amounts(expect, "pending");
			assertThat(result).as("%s facts", scenario.path("id").asText()).isNotNull();
			assertThat(result.paidOrders()).as("%s paidOrders", scenario.path("id").asText())
					.isEqualTo(expect.path("paidOrders").asInt());
			assertThat(result.grossGmvCents()).as("%s gross", scenario.path("id").asText())
					.isEqualTo(expect.path("gross").asLong());
			assertThat(result.refundedGmvCents()).as("%s refund", scenario.path("id").asText())
					.isEqualTo(expect.path("refund").asLong());
			assertThat(result.netGmvCents()).as("%s net", scenario.path("id").asText())
					.isEqualTo(expect.path("net").asLong());
			assertThat(new long[]{result.recommenderRevenueCents(), result.merchantRevenueCents(),
					result.platformFeeCents()}).as("%s settled", scenario.path("id").asText()).isEqualTo(settled);
			assertThat(new long[]{result.pendingRecommenderCents(), result.pendingMerchantCents(),
					result.pendingPlatformCents()}).as("%s pending", scenario.path("id").asText()).isEqualTo(pending);
			// 金标准各情形事实齐全（partial 场景由 missingSettlementFactMarksPartial 单测覆盖）
			assertThat(result.dataCompleteness()).as("%s completeness", scenario.path("id").asText())
					.isEqualTo("complete");
		}
	}

	@Test
	@DisplayName("TC103-15-02/E16 事实幂等：recordVerified 重放不重复累加；同 order 异 hash 不覆盖原事实")
	void settlementFactIdempotentAndConflictKept() {
		JsonNode scenario = scenarioOf("split-completed");
		String orgId = seedScenario(scenario);
		String orderId = lastOrderId;
		CommerceFactsRepository.Facts before = facts.query(orgId, null, null, null, null).block(Duration.ofSeconds(5));
		assertThat(before.recommenderRevenueCents()).isEqualTo(700);

		// 同键同额重放（回包丢失/E16）：吸收，不重复累加
		CommerceSettlementFactRepository.VerifiedFact fact = factOf(orderId, orgId, 3000, 700, 5950, 350);
		boolean inserted = settlementFacts
				.recordVerified(fact, List.of(new CommerceSettlementFactRepository.Allocation(lastRecommenderId, 700)))
				.block(Duration.ofSeconds(5));
		assertThat(inserted).isFalse();
		CommerceFactsRepository.Facts after = facts.query(orgId, null, null, null, null).block(Duration.ofSeconds(5));
		assertThat(after.recommenderRevenueCents()).isEqualTo(700);
		assertThat(after.merchantRevenueCents()).isEqualTo(5950);

		// 同 order 不同内容（改金额）——不覆盖原事实（冲突进核对，由查询层 partial/审计暴露）
		CommerceSettlementFactRepository.VerifiedFact tampered = factOf(orderId, orgId, 0, 999, 999, 999);
		boolean tamperedInserted = settlementFacts
				.recordVerified(tampered,
						List.of(new CommerceSettlementFactRepository.Allocation(lastRecommenderId, 999)))
				.block(Duration.ofSeconds(5));
		assertThat(tamperedInserted).isFalse();
		CommerceFactsRepository.Facts kept = facts.query(orgId, null, null, null, null).block(Duration.ofSeconds(5));
		assertThat(kept.recommenderRevenueCents()).as("原事实不被覆盖").isEqualTo(700);
	}

	@Test
	@DisplayName("TC103-15-03/E20 历史缺投影：已分账订单无事实行 → partial + missingSettlementFactCount")
	void missingSettlementFactMarksPartial() {
		// 已分账（split_completed_at 已落）但历史无事实投影（C103-15 之前的存量）
		String orgId = seedScenario(scenarioOf("split-completed"));
		db.sql("DELETE FROM commerce_settlement_allocation_fact WHERE order_id = CAST(:id AS uuid)\n")
				.bind("id", lastOrderId).then().block(Duration.ofSeconds(5));
		db.sql("DELETE FROM commerce_settlement_fact WHERE order_id = CAST(:id AS uuid)").bind("id", lastOrderId).then()
				.block(Duration.ofSeconds(5));
		CommerceFactsRepository.Facts result = facts.query(orgId, null, null, null, null).block(Duration.ofSeconds(5));
		assertThat(result.dataCompleteness()).isEqualTo("partial");
		assertThat(result.missingSettlementFactCount()).isEqualTo(1);
		// 未知不得当已确认 0 掩盖：已结收入为 0 但 partial 元数据可判（C16 据此 503）
		assertThat(result.recommenderRevenueCents()).isEqualTo(0);
	}

	@Test
	@DisplayName("TC103-15-01/E01 入参校验：缺 scope 拒绝；反向窗口拒绝")
	void invalidScopeAndWindowRejected() {
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> facts.query("  ", null, null, null, null));
		org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> facts.query(UUID.randomUUID().toString(), null, Instant.parse("2026-09-10T00:00:00Z"),
						Instant.parse("2026-09-01T00:00:00Z"), null));
	}

	@Test
	@DisplayName("E22/E15 EXPLAIN：代表性范围查询走索引（不 Seq Scan 全表）")
	void aggregateQueryUsesIndex() {
		String orgId = seedScenario(scenarioOf("paid-not-redeemed"));
		String plan = db.sql("""
				EXPLAIN (COSTS OFF) WITH cohort AS (
				    SELECT o.id FROM consumer_order o WHERE o.organization_id = CAST(:org AS uuid)
				      AND o.created_at >= :fromAt AND o.created_at < :toAt)
				SELECT count(*) FROM cohort c
				  LEFT JOIN consumer_order_refund r ON r.order_id = c.id
				  LEFT JOIN commerce_settlement_fact f ON f.order_id = c.id
				""").bind("org", orgId)
				.bind("fromAt", Instant.parse("2020-01-01T00:00:00Z").atOffset(java.time.ZoneOffset.UTC))
				.bind("toAt", Instant.parse("2030-01-01T00:00:00Z").atOffset(java.time.ZoneOffset.UTC))
				.map((row, meta) -> String.valueOf(row.get(0, String.class))).all().collectList()
				.map(lines -> String.join("\n", lines)).block(Duration.ofSeconds(5));
		assertThat(plan).as("范围查询应使用 idx_consumer_order_merchant 索引\n%s", plan).contains("Index");
		assertThat(plan).as("订单表不得全表扫描\n%s", plan).doesNotContain("Seq Scan on consumer_order");
	}

	// ---------- 场景落库 ----------

	private String lastOrderId;
	private String lastRecommenderId;

	private JsonNode scenarioOf(String id) {
		for (JsonNode scenario : golden.path("cases")) {
			if (id.equals(scenario.path("id").asText())) {
				return scenario;
			}
		}
		throw new IllegalArgumentException("scenario not found: " + id);
	}

	private static long[] amounts(JsonNode expect, String key) {
		JsonNode values = expect.path(key);
		return new long[]{values.get(0).asLong(), values.get(1).asLong(), values.get(2).asLong()};
	}

	/** 逐情形独立组织落库（价格/冻结额取 fixture allocation；状态与时间戳按情形描述）。 */
	private String seedScenario(JsonNode scenario) {
		String orgId = UUID.randomUUID().toString();
		String orderId = UUID.randomUUID().toString();
		String recommenderId = UUID.randomUUID().toString();
		String packageId = UUID.randomUUID().toString();
		String versionId = UUID.randomUUID().toString();
		long price = golden.path("allocation").path("priceCents").asLong();
		long recommender = golden.path("allocation").path("recommender").asLong();
		long merchant = golden.path("allocation").path("merchant").asLong();
		long platform = golden.path("allocation").path("platform").asLong();

		db.sql("INSERT INTO commerce_package(id, organization_id, owner_account_id, status)"
				+ " VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:owner AS uuid), 'published')")
				.bind("id", packageId).bind("org", orgId).bind("owner", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(5));
		db.sql("""
				INSERT INTO commerce_package_version(
				  id, package_id, version, title, price_cents, total_stock, valid_days_after_purchase,
				  recommender_share_bps, platform_fee_bps, merchant_share_bps, policy_version, created_by)
				VALUES (CAST(:id AS uuid), CAST(:package AS uuid), 1, 'facts-ver', :price, 100, 30,
				        1000, 500, 8500, 'commerce-v1', CAST(:owner AS uuid))
				""").bind("id", versionId).bind("package", packageId).bind("price", price)
				.bind("owner", UUID.randomUUID().toString()).then().block(Duration.ofSeconds(5));
		var orderSpec = db.sql("""
				INSERT INTO consumer_order(
				  id, consumer_account_id, organization_id, package_id, package_version_id, package_version,
				  package_title, price_cents, recommender_share_bps, platform_fee_bps, merchant_share_bps,
				  recommender_amount_cents, platform_fee_cents, merchant_amount_cents, policy_version, status,
				  recommender_account_id, redeem_code_hash, redeem_deadline, payment_operation_id,
				  created_at, paid_at, redeemed_at, refunded_at, split_completed_at)
				VALUES (CAST(:id AS uuid), CAST(:consumer AS uuid), CAST(:org AS uuid), CAST(:package AS uuid),
				        CAST(:version AS uuid), 1, 'facts-ver', :price, 1000, 500, 8500,
				        :recommender, :platform, :merchant, 'commerce-v1', :status,
				        CAST(:rec AS uuid), :redeemHash, now() + interval '30 days', :paymentOperation,
				        now() - interval '2 days', :paidAt, :redeemedAt, :refundedAt, :splitCompletedAt)
				""").bind("id", orderId).bind("consumer", UUID.randomUUID().toString()).bind("org", orgId)
				.bind("package", packageId).bind("version", versionId).bind("price", price)
				.bind("recommender", recommender).bind("platform", platform).bind("merchant", merchant)
				.bind("status", scenario.path("orderStatus").asText()).bind("rec", recommenderId)
				.bind("redeemHash", UUID.randomUUID().toString().replace("-", ""))
				.bind("paymentOperation", "payment:" + orderId);
		orderSpec = bindInstant(orderSpec, "paidAt",
				scenario.path("paidAt").asBoolean(false) ? Instant.now().minusSeconds(172800) : null);
		orderSpec = bindInstant(orderSpec, "redeemedAt",
				scenario.path("redeemedAt").asBoolean(false) ? Instant.now().minusSeconds(86400) : null);
		orderSpec = bindInstant(orderSpec, "refundedAt",
				"refunded".equals(scenario.path("orderStatus").asText()) ? Instant.now() : null);
		orderSpec = bindInstant(orderSpec, "splitCompletedAt",
				scenario.path("splitCompleted").asBoolean(false) ? Instant.now() : null);
		orderSpec.then().block(Duration.ofSeconds(5));

		long refunded = 0;
		for (JsonNode refund : scenario.path("refunds")) {
			long amount = refund.asLong();
			refunded += amount;
			db.sql("""
					INSERT INTO consumer_order_refund(id, order_id, operation_id, amount_cents, source, occurred_at)
					VALUES (gen_random_uuid(), CAST(:order AS uuid), :operation, :amount, 'facts_fixture',
					        now() - interval '1 day')
					""").bind("order", orderId).bind("operation", "refund:" + orderId + ":" + amount)
					.bind("amount", amount).then().block(Duration.ofSeconds(5));
		}

		if (scenario.path("settlementFact").asBoolean(false)) {
			// 经 recordVerified 落经确认事实（含分配快照；9000 净额情形退款=1000）
			long netTotal = price - refunded;
			long netRecommender = netTotal * recommender / price;
			long netMerchant = netTotal * merchant / price;
			long netPlatform = netTotal - netRecommender - netMerchant;
			CommerceSettlementFactRepository.VerifiedFact fact = factOf(orderId, orgId, refunded, netRecommender,
					netMerchant, netPlatform);
			settlementFacts
					.recordVerified(fact,
							List.of(new CommerceSettlementFactRepository.Allocation(recommenderId, netRecommender)))
					.block(Duration.ofSeconds(5));
		}

		lastOrderId = orderId;
		lastRecommenderId = recommenderId;
		return orgId;
	}

	private static org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec bindInstant(
			org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec, String name, Instant value) {
		return value == null
				? spec.bindNull(name, java.time.OffsetDateTime.class)
				: spec.bind(name, value.atOffset(java.time.ZoneOffset.UTC));
	}

	private static CommerceSettlementFactRepository.VerifiedFact factOf(String orderId, String orgId,
			long refundedBeforeSplit, long recommenderCents, long merchantCents, long platformCents) {
		return new CommerceSettlementFactRepository.VerifiedFact(orderId, orgId, "split:" + orderId, 10000,
				refundedBeforeSplit, recommenderCents + merchantCents + platformCents, merchantCents, platformCents,
				recommenderCents, Instant.now(), Instant.now());
	}
}
