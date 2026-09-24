package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.compliance.IntelligenceAccountLifecycleRepository;
import com.grassland.intelligence.compliance.PersonalDataErasureService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * 完整注销与可验证物理清理 IT（任务书 #105G C105G-02 / TC105G-02-01 / §9.1）：A 各类 DH 对象、B 对象、
 * 已转组织对象——A 注销两次并「重启 worker」，A 可删清净、B/组织按 Scope 保留、结果幂等；远端未删除/
 * 已删除/未知三态与账号墓碑阻断迟到供应商结果；远端未确认不得报 complete。
 *
 * <p>
 * 真实 DB/事务/账号锁/易失 Redis；最外层网络替身=第三方 render 适配 + runtime 控制端口（INTERNAL13）。
 */
@Import(DigitalHumanErasureIT.FakeErasureNetwork.class)
class DigitalHumanErasureIT extends IntelligenceItSupport {

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
	private PersonalDataErasureService erasure;
	@Autowired
	private IntelligenceAccountLifecycleRepository gate;
	@Autowired
	private DigitalHumanCleanupWorker cleanupWorker;
	@Autowired
	private DigitalHumanLifecycleService dhLifecycle;
	@Autowired
	private FakeRenderProvider fakeProvider;
	@Autowired
	private FakeAvatarRuntimePort fakeAvatarRuntime;
	@Autowired
	private FakeCleanupRuntimePort fakeCleanupRuntime;
	@Autowired
	private ReactiveStringRedisTemplate redis;

	/** 最外层网络替身：第三方 render 适配（删除/查询三态可控）+ 两类 runtime 删除端口。 */
	static class FakeErasureNetwork {

		@Bean
		FakeRenderProvider fakeRenderProvider() {
			return new FakeRenderProvider();
		}

		@Bean
		FakeAvatarRuntimePort fakeAvatarRuntimePort() {
			return new FakeAvatarRuntimePort();
		}

		@Bean
		FakeCleanupRuntimePort fakeCleanupRuntimePort() {
			return new FakeCleanupRuntimePort();
		}
	}

	/** render 适配 fake：远端删除 confirmed 可控 + 查询三态可控（§9.1）。 */
	static class FakeRenderProvider implements DigitalHumanRenderProvider {

		volatile boolean confirmRemoteDelete = true;
		/** 查询返回态：DELETED=幂等恢复；ACTIVE/UNKNOWN=保持 failed（不猜已删）。 */
		volatile DigitalHumanRenderService.RemoteResourceState queryState = DigitalHumanRenderService.RemoteResourceState.DELETED;

		final List<String> deleteCalls = new CopyOnWriteArrayList<>();
		final List<String> queryCalls = new CopyOnWriteArrayList<>();

		@Override
		public String protocol() {
			return "fake-dh-avatar";
		}

		@Override
		public DigitalHumanRenderService.RenderConnection createSession(
				DigitalHumanRenderService.RenderCommand command) {
			throw new UnsupportedOperationException("erasure IT 不触达渲染会话");
		}

		@Override
		public DigitalHumanRenderService.ControlOutcome control(DigitalHumanRenderService.ControlCommand command) {
			throw new UnsupportedOperationException("erasure IT 不触达渲染控制");
		}

		@Override
		public DigitalHumanRecords.UsageUnits queryUsage(String providerSessionRef) {
			throw new UnsupportedOperationException("erasure IT 不触达用量查询");
		}

		@Override
		public DigitalHumanRenderService.AvatarPreparation prepareAvatar(
				DigitalHumanRenderService.AvatarPrepareCommand command) {
			throw new UnsupportedOperationException("erasure IT 不触达形象准备");
		}

		@Override
		public DigitalHumanRenderService.AvatarDeletion deleteAvatar(String providerResourceRef) {
			deleteCalls.add(providerResourceRef);
			return new DigitalHumanRenderService.AvatarDeletion(confirmRemoteDelete,
					confirmRemoteDelete ? "deleted" : "unknown");
		}

		@Override
		public DigitalHumanRenderService.RemoteResourceState queryRemoteAvatar(String providerResourceRef) {
			queryCalls.add(providerResourceRef);
			return queryState;
		}
	}

	/** avatar 族 runtime 删除端口 fake（INTERNAL13 kind=avatar）。 */
	static class FakeAvatarRuntimePort implements DigitalHumanAvatarService.AvatarRuntimePort {

		final List<String> deleteCalls = new CopyOnWriteArrayList<>();

		@Override
		public Mono<DigitalHumanAvatarService.PrepareAccepted> prepareAvatar(UUID avatarId, int revision, byte[] source,
				String sourceSha256, String backendId) {
			throw new UnsupportedOperationException("erasure IT 不触达形象准备");
		}

		@Override
		public Mono<DigitalHumanAvatarService.DeleteOutcome> deleteAvatarObjects(UUID avatarId, int revision) {
			deleteCalls.add(avatarId.toString());
			return Mono.just(new DigitalHumanAvatarService.DeleteOutcome(true, List.of()));
		}
	}

	/** recording 删除端口 fake（INTERNAL13 kind=recording；失败可控）。 */
	static class FakeCleanupRuntimePort implements DigitalHumanCleanupWorker.RuntimePort {

		volatile boolean confirm = true;
		final List<String> deleteCalls = new CopyOnWriteArrayList<>();

		@Override
		public Mono<DigitalHumanCleanupWorker.ResourceDeletion> deleteResource(UUID resourceId, String kind,
				int revision) {
			deleteCalls.add(kind + ":" + resourceId);
			return Mono.just(new DigitalHumanCleanupWorker.ResourceDeletion(confirm,
					confirm ? List.of() : List.of("segment.mp4")));
		}
	}

	@BeforeEach
	void seed() {
		cleanup();
		seedRenderControlPlane("it-dh-g2-cred");
		fakeProvider.confirmRemoteDelete = true;
		fakeProvider.queryState = DigitalHumanRenderService.RemoteResourceState.DELETED;
		fakeProvider.deleteCalls.clear();
		fakeProvider.queryCalls.clear();
		fakeAvatarRuntime.deleteCalls.clear();
		fakeCleanupRuntime.confirm = true;
		fakeCleanupRuntime.deleteCalls.clear();
	}

	/** 双端自清（共享容器防跨类污染）：负例有意保留的证据行在类退出前按 FK 序清空。 */
	@AfterEach
	void drainResidue() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM dh_cleanup").then().then(db.sql("DELETE FROM dh_asset_attachment").then())
				.then(db.sql("DELETE FROM dh_recording").then()).then(db.sql("DELETE FROM dh_avatar").then())
				.then(db.sql("DELETE FROM dh_invocation WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM dh_operation WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM dh_transcript WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM dh_event WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM dh_turn WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM dh_session WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM dh_preview WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM dh_profile_revision WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM dh_profile WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM content_asset WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM media_reference WHERE owner_account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM personal_data_erasure_object WHERE manifest_id IN"
						+ " (SELECT id FROM personal_data_erasure_manifest WHERE account_id LIKE 'dh-g2-%')").then())
				.then(db.sql("DELETE FROM personal_data_erasure_step WHERE manifest_id IN"
						+ " (SELECT id FROM personal_data_erasure_manifest WHERE account_id LIKE 'dh-g2-%')").then())
				.then(db.sql("DELETE FROM personal_data_erasure_manifest WHERE account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id LIKE 'dh-g2-%'").then())
				.then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
						+ " (SELECT id FROM platform_model_config WHERE capability = 'digital_human_render')").then())
				.then(db.sql("DELETE FROM platform_model_config WHERE capability = 'digital_human_render'").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-dh-g2-%'").then())
				.block(Duration.ofSeconds(20));
	}

	private void seedRenderControlPlane(String credentialName) {
		String baseUrl = "https://" + credentialName + ".invalid/v1";
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
				    VALUES (:credName, 'fake-dh-avatar', :baseUrl, true) RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'digital_human_render', 'primary', 'fake-dh-avatar', 'dh-g2-it-model', :baseUrl,
				    'healthy', true, 1, cred.id FROM cred
				""").bind("credName", credentialName).bind("baseUrl", baseUrl).then().block(Duration.ofSeconds(10));
	}

	// ---------- 造数：A 全资源清单 ----------

	private record Seeded(String account, UUID sessionId, UUID previewId, UUID avatarId, UUID personalAttachment,
			UUID orgAttachment) {
	}

	private Seeded seedFullInventory(String account) {
		UUID profileId = UUID.randomUUID();
		exec("INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)"
				+ " VALUES (CAST(:id AS uuid), :o, 'A 的角色', 1, 'active')", "id", profileId.toString(), "o", account);
		exec("INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting, tone,"
				+ " avatar_id, avatar_revision, voice_id, catalog_version) VALUES (gen_random_uuid(), :o,"
				+ " CAST(:id AS uuid), 1, 'p', 'g', 'natural', gen_random_uuid(), 1, 'v', 1)", "id",
				profileId.toString(), "o", account);
		UUID sessionId = UUID.randomUUID();
		exec("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, content_epoch)"
				+ " VALUES (CAST(:s AS uuid), :o, gen_random_uuid(), 1, 'n', 'mock', gen_random_uuid(),"
				+ " gen_random_uuid(), '{}'::jsonb, 'ended', now(), 2)", "s", sessionId.toString(), "o", account);
		exec("INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch, input_kind, state,"
				+ " started_at) VALUES (gen_random_uuid(), :o, CAST(:s AS uuid), gen_random_uuid(), 1, 'text',"
				+ " 'completed', now())", "s", sessionId.toString(), "o", account);
		exec("INSERT INTO dh_event(session_id, seq, event_id, event_type, payload, owner_account_id)"
				+ " VALUES (CAST(:s AS uuid), 1, gen_random_uuid(), 'session.ended', '{}'::jsonb, :o)", "s",
				sessionId.toString(), "o", account);
		exec("INSERT INTO dh_transcript(id, owner_account_id, session_id, utterance_id, utterance_seq, role,"
				+ " final_text, status, started_at, ended_at, content_epoch) VALUES (gen_random_uuid(), :o,"
				+ " CAST(:s AS uuid), gen_random_uuid(), 1, 'user', '已授权文本', 'complete', now(), now(), 2)", "s",
				sessionId.toString(), "o", account);
		UUID previewId = UUID.randomUUID();
		exec("INSERT INTO dh_preview(id, owner_account_id, voice_id, catalog_version, state, expires_at)"
				+ " VALUES (CAST(:p AS uuid), :o, 'preset-zh-natural-01', 1, 'ready', now() + interval '5 minutes')",
				"p", previewId.toString(), "o", account);
		// 已结清经济事实（不阻塞）。
		exec("INSERT INTO dh_invocation(id, owner_account_id, session_id, turn_id, stage, resource_id, segment_index,"
				+ " operation_id, state, settlement_state, provider_snapshot, budget_snapshot, request_hash,"
				+ " deadline_at) VALUES (gen_random_uuid(), :o, CAST(:s AS uuid), NULL, 'render', gen_random_uuid(),"
				+ " 0, gen_random_uuid(), 'succeeded', 'settled', '{}'::jsonb, '{}'::jsonb, repeat('0',64), now())",
				"s", sessionId.toString(), "o", account);
		// 形象（ready + 外部句柄；未撤销——注销路径须补登 avatar_external）。源媒体行真实存在（FK）。
		UUID avatarId = UUID.randomUUID();
		UUID avatarMedia = UUID.randomUUID();
		exec("INSERT INTO media_reference(id, owner_account_id, purpose, object_key, mime_type, status, domain_type)"
				+ " VALUES (CAST(:m AS uuid), :o, 'user_upload', :k, 'video/mp4', 'deleted', 'dh-g2-it')", "m",
				avatarMedia.toString(), "o", account, "k", "it-dh-g2/" + avatarMedia);
		exec("INSERT INTO dh_avatar(id, owner_account_id, source_media_id, source, status, revision,"
				+ " rights_version, rights_accepted_at, license_evidence_ref, provider_resource_refs)"
				+ " VALUES (CAST(:a AS uuid), :o, CAST(:m AS uuid), 'personal', 'ready', 1, 'dh-avatar-v1', now(),"
				+ " 'ev-1', CAST(:refs AS jsonb))", "a", avatarId.toString(), "m", avatarMedia.toString(), "o", account,
				"refs", "{\"providerResourceRef\":\"provider-avatar-" + avatarId + "\",\"compatibleBackendIds\":[]}");
		exec("INSERT INTO dh_cleanup(id, owner_account_id, resource_kind, resource_id, object_ref, state, reason)"
				+ " VALUES (gen_random_uuid(), :o, 'avatar', CAST(:a AS uuid), NULL, 'pending', 'avatar_create')", "a",
				avatarId.toString(), "o", account);
		exec("INSERT INTO dh_cleanup(id, owner_account_id, resource_kind, resource_id, object_ref, state, reason)"
				+ " VALUES (gen_random_uuid(), :o, 'avatar_object', CAST(:a AS uuid), 'dha-" + avatarId
				+ "-normalized_png', 'pending', 'avatar-artifact')", "a", avatarId.toString(), "o", account);
		// 未保存临时录制段 + 派生对象登记（INTERNAL13 由 worker 驱动）。
		UUID recordingId = UUID.randomUUID();
		exec("INSERT INTO dh_recording(id, owner_account_id, session_id, start_command_id, state, partial,"
				+ " start_program_ms) VALUES (CAST(:r AS uuid), :o, CAST(:s AS uuid), gen_random_uuid(), 'ready',"
				+ " false, 0)", "r", recordingId.toString(), "s", sessionId.toString(), "o", account);
		exec("INSERT INTO dh_cleanup(id, owner_account_id, resource_kind, resource_id, object_ref, state, reason)"
				+ " VALUES (gen_random_uuid(), :o, 'recording_object', CAST(:r AS uuid), 'dhr-" + recordingId
				+ "-mp4', 'pending', 'recording-artifact')", "r", recordingId.toString(), "o", account);
		// 个人素材 + 附件（随注销删）；组织（merchant）素材 + 附件（保留）。
		UUID personalAsset = seedAsset(account, "personal", null);
		UUID orgAsset = seedAsset(account, "merchant", "11111111-1111-4111-8111-111111111111");
		UUID personalAttachment = seedAttachment(account, personalAsset);
		UUID orgAttachment = seedAttachment(account, orgAsset);
		// Redis 易失键：buffer（session+epoch）与 preview 音频。
		redis.opsForList().rightPush("dh:buffer:" + sessionId + ":2", "已授权文本帧").block(Duration.ofSeconds(5));
		redis.opsForValue().set("dh:preview:" + previewId, "audio-bytes").block(Duration.ofSeconds(5));
		return new Seeded(account, sessionId, previewId, avatarId, personalAttachment, orgAttachment);
	}

	private UUID seedAsset(String owner, String libraryType, String organizationId) {
		UUID assetId = UUID.randomUUID();
		UUID mediaId = UUID.randomUUID();
		// media 行直接置 deleted：本卡聚焦 DH 链路（对象物删归 #104 已测路径），避免对象登记混入。
		exec("INSERT INTO media_reference(id, owner_account_id, purpose, object_key, mime_type, status, domain_type)"
				+ " VALUES (CAST(:m AS uuid), :o, 'user_upload', :k, 'video/mp4', 'deleted', 'dh-g2-it')", "m",
				mediaId.toString(), "o", owner, "k", "it-dh-g2/" + mediaId);
		var spec = db.sql("INSERT INTO content_asset(id, media_reference_id, library_type, category,"
				+ " owner_account_id, organization_id, title) VALUES (CAST(:id AS uuid), CAST(:m AS uuid), :lib,"
				+ " 'other', :o, CAST(:org AS uuid), 't')").bind("id", assetId.toString()).bind("m", mediaId.toString())
				.bind("lib", libraryType).bind("o", owner);
		spec = organizationId == null ? spec.bindNull("org", String.class) : spec.bind("org", organizationId);
		spec.then().block(Duration.ofSeconds(5));
		return assetId;
	}

	private UUID seedAttachment(String owner, UUID assetId) {
		UUID attachmentId = UUID.randomUUID();
		exec("INSERT INTO dh_asset_attachment(id, owner_account_id, asset_id, media_reference_id, kind)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:a AS uuid), gen_random_uuid(), 'subtitle')", "id",
				attachmentId.toString(), "o", owner, "a", assetId.toString());
		return attachmentId;
	}

	private void exec(String sql, String... pairs) {
		var spec = db.sql(sql);
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			spec = spec.bind(pairs[i], pairs[i + 1]);
		}
		spec.then().block(Duration.ofSeconds(5));
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return String.valueOf(current.getMessage());
	}

	private long count(String table, String owner) {
		return db.sql("SELECT count(*) AS n FROM " + table + " WHERE owner_account_id = :o").bind("o", owner)
				.map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
	}

	private long countSql(String sql) {
		return db.sql(sql).map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
	}

	// ---------- TC105G-02-01 主线 ----------

	@Test
	void tc105g_02_01_perKindCleanupScopeRetentionAndIdempotence() {
		String a = "dh-g2-a-" + UUID.randomUUID();
		String b = "dh-g2-b-" + UUID.randomUUID();
		Seeded seeded = seedFullInventory(a);
		UUID bProfile = UUID.randomUUID();
		exec("INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)"
				+ " VALUES (CAST(:id AS uuid), :o, 'B 的角色', 1, 'active')", "id", bProfile.toString(), "o", b);
		exec("INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting, tone,"
				+ " avatar_id, avatar_revision, voice_id, catalog_version) VALUES (gen_random_uuid(), :o,"
				+ " CAST(:id AS uuid), 1, 'p', 'g', 'natural', gen_random_uuid(), 1, 'v', 1)", "id",
				bProfile.toString(), "o", b);

		// —— 远端未确认：worker 驱动后行 failed、prepare 被阻塞（远端未确认不得当空闲/complete）。
		fakeProvider.confirmRemoteDelete = false;
		fakeProvider.queryState = DigitalHumanRenderService.RemoteResourceState.UNKNOWN;
		cleanupWorker.advanceForOwner(a).block(Duration.ofSeconds(20));
		assertThat(fakeProvider.deleteCalls).as("远端删除按句柄出站").hasSize(1);
		assertThat(countSql("SELECT count(*) AS n FROM dh_cleanup WHERE owner_account_id = '" + a
				+ "' AND resource_kind = 'avatar_external' AND state = 'failed'")).as("未确认删除保持 failed").isEqualTo(1L);
		Throwable blocked = null;
		try {
			gate.prepare(a, UUID.randomUUID()).block(Duration.ofSeconds(5));
		} catch (RuntimeException failure) {
			blocked = failure;
		}
		assertThat(blocked).as("failed cleanup 行阻塞 prepare（ACTIVE_JOBS）").isNotNull();
		assertThat(rootMessage(blocked)).contains("dh_cleanup");

		// —— 供应商结果查询=已删除（§9.1 幂等恢复）：SQL 推进 due 时间后重驱动收口。
		db.sql("UPDATE dh_cleanup SET next_attempt_at = now() - interval '1 second', attempts = 1"
				+ " WHERE owner_account_id = :o AND state = 'failed'").bind("o", a).then().block(Duration.ofSeconds(5));
		fakeProvider.confirmRemoteDelete = true;
		cleanupWorker.advanceForOwner(a).block(Duration.ofSeconds(20));
		assertThat(fakeCleanupRuntime.deleteCalls).as("录制派生对象经 INTERNAL13 删除").hasSize(1);
		// 登记行保留终态证据（K05 账本）：全部到 deleted/retained，无 pending/deleting/failed。
		assertThat(countSql("SELECT count(*) AS n FROM dh_cleanup WHERE owner_account_id = '" + a
				+ "' AND state NOT IN ('deleted', 'retained')")).as("登记行全部收口").isZero();
		assertThat(countSql(
				"SELECT count(*) AS n FROM dh_avatar WHERE owner_account_id = '" + a + "' AND status = 'deleted'"))
				.as("avatar 单向收尾到 deleted").isEqualTo(1L);

		// —— 注销两轮：prepare → plan → process 幂等。
		UUID request = UUID.randomUUID();
		gate.prepare(a, request).block(Duration.ofSeconds(5));
		var manifest = erasure.plan(a, request).block(Duration.ofSeconds(10));
		var first = erasure.process(manifest.id()).block(Duration.ofSeconds(60));
		assertThat(first.state()).as("远端确认后可 complete：%s", first).isEqualTo("completed");
		assertThat(first.erased()).isTrue();

		// A 各类资源清净（逐表）。
		assertThat(count("dh_profile", a)).isZero();
		assertThat(count("dh_profile_revision", a)).isZero();
		assertThat(count("dh_session", a)).isZero();
		assertThat(count("dh_turn", a)).isZero();
		assertThat(count("dh_event", a)).isZero();
		assertThat(count("dh_transcript", a)).isZero();
		assertThat(count("dh_preview", a)).isZero();
		assertThat(count("dh_operation", a)).isZero();
		assertThat(count("dh_recording", a)).isZero();
		assertThat(count("dh_avatar", a)).as("avatar 行随批次删除").isZero();
		assertThat(count("dh_cleanup", a)).isZero();
		assertThat(countSql("SELECT count(*) AS n FROM dh_asset_attachment WHERE id = CAST('"
				+ seeded.personalAttachment() + "' AS uuid)")).as("个人素材附件随注销删除").isZero();
		// 组织资产附件保留（K13.5：已转组织不因历史作者误删）。
		assertThat(countSql("SELECT count(*) AS n FROM dh_asset_attachment WHERE id = CAST('" + seeded.orgAttachment()
				+ "' AS uuid)")).as("组织素材附件保留").isEqualTo(1L);
		assertThat(countSql("SELECT count(*) AS n FROM content_asset WHERE owner_account_id = '" + a
				+ "' AND library_type = 'merchant'")).as("组织素材行保留").isEqualTo(1L);
		// B 不受影响。
		assertThat(count("dh_profile", b)).isEqualTo(1L);
		assertThat(count("dh_profile_revision", b)).isEqualTo(1L);

		// 易失键已删（不只删 Postgres）：buffer/preview 键不存在。
		assertThat(redis.hasKey("dh:buffer:" + seeded.sessionId() + ":2").block(Duration.ofSeconds(5))).as("会话内容缓冲键删除")
				.isFalse();
		assertThat(redis.hasKey("dh:preview:" + seeded.previewId()).block(Duration.ofSeconds(5))).as("预览音频键删除")
				.isFalse();

		// dh 域逐类残留报告：remaining 空、retained 含组织附件。
		var report = dhLifecycle.verifyResidue(a, manifest.id()).block(Duration.ofSeconds(5));
		assertThat(report.clean()).isTrue();
		assertThat(report.remaining()).isEmpty();
		assertThat(report.retained()).containsEntry("dh_asset_attachment_shared_assets", 1L);

		// 「重启 worker」：完成后兜底驱动无事可做；二次 process 幂等同终态。
		cleanupWorker.advanceDue(10).collectList().block(Duration.ofSeconds(20));
		assertThat(fakeProvider.deleteCalls).as("完成后无新增远端调用").hasSize(2);
		var second = erasure.process(manifest.id()).block(Duration.ofSeconds(60));
		assertThat(second.state()).isEqualTo("completed");
		assertThat(second.counts()).isEqualTo(first.counts());
		assertThat(count("dh_profile", b)).isEqualTo(1L);

		// 聚合清理目标签名真实行使：erased 账号上重跑 eraseAll 幂等（零删除、零残留、组织附件仍保留）。
		var aggregate = dhLifecycle.eraseAll(a, manifest.id()).block(Duration.ofSeconds(20));
		assertThat(aggregate.complete()).as("聚合口径干净").isTrue();
		assertThat(aggregate.remaining()).isZero();
		assertThat(aggregate.retained()).isEqualTo(1);
		assertThat(aggregate.counts().values().stream().allMatch(rows -> rows == 0L)).as("无行可删").isTrue();

		// 账号墓碑阻断迟到供应商结果：erased 后晚到删除确认无行可写、不复活。
		fakeProvider.confirmRemoteDelete = true;
		assertThat(cleanupWorker.advanceForOwner(a).block(Duration.ofSeconds(10))).isZero();
		assertThat(count("dh_cleanup", a)).isZero();
		assertThat(count("dh_avatar", a)).isZero();
	}

	// ---------- TC105G-02-01 负例：远端 ACTIVE/UNKNOWN 不得 complete ----------

	@Test
	void tc105g_02_01_remoteUnconfirmedKeepsManifestOpen() {
		String a = "dh-g2-u-" + UUID.randomUUID();
		seedFullInventory(a);
		fakeProvider.confirmRemoteDelete = false;
		fakeProvider.queryState = DigitalHumanRenderService.RemoteResourceState.ACTIVE;

		// 模拟经济结论已出但远端悬而未决：直接置 frozen 放行清理（同 tc105b_05_03 手法）。
		UUID request = UUID.randomUUID();
		db.sql("INSERT INTO intelligence_account_lifecycle(account_id, closure_request_id, state)"
				+ " VALUES (:a, CAST(:r AS uuid), 'frozen') ON CONFLICT (account_id) DO UPDATE SET"
				+ " closure_request_id = CAST(:r AS uuid), state = 'frozen'").bind("a", a).bind("r", request.toString())
				.then().block(Duration.ofSeconds(5));
		var manifest = erasure.plan(a, request).block(Duration.ofSeconds(10));
		var receipt = erasure.process(manifest.id()).block(Duration.ofSeconds(60));

		assertThat(receipt.erased()).as("远端 ACTIVE 未确认不得 complete").isFalse();
		assertThat(receipt.state()).isIn("db_cleaning", "needs_review", "objects_pending");
		assertThat(count("dh_avatar", a)).as("avatar 行保留作证据").isEqualTo(1L);
		// 清理期不新增登记（触发器冻结）：未确认信号由步骤失败码承载，不落在 dh_cleanup 行。
		assertThat(countSql("SELECT count(*) AS n FROM personal_data_erasure_step WHERE manifest_id = CAST('"
				+ manifest.id() + "' AS uuid) AND resource_kind = 'dh_remote_cleanup'"
				+ " AND last_error_code LIKE 'dh_remote_unconfirmed_%'")).as("远端未确认计为步骤失败").isEqualTo(1L);
		assertThat(fakeProvider.queryCalls).as("供应商状态查询发生").isNotEmpty();
		assertThat(fakeProvider.deleteCalls).as("清理期仍尝试远端删除").isNotEmpty();

		// F 媒体行未随批次消失（dh_remote_cleanup 未收口，后续 kind 不推进）。
		assertThat(count("dh_recording", a)).as("录制行保留（步骤失败阻塞后续批次）").isEqualTo(1L);
		assertThat(countSql("SELECT count(*) AS n FROM personal_data_erasure_step WHERE manifest_id = CAST('"
				+ manifest.id() + "' AS uuid) AND resource_kind = 'dh_recording'")).as("后续 kind 未领取").isEqualTo(1L);
		assertThat(countSql("SELECT count(*) AS n FROM personal_data_erasure_step WHERE manifest_id = CAST('"
				+ manifest.id() + "' AS uuid) AND resource_kind = 'dh_recording'" + " AND state = 'pending'"))
				.isEqualTo(1L);
	}
}
