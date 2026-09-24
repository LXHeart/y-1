package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanGrantService.ConnectionGrant;
import com.grassland.intelligence.digitalhuman.DigitalHumanGrantService.GrantBinding;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * 连接资格 IT（任务书 #105D C105D-02 / TC105D-02-01、TC105D-02-03）。
 *
 * <p>
 * 真实 Redis（原子 GETDEL）+ 真实 DB（turn 幂等/operation/epoch）；时间推进用注入 Clock（29,999ms 边界
 * 参数化），不 sleep。同 grant 并发两连接只赢一次；票据窗口唯一、过期无续命；执行资格同样单次。失败面刻 意不回 owner。
 */
class DigitalHumanGrantIT extends IntelligenceItSupport {

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void redisProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
		registry.add("digital-human.internal.allowed-ai-origins",
				() -> "https://ai.grassland.test,https://alt.grassland.test");
	}

	@Autowired
	ReactiveStringRedisTemplate redis;
	@Autowired
	DatabaseClient db;
	@Autowired
	DigitalHumanOperations operations;
	@Autowired
	TransactionalOperator transactions;

	private final String account = UUID.randomUUID().toString();
	private final PersonalActor actor = new PersonalActor(account);
	private final DigitalHumanInternalProperties properties = new DigitalHumanInternalProperties(false, 9143,
			new DigitalHumanInternalProperties.Tls(null, null, null, java.util.List.of()),
			java.util.List.of("https://ai.grassland.test", "https://alt.grassland.test"), Duration.ofSeconds(30),
			Duration.ofSeconds(30), Duration.ofSeconds(5));

	@BeforeEach
	void seed() {
		redis.execute(connection -> connection.serverCommands().flushDb().flux()).then().block(Duration.ofSeconds(5));
		db.sql("DELETE FROM dh_invocation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_session").then())
				.block(Duration.ofSeconds(10));
	}

	private UUID seedSession(String state, long leaseEpoch) {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'backend-1', CAST(:pf AS uuid),"
				+ " CAST(:c AS uuid), CAST('{}' AS jsonb), :state, now(), :lease)").bind("s", sessionId.toString())
				.bind("owner", account).bind("p", UUID.randomUUID().toString()).bind("pf", UUID.randomUUID().toString())
				.bind("c", UUID.randomUUID().toString()).bind("state", state).bind("lease", leaseEpoch).then()
				.block(Duration.ofSeconds(10));
		return sessionId;
	}

	private DigitalHumanGrantService service(Clock clock) {
		return new DigitalHumanGrantService(redis, db, operations, transactions, properties, clock);
	}

	// ---------- TC105D-02-01：单次核销 ----------

	@Test
	void tc105d_02_01_concurrentRedemptionWinsExactlyOnce() {
		UUID sessionId = seedSession("ready", 1);
		DigitalHumanGrantService grants = service(Clock.systemUTC());
		ConnectionGrant ticket = grants.issue(actor, sessionId, 1, "audio", "https://ai.grassland.test")
				.block(Duration.ofSeconds(10));
		assertThat(ticket.grant()).hasSize(43); // 32 字节 base64url 无填充
		assertThat(ticket.wsPath()).isEqualTo("/api/digital-human/sessions/" + sessionId + "/audio");

		// 并发两连接同 grant 同时 auth（两路并行订阅，各自独立事务）：Redis GETDEL 原子——恰好一个 accepted。
		UUID requestId = UUID.randomUUID();
		java.util.List<String> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);
		grants.consumeConnection(ticket.grant(), sessionId, 1, "https://ai.grassland.test", requestId, "pcm_s16le",
				16000, 1).subscribe(binding -> {
					outcomes.add("accepted");
					latch.countDown();
				}, error -> {
					outcomes.add(IntelligenceException.class.cast(error).code());
					latch.countDown();
				});
		grants.consumeConnection(ticket.grant(), sessionId, 1, "https://ai.grassland.test", requestId, "pcm_s16le",
				16000, 1).subscribe(binding -> {
					outcomes.add("accepted");
					latch.countDown();
				}, error -> {
					outcomes.add(IntelligenceException.class.cast(error).code());
					latch.countDown();
				});
		try {
			latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		assertThat(outcomes).as("恰好一个 accepted，另一个 dh_grant_invalid").containsExactlyInAnyOrder("accepted",
				"dh_grant_invalid");
		// 无第二 provider：失败核销面没有产生任何阶段调用（本卡 provider=0，经济键不新增）。
		assertThat(count("SELECT count(*) AS n FROM dh_invocation")).isZero();
		assertThat(count("SELECT count(*) AS n FROM dh_turn WHERE request_id = CAST('" + requestId + "' AS uuid)"))
				.as("幂等：同一 requestId 只一个 turn").isEqualTo(1);
		assertThat(count("SELECT count(*) AS n FROM dh_operation WHERE kind = 'turn_create'")).isEqualTo(1);
		// 服务端只存哈希：原文不在 Redis 键中。
		assertThat(redis.opsForValue().get("dh:grant:" + ticket.grant()).block(Duration.ofSeconds(5))).isNull();
	}

	@Test
	void tc105d_02_01_replayWithNewGrantContinuesOriginalTurn() {
		// K13.1：票据已消费/DB 失败后，用户拿新票据 + 原 requestId → 续原 turn，不建第二个 STT。
		UUID sessionId = seedSession("ready", 1);
		DigitalHumanGrantService grants = service(Clock.systemUTC());
		UUID requestId = UUID.randomUUID();
		GrantBinding first = grants
				.consumeConnection(
						grants.issue(actor, sessionId, 1, "audio", "https://ai.grassland.test")
								.block(Duration.ofSeconds(10)).grant(),
						sessionId, 1, "https://ai.grassland.test", requestId, "pcm_s16le", 16000, 1)
				.block(Duration.ofSeconds(10));
		GrantBinding replay = grants
				.consumeConnection(
						grants.issue(actor, sessionId, 1, "audio", "https://ai.grassland.test")
								.block(Duration.ofSeconds(10)).grant(),
						sessionId, 1, "https://ai.grassland.test", requestId, "pcm_s16le", 16000, 1)
				.block(Duration.ofSeconds(10));
		assertThat(replay.turnId()).isEqualTo(first.turnId());
		assertThat(replay.turnEpoch()).isEqualTo(first.turnEpoch());
		assertThat(count("SELECT count(*) AS n FROM dh_turn")).isEqualTo(1);
		assertThat(count("SELECT count(*) AS n FROM dh_operation WHERE kind = 'turn_create'")).isEqualTo(1);
		assertThat(db.sql("SELECT state FROM dh_session WHERE id = CAST(:s AS uuid)").bind("s", sessionId.toString())
				.map(r -> r.get("state", String.class)).one().block(Duration.ofSeconds(10)))
				.as("首个 accepted 已置 listening").isEqualTo("listening");
	}

	// ---------- TC105D-02-03：时限边界（29,999/30,000ms 参数化） ----------

	@Test
	void tc105d_02_03_grantWindowIsUniqueAndDoesNotRenew() {
		for (long elapsedMs : new long[]{29_999, 30_000}) {
			seedCleanupTurns();
			UUID sessionId = seedSession("ready", 1);
			Instant issuedAt = Instant.parse("2026-09-23T00:00:00Z");
			DigitalHumanGrantService issuer = service(Clock.fixed(issuedAt, ZoneOffset.UTC));
			ConnectionGrant ticket = issuer.issue(actor, sessionId, 1, "audio", "https://ai.grassland.test")
					.block(Duration.ofSeconds(10));
			DigitalHumanGrantService redeemer = service(Clock.fixed(issuedAt.plusMillis(elapsedMs), ZoneOffset.UTC));
			try {
				GrantBinding binding = redeemer.consumeConnection(ticket.grant(), sessionId, 1,
						"https://ai.grassland.test", UUID.randomUUID(), "pcm_s16le", 16000, 1)
						.block(Duration.ofSeconds(10));
				assertThat(elapsedMs).as("30 秒边界内核销成功").isLessThan(30_000);
				assertThat(binding.firstFrameDeadlineAt())
						.isEqualTo(issuedAt.plusMillis(elapsedMs).plus(Duration.ofSeconds(5)));
			} catch (IntelligenceException e) {
				assertThat(e.code()).as("过期票据 410 且无续命").isEqualTo("dh_grant_expired");
				assertThat(e.status()).isEqualTo(410);
				assertThat(elapsedMs).isEqualTo(30_000);
			}
		}
	}

	@Test
	void tc105d_02_03_wrongOriginLeaseOrChannelRejectedWithoutOwner() {
		UUID sessionId = seedSession("ready", 7);
		DigitalHumanGrantService grants = service(Clock.systemUTC());
		// 非法 origin 在签发面拒绝。
		for (String badOrigin : new String[]{null, "https://evil.example", "https://ai.grassland.test.evil"}) {
			try {
				grants.issue(actor, sessionId, 7, "audio", badOrigin).block(Duration.ofSeconds(10));
				throw new IllegalStateException("应当拒绝 origin=" + badOrigin);
			} catch (IntelligenceException e) {
				assertThat(e.status()).isEqualTo(403);
			}
		}
		// 合法票据被改 session/lease/origin 使用 → 全部拒绝且错误不含 owner。
		String ownerLeak = account;
		ConnectionGrant ticket = grants.issue(actor, sessionId, 7, "audio", "https://ai.grassland.test")
				.block(Duration.ofSeconds(10));
		assertRejectedWithoutOwner(grants.consumeConnection(ticket.grant(), UUID.randomUUID(), 7,
				"https://ai.grassland.test", UUID.randomUUID(), "pcm_s16le", 16000, 1), "换会话", ownerLeak);
		ConnectionGrant ticket2 = grants.issue(actor, sessionId, 7, "audio", "https://ai.grassland.test")
				.block(Duration.ofSeconds(10));
		assertRejectedWithoutOwner(grants.consumeConnection(ticket2.grant(), sessionId, 8, "https://ai.grassland.test",
				UUID.randomUUID(), "pcm_s16le", 16000, 1), "换租约", ownerLeak);
		ConnectionGrant ticket3 = grants.issue(actor, sessionId, 7, "audio", "https://ai.grassland.test")
				.block(Duration.ofSeconds(10));
		assertRejectedWithoutOwner(grants.consumeConnection(ticket3.grant(), sessionId, 7, "https://alt.grassland.test",
				UUID.randomUUID(), "pcm_s16le", 16000, 1), "换 origin", ownerLeak);
		// 格式非法：422。
		ConnectionGrant ticket4 = grants.issue(actor, sessionId, 7, "audio", "https://ai.grassland.test")
				.block(Duration.ofSeconds(10));
		assertRejectedWithoutOwner(grants.consumeConnection(ticket4.grant(), sessionId, 7, "https://ai.grassland.test",
				UUID.randomUUID(), "pcm_f32le", 44100, 2), "非法格式", ownerLeak);
		// 执行资格同样单次核销 + stage/hash 绑定（INTERNAL08 的 auth 面）。
		UUID invocationId = UUID.randomUUID();
		var valid = grants.issueExecution(account, invocationId, sessionId, InvocationStage.llm, "hash-1",
				Instant.now().plusSeconds(90)).block(Duration.ofSeconds(10));
		grants.consumeExecution(valid.grant(), invocationId, InvocationStage.llm, "hash-1")
				.block(Duration.ofSeconds(10));
		assertRejectedWithoutOwner(grants.consumeExecution(valid.grant(), invocationId, InvocationStage.llm, "hash-1"),
				"执行资格第二次", ownerLeak);
		var tampered = grants.issueExecution(account, invocationId, sessionId, InvocationStage.llm, "hash-1",
				Instant.now().plusSeconds(90)).block(Duration.ofSeconds(10));
		assertRejectedWithoutOwner(
				grants.consumeExecution(tampered.grant(), invocationId, InvocationStage.llm, "hash-tampered"),
				"输入 hash 被改", ownerLeak);
		var wrongStage = grants.issueExecution(account, invocationId, sessionId, InvocationStage.stt, "hash-1",
				Instant.now().plusSeconds(90)).block(Duration.ofSeconds(10));
		assertRejectedWithoutOwner(
				grants.consumeExecution(wrongStage.grant(), invocationId, InvocationStage.llm, "hash-1"), "stage 被改",
				ownerLeak);
	}

	private void assertRejectedWithoutOwner(Mono<?> attempt, String label, String owner) {
		try {
			attempt.block(Duration.ofSeconds(10));
			throw new IllegalStateException("应当拒绝：" + label);
		} catch (IntelligenceException e) {
			assertThat(e.getMessage()).as(label + " 拒绝面不回 owner").doesNotContain(owner);
		}
	}

	private void seedCleanupTurns() {
		db.sql("DELETE FROM dh_invocation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_session").then())
				.block(Duration.ofSeconds(10));
	}

	private long count(String sql) {
		return db.sql(sql).map((r, m) -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10));
	}
}
