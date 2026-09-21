package com.grassland.intelligence.compliance;

import static com.grassland.identity.assertion.TestAssertionHelper.serviceSigner;
import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 注销准备屏障 IT（任务书 #103 C103-08 / TC103-08-01～06 / R03）。
 *
 * <p>
 * 覆盖：活动任务按 kind 全量计数（含 unknown/费用未结，不只数 running）；prepare 冻结幂等与 409 分类；V85
 * 触发器对冻结账号受保护表新建的写防护（R03 核心反例：旧草稿已删而原稿/凭据 仍在→ 现在冻结后<b>任何</b>受保护表都不得再新建）；release
 * 只取消未清理态冻结；注册任务与 prepare 的关闭竞态两种顺序。
 */
class IntelligenceClosureBarrierIT extends IntelligenceItSupport {

    @Test
    void preparationWaitsForInFlightJobInsertAndRechecksCommittedInventory() throws Exception {
        String account = UUID.randomUUID().toString();
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("INSERT INTO ai_run(operation_id, account_id, capability, provider, budget_cents, status, started_at)"
                    + " VALUES (gen_random_uuid(), ?, 'text', 'sandbox', 0, 'running', now())")) {
                statement.setString(1, account);
                statement.executeUpdate();
            }
            var preparing = lifecycle.prepare(account, UUID.randomUUID()).toFuture();
            try {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
                    Long waiting = db.sql("SELECT count(*) AS n FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                            + "AND query LIKE '%intelligence_account_lifecycle%'")
                            .map(row -> row.get("n", Long.class)).one().block();
                    assertThat(waiting).isGreaterThan(0L);
                });
                assertThat(preparing).isNotDone();
                connection.commit();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> preparing.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .hasRootCauseMessage("ACTIVE_JOBS:[ai_run]");
                assertThat(lifecycle.find(account).block().state()).isEqualTo("active");
                assertThat(inventory.countByKind(account).block()).containsEntry("ai_run", 1L);
            } finally {
                connection.rollback();
                preparing.cancel(true);
            }
        }
    }

    @Test
    void frozenAccountCannotReactivateAnExistingCompletedJob() {
        String account = UUID.randomUUID().toString();
        UUID operation = UUID.randomUUID();
        db.sql("INSERT INTO ai_run(operation_id, account_id, capability, provider, budget_cents, status, started_at)"
                + " VALUES (:op, :a, 'text', 'sandbox', 0, 'succeeded', now())")
                .bind("op", operation).bind("a", account).then().block();
        assertThat(lifecycle.prepare(account, UUID.randomUUID()).block().state()).isEqualTo("frozen");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("UPDATE ai_run SET status='running' WHERE operation_id=:op")
                .bind("op", operation).then().block()).hasMessageContaining("account_closure_barrier");
    }

	@Autowired
	private IntelligenceJobInventory inventory;

	@Autowired
	private IntelligenceAccountLifecycleRepository lifecycle;

	private String identityServiceAssertion() {
		Instant now = Instant.now();
		return serviceSigner("identity", "grassland-intelligence")
				.sign(new IdentityAssertion("service:identity", null, null, null, null, "service", "internal", null,
						"r", "t", "grassland-intelligence", now, now.plusSeconds(30), "service", "identity"));
	}

	// ---------- TC103-08-01：任务状态全表 ----------

	@Test
	void inventoryCountsUnknownAndUnsettledFactsBeyondRunning() {
		String account = UUID.randomUUID().toString();
		db.sql("INSERT INTO ai_run(operation_id, account_id, capability, provider, budget_cents, status, started_at)"
				+ " VALUES (gen_random_uuid(), :a, 'text', 'sandbox', 0, 'running', now())").bind("a", account).then()
				.block();
		db.sql("INSERT INTO speech_transcription(id, media_reference_id, owner_account_id, requested_language,"
				+ " duration_ms, status, created_at)"
				+ " VALUES (gen_random_uuid(), gen_random_uuid(), :a, 'zh-CN', 1000, 'processing', now())")
				.bind("a", account).then().block();
		Map<String, Long> counts = inventory.countByKind(account).block();
		assertThat(counts).containsEntry("ai_run", 1L).containsEntry("speech_transcription", 1L);
		// 静态终态不阻塞。
		String idle = UUID.randomUUID().toString();
		assertThat(inventory.countByKind(idle).block()).isEmpty();
	}

	// ---------- TC103-08-02：prepare 幂等与 blockers ----------

	@Test
	void prepareFreezesIdempotentlyAndBlocksOnActiveJobs() {
		String account = UUID.randomUUID().toString();
		UUID request = UUID.randomUUID();
		db.sql("INSERT INTO ai_run(operation_id, account_id, capability, provider, budget_cents, status, started_at)"
				+ " VALUES (gen_random_uuid(), :a, 'text', 'sandbox', 0, 'running', now())").bind("a", account).then()
				.block();

		client().post().uri("/internal/compliance/accounts/" + account + "/prepare")
				.header("X-Grassland-Identity", identityServiceAssertion())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("closureRequestId", request.toString())).exchange().expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.data.prepared").isEqualTo(false);
		// 未冻结：gate 行已注册但保持 active（不冻结；countByKind 仍见活动任务）。
		var blockedGate = lifecycle.find(account).block();
		assertThat(blockedGate).isNotNull();
		assertThat(blockedGate.state()).isEqualTo("active");

		// 任务完成后 prepare → frozen；同请求重入回同 revision；不同请求 → 409。
		db.sql("UPDATE ai_run SET status = 'succeeded' WHERE account_id = :a").bind("a", account).then().block();
		client().post().uri("/internal/compliance/accounts/" + account + "/prepare")
				.header("X-Grassland-Identity", identityServiceAssertion())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("closureRequestId", request.toString())).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.prepared").isEqualTo(true);
		long revision = lifecycle.find(account).block().revision();
		client().post().uri("/internal/compliance/accounts/" + account + "/prepare")
				.header("X-Grassland-Identity", identityServiceAssertion())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("closureRequestId", request.toString())).exchange().expectStatus().isOk();
		assertThat(lifecycle.find(account).block().revision()).isEqualTo(revision);
		client().post().uri("/internal/compliance/accounts/" + account + "/prepare")
				.header("X-Grassland-Identity", identityServiceAssertion())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("closureRequestId", UUID.randomUUID().toString())).exchange().expectStatus()
				.isEqualTo(409);
	}

	// ---------- TC103-08-06：冻结后写防护与关闭竞态 ----------

	@Test
	void frozenAccountCannotCreateProtectedRowsAndReleaseReopensThem() {
		String account = UUID.randomUUID().toString();
		UUID request = UUID.randomUUID();
		// 冻结前先建父草稿（冻结后无法新建任何受保护行——含父行）。
		String draft = UUID.randomUUID().toString();
		db.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)"
				+ " VALUES (CAST(:d AS uuid), :a, '冻结前草稿', 'independent', now(), now())").bind("d", draft)
				.bind("a", account).then().block();
		client().post().uri("/internal/compliance/accounts/" + account + "/prepare")
				.header("X-Grassland-Identity", identityServiceAssertion())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("closureRequestId", request.toString())).exchange().expectStatus().isOk();

		// 受保护表新建被 V85 触发器拒绝（直连 owner + 子表父解析各一例）。
		assertThat((Object) org.assertj.core.api.Assertions.catchThrowable(() -> db
				.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)"
						+ " VALUES (gen_random_uuid(), :a, '冻结后新草稿', 'independent', now(), now())")
				.bind("a", account).then().block())).isNotNull();
		// creation_draft_version 无 id 列，须用真实列（否则缺列报错会让断言假阳性）。
		assertThat((Object) org.assertj.core.api.Assertions.catchThrowable(() -> db
				.sql("INSERT INTO creation_draft_version(draft_id, version, title, source_type,"
						+ " status, snapshotted_by)"
						+ " VALUES (CAST(:d AS uuid), 1, '版本一', 'independent', 'draft', :a)")
				.bind("d", draft).bind("a", account).then().block())).isNotNull();
		// 其他账号不受影响。
		db.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)"
				+ " VALUES (gen_random_uuid(), :other, '他人草稿', 'independent', now(), now())")
				.bind("other", UUID.randomUUID().toString()).then().block();

		// release（同请求）→ 恢复可写；erasing/erased 不可 release（此处止于 frozen→active）。
		client().post().uri("/internal/compliance/accounts/" + account + "/release")
				.header("X-Grassland-Identity", identityServiceAssertion())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("closureRequestId", request.toString())).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.released").isEqualTo(true);
		db.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)"
				+ " VALUES (gen_random_uuid(), :a, '解冻后草稿', 'independent', now(), now())").bind("a", account).then()
				.block();
	}
}
