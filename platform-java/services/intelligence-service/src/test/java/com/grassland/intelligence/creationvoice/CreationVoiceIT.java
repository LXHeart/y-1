package com.grassland.intelligence.creationvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.compliance.PersonalDataErasureRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * 私有文风档案 CRUD/并发/迁移/隔离/屏障真实集成测试（任务书 #108 C-02 / W17）。
 *
 * <p>
 * 覆盖 TC-C02-001（空槽→建槽→幂等重放→旧 revision 冲突→清空）、TC-C02-002（并发 CAS 与丢响应重放）、
 * TC-C02-003（A/B/管理员越权、gate 冻结竞态、注销擦除）与 AC-003/004。真库（Flyway V1～V94 全量迁移，
 * 含新表与专用屏障触发器）+ 真实 HTTP（WebTestClient + 断言头），无 mock 业务行为。
 */
class CreationVoiceIT extends IntelligenceItSupport {

	@Autowired
	private CreationVoiceService voices;
	@Autowired
	private PersonalDataErasureRepository erasure;
	@Autowired
	private DatabaseClient db;
	@Autowired
	private TransactionalOperator transactions;

	private String account;
	private String identity;

	@BeforeEach
	void seedAccount() {
		account = "cv-" + UUID.randomUUID();
		identity = sign(account, "merchant");
	}

	@AfterEach
	void cleanRows() {
		db.sql("DELETE FROM creation_voice_profile WHERE account_id = :a").bind("a", account).then().then(
				db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id = :a").bind("a", account).then())
				.block(Duration.ofSeconds(10));
	}

	@Test
	void previewSameTextIsReadOnlyAndRejectsInvalidReasonsAndFrozenAccounts() {
		var body = Map.of("original", "原文", "edited", "原文", "reason", "style", "platform", "zhihu", "genre", "article");
		client().post().uri("/api/creation-voice/consumer/preview").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.candidates.length()").isEqualTo(0);
		assertThat(voices.get(account, "consumer").block().revision()).isZero();
		for (String reason : List.of("fact", "one-off")) {
			var invalid = new java.util.HashMap<>(body);
			invalid.put("reason", reason);
			client().post().uri("/api/creation-voice/consumer/preview").header("X-Grassland-Identity", identity)
					.contentType(MediaType.APPLICATION_JSON).bodyValue(invalid).exchange().expectStatus()
					.isBadRequest();
		}
		registerGateRow();
		db.sql("UPDATE intelligence_account_lifecycle SET state = 'frozen' WHERE account_id = :a").bind("a", account)
				.then().block();
		client().post().uri("/api/creation-voice/consumer/preview").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isEqualTo(409);
	}

	// ---------- TC-C02-001：空槽 → 建槽 → 幂等 → 冲突 → 清空 ----------

	@Test
	void missingSlotReturnsEmptyDefaultsAndRequiresLogin() {
		client().get().uri("/api/creation-voice/consumer").exchange().expectStatus().isUnauthorized();

		client().get().uri("/api/creation-voice/consumer").header("X-Grassland-Identity", identity).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true).jsonPath("$.data.role")
				.isEqualTo("consumer").jsonPath("$.data.revision").isEqualTo(0).jsonPath("$.data.enabled")
				.isEqualTo(false).jsonPath("$.data.rules").isArray().jsonPath("$.data.rules.length()").isEqualTo(0)
				.jsonPath("$.data.samples.length()").isEqualTo(0).jsonPath("$.data.updatedAt")
				.value(org.hamcrest.Matchers.nullValue());
	}

	@Test
	void firstPutCreatesRevisionOneAndRoundTripsEncrypted() {
		Map<String, Object> body = putBody(0, true, List.of("少用感叹号", "价格写具体数字"),
				List.of(sample("note", "周六去A店，拿铁38元，二楼安静。")));
		client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.success").isEqualTo(true).jsonPath("$.data.revision").isEqualTo(1)
				.jsonPath("$.data.enabled").isEqualTo(true).jsonPath("$.data.updatedAt").isNotEmpty();

		// GET 读回同一内容（解密+规范解析路径）。
		client().get().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.revision").isEqualTo(1)
				.jsonPath("$.data.rules.length()").isEqualTo(2).jsonPath("$.data.rules[0]").isEqualTo("少用感叹号")
				.jsonPath("$.data.samples[0].platform").isEqualTo("xiaohongshu").jsonPath("$.data.samples[0].genre")
				.isEqualTo("note").jsonPath("$.data.samples[0].consent").isEqualTo(true);

		// 落库的是密文+摘要，不是明文（§7.1：EnvelopeEncryption；hash=含 enabled 的规范内容摘要）。
		var row = db
				.sql("SELECT encrypted_payload, payload_hash FROM creation_voice_profile"
						+ " WHERE account_id = :a AND role = 'merchant'")
				.bind("a", account).map((r) -> Map.of("encrypted_payload", r.get("encrypted_payload", String.class),
						"payload_hash", r.get("payload_hash", String.class)))
				.one().block(Duration.ofSeconds(10));
		assertThat(row).isNotNull();
		assertThat(row.get("encrypted_payload")).doesNotContain("少用感叹号").doesNotContain("拿铁38元");
		assertThat(String.valueOf(row.get("payload_hash"))).matches("[0-9a-f]{64}");
	}

	@Test
	void sameContentReplayIsIdempotentAndStaleRevisionConflicts() {
		put(0, true, List.of("规则一"), List.of());
		// 相同目标内容重放（即使 expectedRevision 已落后）→ 返回当前值不递增（§7.4 幂等先于 revision 比较）。
		put(0, true, List.of("规则一"), List.of());
		assertThat(currentRevision()).isEqualTo(1);

		// 不同内容 + 旧 revision → 409 VOICE_REVISION_CONFLICT，行不变。
		client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(putBody(0, true, List.of("别的规则"), List.of()))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("VOICE_REVISION_CONFLICT");
		assertThat(currentRevision()).isEqualTo(1);

		// 正确 revision 的不同内容 → rev2；再拿 rev1 重放旧不同内容 → 409 不回退（TC-C02-002 丢响应分支）。
		put(1, true, List.of("规则二"), List.of());
		assertThat(currentRevision()).isEqualTo(2);
		client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(putBody(1, true, List.of("规则一"), List.of()))
				.exchange().expectStatus().isEqualTo(409);
		assertThat(currentRevision()).isEqualTo(2);
	}

	@Test
	void clearKeepsSlotWithIncrementedRevisionAndOtherRolesUntouched() {
		put(0, true, List.of("规则一"), List.of(sample("note", "范文一")));
		put(identity, 0, "consumer", true, List.of("商家规则"), List.of());

		put(1, false, List.of(), List.of()); // 清空 = 空列表 + enabled=false（RULE-009）

		client().get().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.revision").isEqualTo(2).jsonPath("$.data.enabled")
				.isEqualTo(false).jsonPath("$.data.rules.length()").isEqualTo(0).jsonPath("$.data.samples.length()")
				.isEqualTo(0);
		// 其他槽位不动。
		client().get().uri("/api/creation-voice/consumer").header("X-Grassland-Identity", identity).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.revision").isEqualTo(1).jsonPath("$.data.rules[0]")
				.isEqualTo("商家规则");
	}

	// ---------- TC-C02-003 / AC-003：账户与角色隔离、越权拒绝 ----------

	@Test
	void profilesAreIsolatedByAccountAndRoleAndOwnerForgeryIsRejected() {
		String accountB = "cv-" + UUID.randomUUID();
		String admin = "cv-admin-" + UUID.randomUUID();
		String org = "org-" + UUID.randomUUID();
		String identityA = signWithOrg(account, org);
		String identityB = signWithOrg(accountB, org);
		try {
			// A 四个角色各存不同 canary；B 同角色存自己的。
			put(identityA, 0, "consumer", List.of("A-consumer-规则"), List.of());
			put(identityA, 0, "merchant", List.of("A-merchant-规则"), List.of());
			put(identityA, 0, "commercial-creator", List.of("A-biz-规则"), List.of());
			put(identityA, 0, "researcher", List.of("A-researcher-规则"), List.of());
			put(identityB, 0, "consumer", List.of("B-consumer-规则"), List.of());

			// A 的 merchant 不读 consumer；B 只读 B；同组织不是共享授权。
			assertThat(firstRule(identityA, "merchant")).isEqualTo("A-merchant-规则");
			assertThat(firstRule(identityA, "consumer")).isEqualTo("A-consumer-规则");
			assertThat(firstRule(identityB, "consumer")).isEqualTo("B-consumer-规则");

			// 管理员断言（另一账号）无代读能力：读的是管理员自己的空槽。
			client().get().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", signAdmin(admin))
					.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.revision").isEqualTo(0)
					.jsonPath("$.data.rules.length()").isEqualTo(0);

			// body 伪造 owner（accountId/organizationId）→ 400 拒绝，不写库不读他人数据。
			client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identityB)
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(putBody(0, true, List.of("伪造规则"), List.of(), "accountId", account)).exchange()
					.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VOICE_INVALID_INPUT");
			client().put().uri("/api/creation-voice/merchant?accountId=" + account)
					.header("X-Grassland-Identity", identityB).contentType(MediaType.APPLICATION_JSON)
					.bodyValue(putBody(0, true, List.of("伪造规则"), List.of())).exchange().expectStatus().isBadRequest()
					.expectBody().jsonPath("$.code").isEqualTo("VOICE_INVALID_INPUT");
			for (String query : List.of("accountId=" + account, "organizationId=" + org, "unknown=x")) {
				client().get().uri("/api/creation-voice/merchant?" + query).header("X-Grassland-Identity", identityB)
						.exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code")
						.isEqualTo("VOICE_INVALID_INPUT");
			}
			client().get().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identityB).exchange()
					.expectStatus().isOk().expectBody().jsonPath("$.data.revision").isEqualTo(0);
			assertThat(firstRule(identityA, "merchant")).isEqualTo("A-merchant-规则");
		} finally {
			db.sql("DELETE FROM creation_voice_profile WHERE account_id = :a").bind("a", accountB).then()
					.then(db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id = :a").bind("a", accountB)
							.then())
					.then(db.sql("DELETE FROM creation_voice_profile WHERE account_id = :a").bind("a", admin).then())
					.then(db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id = :a").bind("a", admin)
							.then())
					.block(Duration.ofSeconds(10));
		}
	}

	// ---------- TC-C02-003 / AC-004：冻结屏障与直接 SQL 绕过 ----------

	@Test
	void frozenGateBlocksPutGetAndDirectSqlThenErasureRemovesEverything() {
		put(0, true, List.of("冻结前规则"), List.of(sample("note", "冻结前范文")));

		db.sql("UPDATE intelligence_account_lifecycle SET state = 'frozen', frozen_at = now()"
				+ " WHERE account_id = :a").bind("a", account).then().block(Duration.ofSeconds(10));

		// HTTP PUT/GET 均被屏障拒绝（409 既有屏障语义）。
		client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(putBody(1, true, List.of("冻结期规则"), List.of()))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("account_closure_barrier");
		client().get().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity).exchange()
				.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("account_closure_barrier");

		// 直接 SQL 绕过应用层：V94 专用触发器拒绝 INSERT/UPDATE（先前行保留、无行增长）。
		assertThatThrownBy(() -> db
				.sql("INSERT INTO creation_voice_profile"
						+ " (account_id, role, revision, enabled, encrypted_payload, payload_hash)"
						+ " VALUES (:a, 'consumer', 1, false, 'x', 'y')")
				.bind("a", account).then().block(Duration.ofSeconds(10)))
				.hasMessageContaining("account_closure_barrier");
		assertThatThrownBy(() -> db.sql("UPDATE creation_voice_profile SET enabled = true" + " WHERE account_id = :a")
				.bind("a", account).then().block(Duration.ofSeconds(10)))
				.hasMessageContaining("account_closure_barrier");
		Long rows = db.sql("SELECT count(*) AS c FROM creation_voice_profile WHERE account_id = :a").bind("a", account)
				.map((r) -> r.get("c", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(rows).isEqualTo(1);

		// 真实擦除批次：行删空、密文与摘要全无；旧偏好表保持自己的既有清理语义（style_preferences kind 不变）。
		db.sql("UPDATE intelligence_account_lifecycle SET state = 'erasing'" + " WHERE account_id = :a")
				.bind("a", account).then().block(Duration.ofSeconds(10));
		Long deleted = erasure.runBatch("creation_voice_profile", account, 100).block(Duration.ofSeconds(10));
		assertThat(deleted).isEqualTo(1);
		Long residue = db.sql("SELECT count(*) AS c FROM creation_voice_profile WHERE account_id = :a")
				.bind("a", account).map((r) -> r.get("c", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(residue).isZero();
		Long remaining = erasure.residueByKind(account).block(Duration.ofSeconds(10)).get("creation_voice_profile");
		assertThat(remaining).isZero();
	}

	@Test
	void putWaitsForHeldGateLockThenRejectsAfterFreezeCommits() throws Exception {
		// TC-C02-003：先持 gate 冻结事务（JDBC 行锁），再发 PUT——PUT 必须等锁释放且随后被拒，无行增长。
		registerGateRow();
		try (Connection jdbc = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword())) {
			jdbc.setAutoCommit(false);
			try (var statement = jdbc.createStatement()) {
				statement.execute("UPDATE intelligence_account_lifecycle SET state = 'frozen'" + " WHERE account_id = '"
						+ account + "'");
			}
			CompletableFuture<Integer> pending = supplyAsyncPut(); // 阻塞在 gate FOR UPDATE
			Thread.sleep(600); // 等待 PUT 真正抵达锁等待（R2DBC 不会占 JDBC 锁）。
			jdbc.commit(); // 冻结提交。

			Integer status = pending.get(15, TimeUnit.SECONDS);
			assertThat(status).isEqualTo(409);
		}
		Long rows = db.sql("SELECT count(*) AS c FROM creation_voice_profile WHERE account_id = :a").bind("a", account)
				.map((r) -> r.get("c", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(rows).isZero();
	}

	// ---------- TC-C02-002：并发 CAS ----------

	@Test
	void readHoldsGateLockUntilProfileHasBeenRead() throws Exception {
		put(0, true, List.of("读取期间保持账户锁"), List.of());
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
				Connection freezer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
						POSTGRES.getPassword())) {
			blocker.setAutoCommit(false);
			freezer.setAutoCommit(false);
			try (var statement = blocker.createStatement()) {
				statement.execute("LOCK TABLE creation_voice_profile IN ACCESS EXCLUSIVE MODE");
			}
			var reading = pool.submit(() -> voices.get(account, "merchant").block(Duration.ofSeconds(15)));
			try {
				// 等待真正到达档案 SELECT，而非用固定 sleep 猜测事务时序。
				boolean waiting = false;
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
				while (System.nanoTime() < deadline) {
					try (var statement = blocker.createStatement()) {
						statement.execute("SELECT pg_stat_clear_snapshot()");
					}
					try (var statement = blocker.createStatement(); var rows = statement.executeQuery("""
							SELECT count(*) FROM pg_stat_activity
							 WHERE pid <> pg_backend_pid() AND wait_event_type = 'Lock'
							   AND query LIKE 'SELECT revision, enabled, encrypted_payload%'
							""")) {
						rows.next();
						waiting = rows.getLong(1) > 0;
					}
					if (waiting) {
						break;
					}
					Thread.sleep(20);
				}
				assertThat(waiting).as("档案读取已被表锁阻塞").isTrue();
				try (var statement = freezer.createStatement()) {
					statement.execute("SET LOCAL lock_timeout = '300ms'");
					assertThatThrownBy(() -> statement.executeUpdate(
							"UPDATE intelligence_account_lifecycle SET state = 'frozen' WHERE account_id = '" + account
									+ "'"))
							.isInstanceOf(java.sql.SQLException.class)
							.extracting(error -> ((java.sql.SQLException) error).getSQLState()).isEqualTo("55P03");
				} finally {
					freezer.rollback();
				}
			} finally {
				blocker.rollback();
			}
			assertThat(reading.get(15, TimeUnit.SECONDS).revision()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void concurrentDifferentContentPutAllowsExactlyOneWinner() throws Exception {
		put(0, true, List.of("初始规则"), List.of());
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			AtomicInteger okCount = new AtomicInteger();
			AtomicInteger conflictCount = new AtomicInteger();
			Runnable writerY = () -> awaitAndPut(start, 1, List.of("并发内容Y"), okCount, conflictCount);
			Runnable writerZ = () -> awaitAndPut(start, 1, List.of("并发内容Z"), okCount, conflictCount);
			var futureY = pool.submit(writerY);
			var futureZ = pool.submit(writerZ);
			start.countDown();
			futureY.get(30, TimeUnit.SECONDS);
			futureZ.get(30, TimeUnit.SECONDS);
			assertThat(okCount.get()).isEqualTo(1);
			assertThat(conflictCount.get()).isEqualTo(1);

			assertThat(currentRevision()).isEqualTo(2);
			String winner = firstRule(identity, "merchant");
			assertThat(winner).isIn("并发内容Y", "并发内容Z");
			// 密文与 hash 与胜出内容一致（半行/旧内容残留即失败）。
			var row = db
					.sql("SELECT payload_hash FROM creation_voice_profile"
							+ " WHERE account_id = :a AND role = 'merchant'")
					.bind("a", account).map((r) -> r.get("payload_hash", String.class)).one()
					.block(Duration.ofSeconds(10));
			String expectedHash = sha256Hex(CreationVoiceTypes.canonicalContent(true, List.of(winner), List.of()));
			assertThat(row).isEqualTo(expectedHash);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void concurrentFirstCreateOnlyOneSlotWins() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			AtomicInteger okCount = new AtomicInteger();
			AtomicInteger conflictCount = new AtomicInteger();
			var first = pool.submit(() -> awaitAndPut(start, 0, List.of("首建甲"), okCount, conflictCount));
			var second = pool.submit(() -> awaitAndPut(start, 0, List.of("首建乙"), okCount, conflictCount));
			start.countDown();
			first.get(30, TimeUnit.SECONDS);
			second.get(30, TimeUnit.SECONDS);
			assertThat(okCount.get()).isEqualTo(1);
			assertThat(conflictCount.get()).isEqualTo(1);
			assertThat(currentRevision()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	// ---------- 迁移与约束（真实 PG：V94 表/触发器/CHECK） ----------

	@Test
	void migrationCreatesGuardedTableWithRoleAndRevisionChecks() {
		Long columns = db.sql("""
				SELECT count(*) AS c FROM information_schema.columns
				 WHERE table_schema = 'public' AND table_name = 'creation_voice_profile'
				   AND column_name IN ('account_id','role','revision','enabled','encrypted_payload',
				                       'payload_hash','updated_at')
				""").map((r) -> r.get("c", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(columns).isEqualTo(7L);

		Long triggerCount = db.sql("""
				SELECT count(*) AS c FROM pg_trigger t
				 JOIN pg_class c ON c.oid = t.tgrelid
				 WHERE c.oid = 'public.creation_voice_profile'::regclass
				   AND t.tgname = 'trg_guard_creation_voice_profile'
				   AND t.tgenabled IN ('O','A')
				""").map((r) -> r.get("c", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(triggerCount).isEqualTo(1L);
		String action = db
				.sql("""
						SELECT pg_get_triggerdef(t.oid) AS def FROM pg_trigger t
						 JOIN pg_class c ON c.oid = t.tgrelid
						 WHERE c.oid = 'public.creation_voice_profile'::regclass AND t.tgname = 'trg_guard_creation_voice_profile'
						""")
				.map((r) -> r.get("def", String.class)).one().block(Duration.ofSeconds(10));
		assertThat(action).contains("BEFORE INSERT OR UPDATE");

		// role CHECK：非法角色拒绝（gate 为 active 仍被约束拦，证明拒绝来自 role 而非屏障）。
		registerGateRow();
		assertThatThrownBy(() -> db
				.sql("INSERT INTO creation_voice_profile"
						+ " (account_id, role, revision, enabled, encrypted_payload, payload_hash)"
						+ " VALUES (:a, 'admin', 1, false, 'x', 'y')")
				.bind("a", account).then().block(Duration.ofSeconds(10)))
				.hasMessageContaining("violates check constraint");
		// revision CHECK：0 拒绝。
		assertThatThrownBy(() -> db
				.sql("INSERT INTO creation_voice_profile"
						+ " (account_id, role, revision, enabled, encrypted_payload, payload_hash)"
						+ " VALUES (:a, 'merchant', 0, false, 'x', 'y')")
				.bind("a", account).then().block(Duration.ofSeconds(10)))
				.hasMessageContaining("violates check constraint");
	}

	// ---------- 校验边界（服务端 400，TC-C04-001 的 API 侧子集） ----------

	@Test
	void invalidInputsAreRejectedWithVoiceInvalidInput() {
		String duplicateId = UUID.randomUUID().toString();
		List<Map<String, Object>> cases = List.of(
				// 未知字段（含伪造账户参数）
				Map.of("expectedRevision", 0, "enabled", false, "rules", List.of(), "samples", List.of(),
						"organizationId", "org-1"),
				// enabled=true 但空内容
				Map.of("expectedRevision", 0, "enabled", true, "rules", List.of(), "samples", List.of()),
				// 负数 revision
				Map.of("expectedRevision", -1, "enabled", false, "rules", List.of(), "samples", List.of()),
				// 非整数 revision
				Map.of("expectedRevision", 1.5, "enabled", false, "rules", List.of(), "samples", List.of()),
				// 缺 enabled（null 不等于缺省）
				Map.of("expectedRevision", 0, "rules", List.of(), "samples", List.of()),
				// 规则 301 字符
				Map.of("expectedRevision", 0, "enabled", false, "rules", List.of("字".repeat(301)), "samples",
						List.of()),
				// 31 条规则
				Map.of("expectedRevision", 0, "enabled", false, "rules",
						java.util.stream.IntStream.rangeClosed(1, 31).mapToObj((i) -> "规则" + i).toList(), "samples",
						List.of()),
				// 6 份范文
				Map.of("expectedRevision", 0, "enabled", false, "rules", List.of(), "samples",
						java.util.stream.IntStream.rangeClosed(1, 6).mapToObj((i) -> sample("note", "范文" + i))
								.toList()),
				// 范文正文 2001 字符
				Map.of("expectedRevision", 0, "enabled", false, "rules", List.of(), "samples",
						List.of(Map.of("id", UUID.randomUUID().toString(), "platform", "zhihu", "genre", "article",
								"text", "字".repeat(2001), "consent", true))),
				// consent false
				Map.of("expectedRevision", 0, "enabled", false, "rules", List.of(), "samples",
						List.of(Map.of("id", UUID.randomUUID().toString(), "platform", "zhihu", "genre", "article",
								"text", "正文", "consent", false))),
				// 非法 platform
				Map.of("expectedRevision", 0, "enabled", false, "rules", List.of(), "samples",
						List.of(Map.of("id", UUID.randomUUID().toString(), "platform", "twitter", "genre", "article",
								"text", "正文", "consent", true))),
				// 范文 id 重复（同 id 两份）
				Map.of("expectedRevision", 0, "enabled", false, "rules", List.of(), "samples",
						List.of(Map.of("id", duplicateId, "platform", "dianping", "genre", "note", "text", "一",
								"consent", true),
								Map.of("id", duplicateId, "platform", "dianping", "genre", "note", "text", "二",
										"consent", true))));
		for (Map<String, Object> body : cases) {
			client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
					.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest()
					.expectBody().jsonPath("$.code").isEqualTo("VOICE_INVALID_INPUT");
		}
		// 非法 role 路径同样 400（不落 500/404）。
		client().get().uri("/api/creation-voice/admin").header("X-Grassland-Identity", identity).exchange()
				.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VOICE_INVALID_INPUT");
		// 规则精确去重保序。
		put(0, false, List.of("规则A", "规则B", "规则A"), List.of());
		client().get().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.rules.length()").isEqualTo(2)
				.jsonPath("$.data.rules[0]").isEqualTo("规则A");
	}

	@Test
	void oversizedCanonicalPayloadIsRejected() {
		// 字段上限内合法、但 JSON 转义后超 64KiB（RULE-006 全 payload 上限；超限拒绝不截断）。
		// 规则需两两不同（服务端精确去重会折叠相同规则）：299 个控制符 + 1 个区分字符 = 每条 300 units。
		List<String> rules = java.util.stream.IntStream.rangeClosed(1, 30)
				.mapToObj((i) -> "\u0001".repeat(299) + (char) ('a' + i)).toList();
		List<Map<String, Object>> samples = java.util.stream.IntStream
				.rangeClosed(1, 3).mapToObj((i) -> Map.<String, Object>of("id", UUID.randomUUID().toString(),
						"platform", "dianping", "genre", "note", "text", "\u0001".repeat(2000), "consent", true))
				.toList();
		Map<String, Object> body = Map.of("expectedRevision", 0, "enabled", false, "rules", rules, "samples", samples);
		client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest()
				.expectBody().jsonPath("$.code").isEqualTo("VOICE_INVALID_INPUT");
	}

	// ---------- 密钥缺失 fail-loud（不静默、不降级） ----------

	@Test
	void missingEncryptionKeyFailsLoudInsteadOfSilentSuccess() {
		CreationVoiceRepository repositoryWithoutCrypto = new CreationVoiceRepository(db, emptyEncryptionProvider(),
				transactions);
		Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> repositoryWithoutCrypto
				.compareAndSet(account, "merchant", true, List.of("规则"), List.of(),
						CreationVoiceTypes.canonicalContent(true, List.of("规则"), List.of()), 0)
				.block(Duration.ofSeconds(10)));
		assertThat(thrown).isInstanceOf(IntelligenceException.class);
		IntelligenceException error = (IntelligenceException) thrown;
		assertThat(error.status()).isEqualTo(503);
		assertThat(error.code()).isEqualTo("VOICE_UNAVAILABLE");
	}

	@Test
	void legacyStylePreferencesTableUntouchedByVoiceWrites() {
		put(0, true, List.of("规则"), List.of());
		Long legacyRows = db.sql("SELECT count(*) AS c FROM intelligence_style_preferences WHERE account_id = :a")
				.bind("a", account).map((r) -> r.get("c", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(legacyRows).isZero();
	}

	// ---------- helpers ----------

	private void put(long expectedRevision, boolean enabled, List<String> rules, List<Map<String, Object>> samples) {
		put(identity, expectedRevision, "merchant", enabled, rules, samples);
	}

	private void put(String identityHeader, long expectedRevision, String role, List<String> rules,
			List<Map<String, Object>> samples) {
		put(identityHeader, expectedRevision, role, false, rules, samples);
	}

	private void put(String identityHeader, long expectedRevision, String role, boolean enabled, List<String> rules,
			List<Map<String, Object>> samples) {
		client().put().uri("/api/creation-voice/" + role).header("X-Grassland-Identity", identityHeader)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(putBody(expectedRevision, enabled, rules, samples))
				.exchange().expectStatus().isOk();
	}

	private void put(long expectedRevision, boolean enabled, List<String> rules, List<Map<String, Object>> samples,
			String extraKey, Object extraValue) {
		Map<String, Object> body = putBody(expectedRevision, enabled, rules, samples);
		body.put(extraKey, extraValue);
		client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
	}

	private static Map<String, Object> putBody(long expectedRevision, boolean enabled, List<String> rules,
			List<Map<String, Object>> samples) {
		return new java.util.LinkedHashMap<>(
				Map.of("expectedRevision", expectedRevision, "enabled", enabled, "rules", rules, "samples", samples));
	}

	private static Map<String, Object> putBody(long expectedRevision, boolean enabled, List<String> rules,
			List<Map<String, Object>> samples, String extraKey, Object extraValue) {
		Map<String, Object> body = putBody(expectedRevision, enabled, rules, samples);
		body.put(extraKey, extraValue);
		return body;
	}

	private static Map<String, Object> sample(String genre, String text) {
		return Map.of("id", UUID.randomUUID().toString(), "platform", "xiaohongshu", "genre", genre, "text", text,
				"consent", true);
	}

	private String firstRule(String identityHeader, String role) {
		byte[] body = client().get().uri("/api/creation-voice/" + role).header("X-Grassland-Identity", identityHeader)
				.exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("rules").path(0)
					.asText();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private long currentRevision() {
		Long revision = db
				.sql("SELECT revision FROM creation_voice_profile" + " WHERE account_id = :a AND role = 'merchant'")
				.bind("a", account).map((r) -> r.get("revision", Long.class)).one().block(Duration.ofSeconds(10));
		return revision == null ? 0 : revision;
	}

	private void registerGateRow() {
		db.sql("INSERT INTO intelligence_account_lifecycle(account_id) VALUES (:a)"
				+ " ON CONFLICT (account_id) DO NOTHING").bind("a", account).then().block(Duration.ofSeconds(10));
	}

	private CompletableFuture<Integer> supplyAsyncPut() {
		return CompletableFuture.supplyAsync(
				() -> client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
						.contentType(MediaType.APPLICATION_JSON).bodyValue(putBody(0, true, List.of("竞态规则"), List.of()))
						.exchange().returnResult(Integer.class).getStatus().value());
	}

	private void awaitAndPut(CountDownLatch start, long expectedRevision, List<String> rules, AtomicInteger okCount,
			AtomicInteger conflictCount) {
		try {
			start.await(10, TimeUnit.SECONDS);
			Integer status = client().put().uri("/api/creation-voice/merchant").header("X-Grassland-Identity", identity)
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(putBody(expectedRevision, true, rules, List.of())).exchange().returnResult(Integer.class)
					.getStatus().value();
			if (status == 200) {
				okCount.incrementAndGet();
			} else if (status == 409) {
				conflictCount.incrementAndGet();
			} else {
				throw new IllegalStateException("unexpected status " + status);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static String sha256Hex(String content) {
		try {
			byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
					.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static org.springframework.beans.factory.ObjectProvider<com.grassland.crypto.EnvelopeEncryption> emptyEncryptionProvider() {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public com.grassland.crypto.EnvelopeEncryption getObject(Object... args) {
				return null;
			}

			@Override
			public com.grassland.crypto.EnvelopeEncryption getObject() {
				return null;
			}

			@Override
			public com.grassland.crypto.EnvelopeEncryption getIfAvailable() {
				return null;
			}

			@Override
			public com.grassland.crypto.EnvelopeEncryption getIfUnique() {
				return null;
			}
		};
	}
}
