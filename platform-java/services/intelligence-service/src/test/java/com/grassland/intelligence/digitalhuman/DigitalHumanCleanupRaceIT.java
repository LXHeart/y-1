package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.compliance.IntelligenceAccountLifecycleRepository;
import com.grassland.intelligence.compliance.PersonalDataErasureService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 删除中写入并发屏障 IT（任务书 #105G C105G-02 / TC105G-02-02）：freeze 与 turn/save/晚回调以
 * 两个独立事务（各自 auto-commit）+ 屏障控制先后交错提交——冻结前写入进入清单随注销删除；冻结后 新写入/新引用被
 * dh_guard_personal_write 拒绝（account_closure_barrier，无内容逃过清单）；
 * 终态仅允许单向收尾（recording→saved 放行、倒退拒绝）；erased 后晚回调 0 行不复活。
 *
 * <p>
 * 真实 DB/触发器/账号锁/易失 Redis；无外部 provider 调用（本用例资源不含形象/远端句柄）。
 */
class DigitalHumanCleanupRaceIT extends IntelligenceItSupport {

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void redisProps(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@Autowired
	private DatabaseClient db;
	@Autowired
	private IntelligenceAccountLifecycleRepository gate;
	@Autowired
	private PersonalDataErasureService erasure;
	@Autowired
	private DigitalHumanLifecycleService dhLifecycle;
	@Autowired
	private ReactiveStringRedisTemplate redis;

	@BeforeEach
	void seed() {
		cleanup();
	}

	/** 双端自清（共享容器防跨类污染）。 */
	@AfterEach
	void drainResidue() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM dh_cleanup WHERE owner_account_id LIKE 'dh-g2r-%'").then()
				.then(db.sql("DELETE FROM dh_recording WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_invocation WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_operation WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_transcript WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_event WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_turn WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_session WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_preview WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_profile_revision WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM dh_profile WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM content_asset WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM media_reference WHERE owner_account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM personal_data_erasure_object WHERE manifest_id IN"
						+ " (SELECT id FROM personal_data_erasure_manifest WHERE account_id LIKE 'dh-g2r-%')").then())
				.then(db.sql("DELETE FROM personal_data_erasure_step WHERE manifest_id IN"
						+ " (SELECT id FROM personal_data_erasure_manifest WHERE account_id LIKE 'dh-g2r-%')").then())
				.then(db.sql("DELETE FROM personal_data_erasure_manifest WHERE account_id LIKE 'dh-g2r-%'").then())
				.then(db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id LIKE 'dh-g2r-%'").then())
				.block(Duration.ofSeconds(20));
	}

	private void exec(String sql, String... pairs) {
		var spec = db.sql(sql);
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			spec = spec.bind(pairs[i], pairs[i + 1]);
		}
		spec.then().block(Duration.ofSeconds(5));
	}

	private long count(String table, String owner) {
		return db.sql("SELECT count(*) AS n FROM " + table + " WHERE owner_account_id = :o").bind("o", owner)
				.map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return String.valueOf(current.getMessage());
	}

	/** 屏障约定：非 active 态写入必须被拒，消息含 account_closure_barrier。 */
	private String expectBarrierRejected(String what, Runnable insert) {
		Throwable rejected = null;
		try {
			insert.run();
		} catch (RuntimeException failure) {
			rejected = failure;
		}
		assertThat(rejected).as(what + " 冻结后写入被拒").isNotNull();
		assertThat(rootMessage(rejected)).as(what + " 触发器屏障").contains("account_closure_barrier");
		return rootMessage(rejected);
	}

	// ---------- TC105G-02-02：freeze 与 turn/save/晚回调并发屏障 ----------

	@Test
	void tc105g_02_02_freezeVersusTurnSaveAndLateCallbackBarriers() {
		String a = "dh-g2r-a-" + UUID.randomUUID();
		String b = "dh-g2r-b-" + UUID.randomUUID();

		// —— 事务 1（active）：冻结前正常写入——清单来源。 ——
		UUID profileId = UUID.randomUUID();
		exec("INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)"
				+ " VALUES (CAST(:id AS uuid), :o, '并发角色', 1, 'active')", "id", profileId.toString(), "o", a);
		exec("INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting, tone,"
				+ " avatar_id, avatar_revision, voice_id, catalog_version) VALUES (gen_random_uuid(), :o,"
				+ " CAST(:id AS uuid), 1, 'p', 'g', 'natural', gen_random_uuid(), 1, 'v', 1)", "id",
				profileId.toString(), "o", a);
		UUID sessionId = UUID.randomUUID();
		exec("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, content_epoch)"
				+ " VALUES (CAST(:s AS uuid), :o, gen_random_uuid(), 1, 'n', 'mock', gen_random_uuid(),"
				+ " gen_random_uuid(), '{}'::jsonb, 'ended', now(), 3)", "s", sessionId.toString(), "o", a);
		// 冻结前最后一刻的 turn（事务 1 提交于 freeze 之前 → 合法进入清单）。
		exec("INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch, input_kind, state,"
				+ " started_at) VALUES (gen_random_uuid(), :o, CAST(:s AS uuid), gen_random_uuid(), 1, 'text',"
				+ " 'completed', now())", "s", sessionId.toString(), "o", a);
		UUID previewId = UUID.randomUUID();
		exec("INSERT INTO dh_preview(id, owner_account_id, voice_id, catalog_version, state, expires_at)"
				+ " VALUES (CAST(:p AS uuid), :o, 'preset-zh-natural-01', 1, 'ready', now() + interval '5 minutes')",
				"p", previewId.toString(), "o", a);
		// 已结清 invocation（晚回调目标）与未保存录制段。
		UUID invocationId = UUID.randomUUID();
		exec("INSERT INTO dh_invocation(id, owner_account_id, session_id, turn_id, stage, resource_id, segment_index,"
				+ " operation_id, state, settlement_state, provider_snapshot, budget_snapshot, request_hash,"
				+ " deadline_at) VALUES (CAST(:i AS uuid), :o, CAST(:s AS uuid), NULL, 'render', gen_random_uuid(),"
				+ " 0, gen_random_uuid(), 'succeeded', 'settled', '{}'::jsonb, '{}'::jsonb, repeat('0',64), now())",
				"i", invocationId.toString(), "s", sessionId.toString(), "o", a);
		UUID recordingId = UUID.randomUUID();
		exec("INSERT INTO dh_recording(id, owner_account_id, session_id, start_command_id, state, partial,"
				+ " start_program_ms) VALUES (CAST(:r AS uuid), :o, CAST(:s AS uuid), gen_random_uuid(), 'ready',"
				+ " false, 0)", "r", recordingId.toString(), "s", sessionId.toString(), "o", a);
		// 媒体行（save 尝试的目标载体；置 deleted 避免 #104 对象物链路混入本卡并发断言）。
		UUID mediaId = UUID.randomUUID();
		exec("INSERT INTO media_reference(id, owner_account_id, purpose, object_key, mime_type, status, domain_type)"
				+ " VALUES (CAST(:m AS uuid), :o, 'user_upload', :k, 'video/mp4', 'deleted', 'dh-g2r-it')", "m",
				mediaId.toString(), "o", a, "k", "it-dh-g2r/" + mediaId);
		redis.opsForList().rightPush("dh:buffer:" + sessionId + ":3", "冻结前内容帧").block(Duration.ofSeconds(5));
		redis.opsForValue().set("dh:preview:" + previewId, "audio-bytes").block(Duration.ofSeconds(5));

		// —— 事务 2（屏障=freeze 提交在先）：账号冻结。 ——
		UUID request = UUID.randomUUID();
		gate.prepare(a, request).block(Duration.ofSeconds(5));

		// —— 事务 3+（freeze 之后交错提交）：新内容/新引用全部被拒。 ——
		expectBarrierRejected("turn 插入", () -> exec(
				"INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch, input_kind, state,"
						+ " started_at) VALUES (gen_random_uuid(), :o, CAST(:s AS uuid), gen_random_uuid(), 2,"
						+ " 'text', 'completed', now())",
				"o", a, "s", sessionId.toString()));
		assertThat(count("dh_turn", a)).as("冻结后无新 turn 逃过清单").isEqualTo(1L);
		expectBarrierRejected("transcript 插入", () -> exec(
				"INSERT INTO dh_transcript(id, owner_account_id, session_id, utterance_id, utterance_seq, role,"
						+ " final_text, status, started_at, ended_at, content_epoch) VALUES (gen_random_uuid(), :o,"
						+ " CAST(:s AS uuid), gen_random_uuid(), 1, 'user', '迟到文本', 'complete', now(), now(), 3)",
				"o", a, "s", sessionId.toString()));
		expectBarrierRejected("save 到素材库（content_asset 插入）",
				() -> exec("INSERT INTO content_asset(id, media_reference_id, library_type, category,"
						+ " owner_account_id, organization_id, title) VALUES (gen_random_uuid(), CAST(:m AS uuid),"
						+ " 'personal', 'other', :o, NULL, '迟到保存')", "m", mediaId.toString(), "o", a));
		assertThat(db.sql("SELECT count(*) AS n FROM content_asset WHERE owner_account_id = :o").bind("o", a)
				.map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(5))).as("无新素材引用逃过清单").isZero();

		// —— 终态单向收尾：录制段迟到完成 → saved 放行；从 saved 倒退 → 拒绝。 ——
		exec("UPDATE dh_recording SET state = 'saved', asset_id = NULL WHERE id = CAST(:r AS uuid)", "r",
				recordingId.toString());
		expectBarrierRejected("saved 倒退",
				() -> exec("UPDATE dh_recording SET state = 'ready' WHERE id = CAST(:r AS uuid)", "r",
						recordingId.toString()));

		// —— 清理收口：plan → process 全绿（冻结前写入全部随清单删除）。 ——
		var manifest = erasure.plan(a, request).block(Duration.ofSeconds(10));
		var receipt = erasure.process(manifest.id()).block(Duration.ofSeconds(60));
		assertThat(receipt.state()).as("屏障后清单完整可收口：%s", receipt).isEqualTo("completed");
		assertThat(receipt.erased()).isTrue();
		for (String table : new String[]{"dh_profile", "dh_profile_revision", "dh_session", "dh_turn", "dh_event",
				"dh_transcript", "dh_preview", "dh_recording", "dh_operation", "dh_cleanup", "dh_avatar"}) {
			assertThat(count(table, a)).as(table + " 随注销删除").isZero();
		}
		// 已结清 invocation 按 K04 保留为脱敏经济事实（dh_invocation_economic_facts），不随批次删除。
		assertThat(count("dh_invocation", a)).as("已结清经济事实保留").isEqualTo(1L);
		assertThat(redis.hasKey("dh:buffer:" + sessionId + ":3").block(Duration.ofSeconds(5))).as("内容缓冲键删除").isFalse();
		assertThat(redis.hasKey("dh:preview:" + previewId).block(Duration.ofSeconds(5))).as("预览键删除").isFalse();
		var report = dhLifecycle.verifyResidue(a, manifest.id()).block(Duration.ofSeconds(5));
		assertThat(report.clean()).isTrue();
		assertThat(report.remaining()).isEmpty();

		// —— erased 后晚回调：保留的经济事实行已终态，单向屏障拒绝改写（不复活、不倒退）。 ——
		expectBarrierRejected("晚回调改写已结清 invocation", () -> db.sql(
				"UPDATE dh_invocation SET state = 'succeeded' WHERE id = CAST(:i AS uuid) AND owner_account_id = :o")
				.bind("i", invocationId.toString()).bind("o", a).fetch().rowsUpdated().block(Duration.ofSeconds(5)));
		assertThat(count("dh_invocation", a)).as("经济事实行数不变").isEqualTo(1L);
	}
}
