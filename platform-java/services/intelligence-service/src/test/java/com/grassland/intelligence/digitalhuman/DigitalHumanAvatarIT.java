package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAvatarService.ArtifactAcceptance;
import com.grassland.intelligence.digitalhuman.DigitalHumanAvatarService.AvatarRuntimePort;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanMediaRepository.AvatarRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarItem;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarSource;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarState;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 自有形象 IT（任务书 #105F C105F-01 / TC105F-01-01～04 + §9.1 远端补充）：真实 DB
 * （dh_avatar/dh_cleanup/dh_operation 幂等键/引用约束/真实 Postgres）+ 最外层网络边界 fake（第三方
 * render 适配、runtime 控制端口、对象存储读取）。0/多脸、配置撤销、异步迟到、额外收费不支持、远端删除 重试在 Java
 * 侧断言；坏图/多帧/炸弹/超限的 runtime 解码面在 tests/test_avatar.py（跨语言同 TC 各有 子断言）。
 */
@Import(DigitalHumanAvatarIT.FakeAvatarNetwork.class)
class DigitalHumanAvatarIT extends IntelligenceItSupport {

	@Autowired
	private DatabaseClient db;
	@Autowired
	private DigitalHumanAvatarService avatars;
	@Autowired
	private DigitalHumanMediaRepository mediaRepository;
	@Autowired
	private FakeAvatarRenderProvider fakeProvider;
	@Autowired
	private FakeAvatarRuntimePort fakeRuntime;
	@MockitoBean
	private ObjectStorageAdapter storage;

	private final String account = "dh-avatar-a-" + UUID.randomUUID();
	private final PersonalActor actor = new PersonalActor(account);
	private final String other = "dh-avatar-b-" + UUID.randomUUID();

	/** 最外层网络替身：第三方 render 适配 + runtime 控制端口（DB/owner/幂等键全部真实）。 */
	static class FakeAvatarNetwork {

		@Bean
		FakeAvatarRenderProvider fakeRenderProvider() {
			return new FakeAvatarRenderProvider();
		}

		@Bean
		FakeAvatarRuntimePort fakeRuntimePort() {
			return new FakeAvatarRuntimePort();
		}
	}

	/** 第三方形象检测/准备 fake（faceCount/extraCharge/远端删除重试可控）。 */
	static class FakeAvatarRenderProvider implements DigitalHumanRenderProvider {

		volatile int faceCount = 1;
		volatile boolean extraCharge = false;
		final List<String> prepareCalls = new CopyOnWriteArrayList<>();
		final List<String> deleteCalls = new CopyOnWriteArrayList<>();
		volatile int failRemoteDeleteFirstN = 0;

		@Override
		public String protocol() {
			return "fake-dh-avatar";
		}

		@Override
		public DigitalHumanRenderService.RenderConnection createSession(
				DigitalHumanRenderService.RenderCommand command) {
			throw new UnsupportedOperationException("avatar IT 不触达渲染会话");
		}

		@Override
		public DigitalHumanRenderService.ControlOutcome control(DigitalHumanRenderService.ControlCommand command) {
			throw new UnsupportedOperationException("avatar IT 不触达渲染控制");
		}

		@Override
		public DigitalHumanRecords.UsageUnits queryUsage(String providerSessionRef) {
			throw new UnsupportedOperationException("avatar IT 不触达用量查询");
		}

		@Override
		public DigitalHumanRenderService.AvatarPreparation prepareAvatar(
				DigitalHumanRenderService.AvatarPrepareCommand command) {
			prepareCalls.add(command.avatarId() == null ? "anon" : command.avatarId().toString());
			return new DigitalHumanRenderService.AvatarPreparation(faceCount, "provider-avatar-" + command.avatarId(),
					List.of("dh-it-backend"), extraCharge);
		}

		@Override
		public DigitalHumanRenderService.AvatarDeletion deleteAvatar(String providerResourceRef) {
			deleteCalls.add(providerResourceRef);
			if (failRemoteDeleteFirstN > 0) {
				failRemoteDeleteFirstN--;
				return new DigitalHumanRenderService.AvatarDeletion(false, "retrying");
			}
			return new DigitalHumanRenderService.AvatarDeletion(true, "deleted");
		}
	}

	/** runtime 控制端口 fake（INTERNAL10/13 调用计数；不产生真实出站）。 */
	static class FakeAvatarRuntimePort implements AvatarRuntimePort {

		final List<String> prepareCalls = new CopyOnWriteArrayList<>();
		final List<String> deleteCalls = new CopyOnWriteArrayList<>();

		@Override
		public Mono<DigitalHumanAvatarService.PrepareAccepted> prepareAvatar(UUID avatarId, int revision, byte[] source,
				String sourceSha256, String backendId) {
			prepareCalls.add(avatarId.toString());
			return Mono.just(new DigitalHumanAvatarService.PrepareAccepted("job-" + avatarId, "processing"));
		}

		@Override
		public Mono<DigitalHumanAvatarService.DeleteOutcome> deleteAvatarObjects(UUID avatarId, int revision) {
			deleteCalls.add(avatarId.toString());
			return Mono.just(new DigitalHumanAvatarService.DeleteOutcome(true, List.of()));
		}
	}

	@BeforeEach
	void seed() {
		reset(storage);
		db.sql("DELETE FROM dh_cleanup").then().then(db.sql("DELETE FROM dh_avatar").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind LIKE 'avatar_%'").then())
				.then(db.sql("DELETE FROM dh_session").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_profile_revision").then()).then(db.sql("DELETE FROM dh_profile").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM media_reference WHERE domain_type = 'dh-avatar-it'").then())
				.then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
						+ " (SELECT id FROM platform_model_config WHERE capability = 'digital_human_render')").then())
				.then(db.sql("DELETE FROM platform_model_config WHERE capability = 'digital_human_render'"
						+ " OR credential_id IN (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-dh-av-%')")
						.then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-dh-av-%'").then())
				.then(db.sql("DELETE FROM dh_catalog").then()).block(Duration.ofSeconds(10));
		seedRenderControlPlane("it-dh-av-cred");
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by) VALUES (1, 1,"
				+ " CAST(:config AS jsonb), 'it')")
				.bind("config",
						"{\"enabled\":true,\"newSessionsAllowed\":true,\"recordingEnabled\":false,"
								+ "\"customAvatarEnabled\":true,\"allowedBackendIds\":[\"dh-it-backend\"],"
								+ "\"avatars\":[],\"voices\":[]}")
				.then().block(Duration.ofSeconds(10));
		fakeProvider.faceCount = 1;
		fakeProvider.extraCharge = false;
		fakeProvider.failRemoteDeleteFirstN = 0;
		fakeProvider.prepareCalls.clear();
		fakeProvider.deleteCalls.clear();
		fakeRuntime.prepareCalls.clear();
		fakeRuntime.deleteCalls.clear();
		doThrow(new IllegalStateException("未配置的对象 key")).when(storage).getObject(anyString());
	}

	private void seedRenderControlPlane(String credentialName) {
		// 目的地唯一索引 (provider, base_url)：每凭据独立假域名（端点不参与真实出站，adapter 为 fake）。
		String baseUrl = "https://" + credentialName + ".invalid/v1";
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
				    VALUES (:credName, 'fake-dh-avatar', :baseUrl, true) RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'digital_human_render', 'primary', 'fake-dh-avatar', 'dh-avatar-it-model', :baseUrl,
				    'healthy', true, 1, cred.id FROM cred
				""").bind("credName", credentialName).bind("baseUrl", baseUrl).then().block(Duration.ofSeconds(10));
	}

	// ---------- 造数 ----------

	private byte[] jpegBytes() {
		try {
			BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "jpeg", out);
			return out.toByteArray();
		} catch (Exception failure) {
			throw new IllegalStateException("测试图片生成失败", failure);
		}
	}

	private UUID seedMedia(String owner, String organizationId, MediaStatus status, String mime, byte[] bytes) {
		UUID id = UUID.randomUUID();
		String key = "it-dh-avatar/" + id;
		doReturn(bytes).when(storage).getObject(key);
		var insert = db.sql("INSERT INTO media_reference(id, owner_account_id, organization_id, purpose,"
				+ " object_key, mime_type, size_bytes, checksum, source, status, domain_type)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:org AS uuid), 'user_upload', :key, :mime, :size, 'sha',"
				+ " 'upload', :status, 'dh-avatar-it')").bind("id", id.toString()).bind("owner", owner).bind("key", key)
				.bind("mime", mime).bind("size", (long) bytes.length).bind("status", status.db());
		insert = organizationId == null ? insert.bindNull("org", String.class) : insert.bind("org", organizationId);
		insert.then().block(Duration.ofSeconds(10));
		return id;
	}

	private AvatarRow createProcessing(String ownerId) {
		UUID mediaId = seedMedia(ownerId, null, MediaStatus.ACTIVE, "image/jpeg", jpegBytes());
		AvatarItem item = avatars
				.create(new PersonalActor(ownerId), mediaId, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10));
		assertThat(item.state()).isEqualTo(AvatarState.processing);
		return mediaRepository.findAvatarById(UUID.fromString(item.id())).block(Duration.ofSeconds(10));
	}

	private String manifestJson(String avatarId, String... objectRefs) {
		StringBuilder files = new StringBuilder();
		for (String ref : objectRefs) {
			if (files.length() > 0) {
				files.append(',');
			}
			files.append("{\"objectRef\":\"").append(ref)
					.append("\",\"relativePath\":\"normalized.png\",\"sha256\":\"a1\",\"sizeBytes\":11,"
							+ "\"contentType\":\"image/png\"}");
		}
		return "{\"avatarId\":\"" + avatarId + "\",\"revision\":1,\"backendId\":\"dh-it-backend\",\"files\":[" + files
				+ "]}";
	}

	private long countRows(String table, String where) {
		return db.sql("SELECT count(*) AS n FROM " + table + " WHERE " + where).map(r -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
	}

	private AvatarRow awaitStatus(UUID avatarId, String expected) {
		long deadline = System.currentTimeMillis() + 10_000;
		AvatarRow row = null;
		while (System.currentTimeMillis() < deadline) {
			row = mediaRepository.findAvatarById(avatarId).block(Duration.ofSeconds(10));
			if (row != null && expected.equals(row.status())) {
				return row;
			}
			sleepBriefly();
		}
		throw new AssertionError("avatar 未进入 " + expected + "，当前=" + (row == null ? "null" : row.status()));
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException interrupt) {
			Thread.currentThread().interrupt();
		}
	}

	// ---------- TC105F-01-01：合法图片 → ready 可选、manifest 逐 key 登记 ----------

	@Test
	void tc105f_01_01_validImageBecomesReadyWithPerKeyCleanupRegistration() {
		UUID mediaId = seedMedia(account, null, MediaStatus.ACTIVE, "image/jpeg", jpegBytes());
		UUID requestId = UUID.randomUUID();

		AvatarItem item = avatars.create(actor, mediaId, true, "dh-avatar-v1", requestId, false)
				.block(Duration.ofSeconds(10));
		assertThat(item.source()).isEqualTo(AvatarSource.personal);
		assertThat(item.state()).isEqualTo(AvatarState.processing);
		assertThat(item.compatibleBackendIds()).containsExactly("dh-it-backend");
		assertThat(item.previewMediaId()).isEqualTo(mediaId.toString());
		assertThat(fakeProvider.prepareCalls).hasSize(1);
		assertThat(fakeRuntime.prepareCalls).isEmpty();

		UUID avatarId = UUID.fromString(item.id());
		avatars.dispatchPrepare(mediaRepository.findAvatarById(avatarId).block(Duration.ofSeconds(10)), jpegBytes())
				.block(Duration.ofSeconds(10));
		assertThat(fakeRuntime.prepareCalls).containsExactly(avatarId.toString());

		// 同键同体重放：返回同一资源，不二次调 provider。
		AvatarItem replay = avatars.create(actor, mediaId, true, "dh-avatar-v1", requestId, false)
				.block(Duration.ofSeconds(10));
		assertThat(replay.id()).isEqualTo(item.id());
		assertThat(fakeProvider.prepareCalls).hasSize(1);

		// INTERNAL11 ready：manifest 逐 key 登记清理、bundle 落库、ready 可选。
		ArtifactAcceptance accepted = avatars.applyAvatarArtifact(avatarId, 1, manifestJson(item.id(),
				"dhav-" + item.id() + "-r1-normalized_png", "dhav-" + item.id() + "-r1-preview_jpg"), null)
				.block(Duration.ofSeconds(10));
		assertThat(accepted.accepted()).isTrue();

		AvatarItem ready = avatars.get(actor, avatarId).block(Duration.ofSeconds(10));
		assertThat(ready.state()).isEqualTo(AvatarState.ready);
		assertThat(countRows("dh_cleanup",
				"resource_kind = 'avatar_object' AND resource_id = CAST('" + avatarId + "' AS uuid)")).isEqualTo(2);
		AvatarRow row = mediaRepository.findAvatarById(avatarId).block(Duration.ofSeconds(10));
		assertThat(row.bundleManifest()).contains("normalized_png").contains("preview_jpg");

		// 跨 owner 读取 → 404（不泄漏存在性）。
		assertThatThrownBy(() -> avatars.get(new PersonalActor(other), avatarId).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));
	}

	// ---------- TC105F-01-02：远端 0/多脸/额外收费/配置撤销 + 同键重放回失败 ----------

	@Test
	void tc105f_01_02_remoteRejectionsLeaveFailedRowWithoutRuntimeDispatch() {
		UUID mediaId = seedMedia(account, null, MediaStatus.ACTIVE, "image/jpeg", jpegBytes());

		fakeProvider.faceCount = 0;
		UUID requestIdZero = UUID.randomUUID();
		assertThatThrownBy(() -> avatars.create(actor, mediaId, true, "dh-avatar-v1", requestIdZero, false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(422);
					assertThat(e.code()).isEqualTo("dh_image_rejected");
				});
		assertThat(fakeRuntime.prepareCalls).isEmpty();
		assertThat(countRows("dh_avatar", "status = 'failed' AND error_code = 'dh_image_rejected'")).isEqualTo(1);

		fakeProvider.faceCount = 2;
		assertThatThrownBy(() -> avatars.create(actor, mediaId, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_image_rejected"));

		fakeProvider.faceCount = 1;
		fakeProvider.extraCharge = true;
		assertThatThrownBy(() -> avatars.create(actor, mediaId, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(409);
					assertThat(e.code()).isEqualTo("dh_configuration_changed");
				});

		fakeProvider.extraCharge = false;
		db.sql("DELETE FROM platform_model_config WHERE capability = 'digital_human_render'").then()
				.block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> avatars.create(actor, mediaId, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_configuration_changed"));
		assertThat(fakeRuntime.prepareCalls).isEmpty();

		// 配置恢复后，同键重放回原失败（不二次调 provider）。
		seedRenderControlPlane("it-dh-av-cred2");
		int prepareCountBefore = fakeProvider.prepareCalls.size();
		AvatarItem replay = avatars.create(actor, mediaId, true, "dh-avatar-v1", requestIdZero, false)
				.block(Duration.ofSeconds(10));
		assertThat(replay.state()).isEqualTo(AvatarState.failed);
		assertThat(replay.reasonCode()).isEqualTo("dh_image_rejected");
		assertThat(fakeProvider.prepareCalls).hasSize(prepareCountBefore);
	}

	@Test
	void tc105f_01_02_localRejectionsWithoutPersistingRows() {
		// 伪 mime（声明 jpeg、内容非图）→ 真实解码拒绝（content-type 不作解码证明）。
		UUID fakeMime = seedMedia(account, null, MediaStatus.ACTIVE, "image/jpeg",
				"plain text pretending to be jpeg".getBytes());
		assertThatThrownBy(() -> avatars.create(actor, fakeMime, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_image_rejected"));

		// 10MiB+1（E22）。
		UUID oversize = seedMedia(account, null, MediaStatus.ACTIVE, "image/jpeg", new byte[10 * 1024 * 1024 + 1]);
		assertThatThrownBy(() -> avatars.create(actor, oversize, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_image_rejected"));

		// customAvatarEnabled=false（K10 默认）→ 404 dh_feature_disabled。
		db.sql("UPDATE dh_catalog SET config_json = jsonb_set(config_json, '{customAvatarEnabled}', 'false')").then()
				.block(Duration.ofSeconds(10));
		UUID valid = seedMedia(account, null, MediaStatus.ACTIVE, "image/jpeg", jpegBytes());
		assertThatThrownBy(() -> avatars.create(actor, valid, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_feature_disabled"));

		assertThat(fakeProvider.prepareCalls).isEmpty();
		assertThat(fakeRuntime.prepareCalls).isEmpty();
		assertThat(countRows("dh_avatar", "owner_account_id = '" + account + "'")).isZero();
	}

	@Test
	void tc105f_01_02_rightsNotAcceptedRejected() {
		UUID mediaId = seedMedia(account, null, MediaStatus.ACTIVE, "image/jpeg", jpegBytes());
		assertThatThrownBy(() -> avatars.create(actor, mediaId, false, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invalid_input"));
		assertThatThrownBy(() -> avatars.create(actor, mediaId, true, "dh-avatar-v0", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invalid_input"));
		assertThat(countRows("dh_avatar", "owner_account_id = '" + account + "'")).isZero();
	}

	// ---------- TC105F-01-03：越权/任意路径 ----------

	@Test
	void tc105f_01_03_crossOwnerUnknownAndMalformedRejectedWithoutSideEffects() {
		UUID otherMedia = seedMedia(other, null, MediaStatus.ACTIVE, "image/jpeg", jpegBytes());
		assertThatThrownBy(() -> avatars.create(actor, otherMedia, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));

		assertThatThrownBy(
				() -> avatars.create(actor, UUID.randomUUID(), true, "dh-avatar-v1", UUID.randomUUID(), false)
						.block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));

		// 组织名下媒体不算个人（即使 owner 是本人）。
		UUID orgMedia = seedMedia(account, UUID.randomUUID().toString(), MediaStatus.ACTIVE, "image/jpeg", jpegBytes());
		assertThatThrownBy(() -> avatars.create(actor, orgMedia, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));

		assertThat(fakeProvider.prepareCalls).isEmpty();
		assertThat(fakeRuntime.prepareCalls).isEmpty();
		assertThat(countRows("dh_avatar", "owner_account_id = '" + account + "'")).isZero();
		// B 的 media 行不被触碰（不随失败擅自删他人/shared 引用）。
		assertThat(countRows("media_reference", "id = CAST('" + otherMedia + "' AS uuid)")).isEqualTo(1);
	}

	// ---------- TC105F-01-04：部分派生失败、迟到墓碑、远端删除重试 ----------

	@Test
	void tc105f_01_04_partialFailureTombstoneAndRemoteDeleteRetry() {
		AvatarRow row = createProcessing(account);
		UUID avatarId = UUID.fromString(row.id());
		UUID mediaId = UUID.fromString(row.sourceMediaId());

		// 部分派生失败：errorCode + 部分 manifest（已写对象仍登记清理）。
		ArtifactAcceptance partial = avatars.applyAvatarArtifact(avatarId, 1,
				manifestJson(row.id(), "dhav-" + row.id() + "-r1-normalized_png"), "dh_media_write_failed")
				.block(Duration.ofSeconds(10));
		assertThat(partial.accepted()).isTrue();
		AvatarRow failed = awaitStatus(avatarId, "failed");
		assertThat(failed.errorCode()).isEqualTo("dh_media_write_failed");
		assertThat(countRows("dh_cleanup",
				"resource_kind = 'avatar_object' AND resource_id = CAST('" + avatarId + "' AS uuid)")).isEqualTo(1);

		// 迟到 ready 被墓碑拦截（failed 终态不复活，K14.4 F01）。
		ArtifactAcceptance late = avatars.applyAvatarArtifact(avatarId, 1, manifestJson(row.id(),
				"dhav-" + row.id() + "-r1-normalized_png", "dhav-" + row.id() + "-r1-preview_jpg"), null)
				.block(Duration.ofSeconds(10));
		assertThat(late.accepted()).isFalse();
		assertThat(mediaRepository.findAvatarById(avatarId).block(Duration.ofSeconds(10)).status()).isEqualTo("failed");

		// 删除 → revoked 立即拒新建；远端删除第一次未确认 → 批次记 failed；第二次确认 → 收口 deleted。
		AvatarRow revoked = avatars.delete(actor, avatarId, UUID.randomUUID()).block(Duration.ofSeconds(10));
		assertThat(revoked.status()).isEqualTo("revoked");
		// revoked 后新建同 avatarId 的角色组合由目录 422（personal 不在 catalog）——此处直接验证重放幂等：
		AvatarRow replayDelete = avatars.delete(actor, avatarId, UUID.randomUUID()).block(Duration.ofSeconds(10));
		assertThat(replayDelete.status()).isEqualTo("revoked");

		fakeProvider.failRemoteDeleteFirstN = 1;
		avatars.processCleanup(avatarId).block(Duration.ofSeconds(20));
		assertThat(fakeProvider.deleteCalls).hasSize(1);
		assertThat(fakeRuntime.deleteCalls).containsExactly(avatarId.toString());
		assertThat(mediaRepository.listCleanupForResource("avatar_external", avatarId).block(Duration.ofSeconds(10))
				.stream().filter(r -> "failed".equals(r.state())).count()).isEqualTo(1L);

		avatars.processCleanup(avatarId).block(Duration.ofSeconds(20));
		assertThat(fakeProvider.deleteCalls).hasSize(2);
		AvatarRow deleted = awaitStatus(avatarId, "deleted");
		assertThat(deleted.status()).isEqualTo("deleted");
		assertThat(countRows("dh_cleanup",
				"resource_id = CAST('" + avatarId + "' AS uuid)" + " AND state NOT IN ('deleted')")).isZero();

		// 原 media 不被清理波及（生命周期仍由原库决定）。
		assertThat(countRows("media_reference", "id = CAST('" + mediaId + "' AS uuid)")).isEqualTo(1);

		// 删除后读取被墓碑拦截：GET 404。
		assertThatThrownBy(() -> avatars.get(actor, avatarId).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));
	}

	@Test
	void tc105f_01_04_deleteWithActiveSessionReferenceConflicts() {
		AvatarRow row = createProcessing(account);
		UUID avatarId = UUID.fromString(row.id());
		UUID profileId = UUID.randomUUID();
		db.sql("INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status) VALUES"
				+ " (CAST(:p AS uuid), :owner, '角色', 1, 'active')").bind("p", profileId.toString())
				.bind("owner", account).then().block(Duration.ofSeconds(10));
		db.sql("INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting,"
				+ " tone, avatar_id, avatar_revision, voice_id, catalog_version) VALUES (CAST(:r AS uuid), :owner,"
				+ " CAST(:p AS uuid), 1, 'p', 'g', 'natural', CAST(:av AS uuid), 1, 'v1', 1)")
				.bind("r", UUID.randomUUID().toString()).bind("owner", account).bind("p", profileId.toString())
				.bind("av", avatarId.toString()).then().block(Duration.ofSeconds(10));
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'dh-it-backend',"
				+ " CAST(:pf AS uuid), CAST(:c AS uuid), CAST('{}' AS jsonb), 'ready', now(), 1)")
				.bind("s", UUID.randomUUID().toString()).bind("owner", account).bind("p", profileId.toString())
				.bind("pf", UUID.randomUUID().toString()).bind("c", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));

		assertThatThrownBy(() -> avatars.delete(actor, avatarId, UUID.randomUUID()).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(409);
					assertThat(e.code()).isEqualTo("dh_state_conflict");
				});
		assertThat(mediaRepository.findAvatarById(avatarId).block(Duration.ofSeconds(10)).status())
				.isEqualTo("processing");
	}

	// ---------- API30-32 HTTP 信封（装配层） ----------

	@Test
	void tc105f_01_01_httpEndpointsEnvelope() {
		UUID mediaId = seedMedia(account, null, MediaStatus.ACTIVE, "image/jpeg", jpegBytes());
		WebTestClient client = client();
		String createBody = """
				{"requestId":"%s","mediaId":"%s","rightsAccepted":true,"rightsVersion":"dh-avatar-v1"}
				""".formatted(UUID.randomUUID(), mediaId);

		byte[] created = client.post().uri("/api/digital-human/avatars")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(createBody).exchange().expectStatus().isAccepted().expectBody().jsonPath("$.success")
				.isEqualTo(true).jsonPath("$.data.state").isEqualTo("processing").jsonPath("$.data.source")
				.isEqualTo("personal").jsonPath("$.data.revision").isEqualTo(1).returnResult().getResponseBody();
		String avatarId;
		try {
			avatarId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data").path("id")
					.asText();
		} catch (Exception invalid) {
			throw new IllegalStateException("响应解码失败", invalid);
		}

		client.get().uri("/api/digital-human/avatars/{id}", avatarId)
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.state").isEqualTo("processing");

		client.method(org.springframework.http.HttpMethod.DELETE).uri("/api/digital-human/avatars/{id}", avatarId)
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\"}").exchange().expectStatus().isAccepted()
				.expectBody().jsonPath("$.data.kind").isEqualTo("avatar_delete").jsonPath("$.data.state")
				.isEqualTo("succeeded").jsonPath("$.data.resourceId").isEqualTo(avatarId);

		// 未登录 401（零业务写）。
		client.post().uri("/api/digital-human/avatars").contentType(MediaType.APPLICATION_JSON).bodyValue(createBody)
				.exchange().expectStatus().isUnauthorized();
		client.get().uri("/api/digital-human/avatars/{id}", avatarId).exchange().expectStatus().isUnauthorized();

		// 未知字段拒绝（K00 严格解码）。
		client.post().uri("/api/digital-human/avatars").header("X-Grassland-Identity", sign(account, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						"""
								{"requestId":"%s","mediaId":"%s","rightsAccepted":true,"rightsVersion":"dh-avatar-v1","orgId":"x"}
								"""
								.formatted(UUID.randomUUID(), mediaId))
				.exchange().expectStatus().isEqualTo(422);
	}
}
