package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactService.SaveOutcome;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarItem;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanAvatarService.ArtifactAcceptance;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * S2 媒体闭环隐私与撤销 IT（任务书 #105F C105F-05 / TC105F-05-03、TC105F-05-04 Java 侧子断言）。
 *
 * <p>
 * 隐私分工如实注明（K12：跨语言同 TC 各有子断言，不互相替代）：
 * <ul>
 * <li>「录制分支永不接 mic」的结构性排除与输出音频频谱不含 mic 频标（1kHz 在/2kHz 不在）在
 * {@code tests/test_recording.py}（真编码 + FFT）；
 * <li>本文件证明 Java 持久链：下载/保存的产物字节与 runtime 产物<b>逐字节一致</b>（无注入/变换， mic
 * 字节因此无从混入）、对象存储与 media 表不含 mic 合成标记、无任何 audio/* 持久行、 未同意挂载的字幕不留持久面、库内 checksum
 * 与文件 SHA 一致。
 * </ul>
 *
 * <p>
 * 撤销（API32 授权撤回）：活动会话引用 409（不得静默终止活动会话——先结束会话再撤）；无活动 引用 revoked
 * 立即生效；清理批次真实删派生（runtime INTERNAL13 + 第三方远端删除）全部收口后 deleted；迟到 INTERNAL11
 * 不复活；对已撤销/未登记 personal 形象的组合校验 422（新建拒绝）。 E18 六窗口撤权删除的完整矩阵归 C105G-05。
 */
@Import({DigitalHumanAvatarIT.FakeAvatarNetwork.class, DigitalHumanArtifactIT.FakeArtifactNetwork.class})
class DigitalHumanMediaLifecycleIT extends IntelligenceItSupport {

	@Autowired
	private DatabaseClient db;
	@Autowired
	private DigitalHumanArtifactService artifacts;
	@Autowired
	private DigitalHumanAvatarService avatars;
	@Autowired
	private DigitalHumanMediaRepository mediaRepository;
	@Autowired
	private DigitalHumanCatalogService catalog;
	@Autowired
	private DigitalHumanAvatarIT.FakeAvatarRenderProvider fakeProvider;
	@Autowired
	private DigitalHumanAvatarIT.FakeAvatarRuntimePort fakeRuntime;
	@Autowired
	private DigitalHumanArtifactIT.FakeArtifactFetchPort fakeFetch;
	@MockitoBean
	private ObjectStorageAdapter storage;

	private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();
	private final List<String> putCalls = new CopyOnWriteArrayList<>();

	private final String account = "dh-life-a-" + UUID.randomUUID();
	private final PersonalActor actor = new PersonalActor(account);
	private final String other = "dh-life-b-" + UUID.randomUUID();

	/** mic 合成标记（Given：mic 含独立合成标记）。仅作为假设泄漏源在内存中持有，绝不入库。 */
	private static final byte[] MIC_MARKER = "DH-MIC-INPUT-MARKER-2KHZ-BLOCK".getBytes(StandardCharsets.UTF_8);

	private static final String SAMPLE_SRT = "1\n00:00:00,000 --> 00:00:01,000\n你好，草场\n\n";

	@BeforeEach
	void seed() {
		cleanSharedDhState();
		seedRenderControlPlane("it-dh-life-cred");
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by) VALUES (1, 1,"
				+ " CAST(:config AS jsonb), 'it')")
				.bind("config",
						"{\"enabled\":true,\"newSessionsAllowed\":true,\"recordingEnabled\":true,"
								+ "\"customAvatarEnabled\":true,\"allowedBackendIds\":[\"dh-it-backend\"],"
								+ "\"avatars\":[],\"voices\":[]}")
				.then().block(Duration.ofSeconds(10));
		objectStore.clear();
		putCalls.clear();
		fakeFetch.objects.clear();
		fakeFetch.fetchCalls.clear();
		fakeProvider.faceCount = 1;
		fakeProvider.extraCharge = false;
		fakeProvider.failRemoteDeleteFirstN = 0;
		fakeProvider.prepareCalls.clear();
		fakeProvider.deleteCalls.clear();
		fakeRuntime.prepareCalls.clear();
		fakeRuntime.deleteCalls.clear();
		reset(storage);
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			byte[] content = invocation.getArgument(1);
			objectStore.put(key, content);
			putCalls.add(key);
			return null;
		}).when(storage).putObject(anyString(), any(byte[].class), anyString());
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			byte[] content = objectStore.get(key);
			return content == null
					? Optional.empty()
					: Optional.of(new StoredObject(key, content.length, "application/octet-stream", "etag", null));
		}).when(storage).headObject(anyString());
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			byte[] content = objectStore.get(key);
			if (content == null) {
				throw new IllegalStateException("未配置的对象 key：" + key);
			}
			return content;
		}).when(storage).getObject(anyString());
	}

	@AfterEach
	void cleanUp() {
		// 共享容器全量 :check 里本类之后还有 speech/imagestudio 等类，其 setup 按 purpose 域 DELETE
		// media_reference；本类（及同 seeding 的 AvatarIT，先于本类被 @BeforeEach 自清）留下的 dh_avatar
		// 行会让该 DELETE 撞 dh_avatar_source_media_id_fkey。不收尾 = 跨类污染（全量 :check 39 失败实录）。
		cleanSharedDhState();
	}

	/** 依赖序自清（共享容器跨类残留自愈）：附件→资产→media→登记→段/形象→操作→会话链。 */
	private void cleanSharedDhState() {
		db.sql("DELETE FROM dh_asset_attachment").then()
				.then(db.sql("DELETE FROM content_asset WHERE source = 'dh_recording'").then())
				.then(db.sql("DELETE FROM dh_cleanup").then()).then(db.sql("DELETE FROM dh_avatar").then())
				.then(db.sql("DELETE FROM media_reference WHERE domain_type IN ('dh_recording', 'dh-avatar-it')")
						.then())
				.then(db.sql("DELETE FROM dh_recording").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind LIKE 'recording_%' OR kind LIKE 'avatar_%'").then())
				.then(db.sql("DELETE FROM dh_invocation").then()).then(db.sql("DELETE FROM dh_preview").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_turn").then()).then(db.sql("DELETE FROM dh_session").then())
				.then(db.sql("DELETE FROM dh_profile_revision").then()).then(db.sql("DELETE FROM dh_profile").then())
				.then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
						+ " (SELECT id FROM platform_model_config WHERE capability = 'digital_human_render')").then())
				.then(db.sql("DELETE FROM platform_model_config WHERE capability = 'digital_human_render'").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-dh-life-%'").then())
				.then(db.sql("DELETE FROM dh_catalog").then()).block(Duration.ofSeconds(10));
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
				SELECT 'digital_human_render', 'primary', 'fake-dh-avatar', 'dh-life-it-model', :baseUrl,
				    'healthy', true, 1, cred.id FROM cred
				""").bind("credName", credentialName).bind("baseUrl", baseUrl).then().block(Duration.ofSeconds(10));
	}

	// ---------- 造数 ----------

	private static byte[] fixture(String name) {
		try (var input = DigitalHumanMediaLifecycleIT.class.getResourceAsStream("/" + name)) {
			assertThat(input).as("夹具 %s 必须在 test resources", name).isNotNull();
			return input.readAllBytes();
		} catch (IOException failure) {
			throw new IllegalStateException("夹具读取失败：" + name, failure);
		}
	}

	private UUID seedReadyRecording(String owner, byte[] video, long manifestDurationMs, boolean withSubtitle) {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'dh-it-backend',"
				+ " CAST(:pf AS uuid), CAST(:c AS uuid), CAST('{}' AS jsonb), 'ended', now(), 1)")
				.bind("s", sessionId.toString()).bind("owner", owner).bind("p", UUID.randomUUID().toString())
				.bind("pf", UUID.randomUUID().toString()).bind("c", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		UUID recordingId = UUID.randomUUID();
		String videoRef = "dhr-" + recordingId + "-mp4";
		String srtRef = "dhr-" + recordingId + "-srt";
		fakeFetch.objects.put(videoRef, video);
		byte[] srt = SAMPLE_SRT.getBytes(StandardCharsets.UTF_8);
		fakeFetch.objects.put(srtRef, srt);
		String manifest = "{\"recordingId\":\"" + recordingId + "\",\"startProgramMs\":1000,\"endProgramMs\":4000,"
				+ "\"partial\":false,\"video\":{\"objectRef\":\"" + videoRef + "\",\"sha256\":\""
				+ MediaChecksums.sha256(video) + "\",\"sizeBytes\":" + video.length + ",\"durationMs\":"
				+ manifestDurationMs + ",\"width\":64,\"height\":36,\"fps\":10.0,\"videoCodec\":\"h264\","
				+ "\"audioCodec\":\"aac\",\"audioSampleRate\":48000},\"subtitle\":"
				+ (withSubtitle
						? "{\"objectRef\":\"" + srtRef + "\",\"sha256\":\"" + MediaChecksums.sha256(srt)
								+ "\",\"sizeBytes\":" + srt.length + "}"
						: "null")
				+ ",\"errorCode\":null}";
		db.sql("INSERT INTO dh_recording(id, owner_account_id, session_id, start_command_id, state, partial,"
				+ " start_program_ms, manifest, size_bytes, duration_ms, expires_at)"
				+ " VALUES (CAST(:r AS uuid), :owner, CAST(:s AS uuid), CAST(:cmd AS uuid), 'ready', false, 0,"
				+ " CAST(:manifest AS jsonb), :size, :duration, now() + interval '24 hours')")
				.bind("r", recordingId.toString()).bind("owner", owner).bind("s", sessionId.toString())
				.bind("cmd", UUID.randomUUID().toString()).bind("manifest", manifest).bind("size", video.length)
				.bind("duration", manifestDurationMs).then().block(Duration.ofSeconds(10));
		return recordingId;
	}

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

	private UUID seedMedia(String owner, String mime, byte[] bytes) {
		UUID id = UUID.randomUUID();
		String key = "it-dh-avatar/" + id;
		doReturn(bytes).when(storage).getObject(key);
		db.sql("INSERT INTO media_reference(id, owner_account_id, organization_id, purpose,"
				+ " object_key, mime_type, size_bytes, checksum, source, status, domain_type)"
				+ " VALUES (CAST(:id AS uuid), :owner, NULL, 'user_upload', :key, :mime, :size, 'sha',"
				+ " 'upload', 'active', 'dh-avatar-it')").bind("id", id.toString()).bind("owner", owner)
				.bind("key", key).bind("mime", mime).bind("size", (long) bytes.length).then()
				.block(Duration.ofSeconds(10));
		return id;
	}

	/** 上传→ready（真实 create/dispatch/INTERNAL11 链，fake 网络）。返回 avatarId。 */
	private UUID readyAvatar(String ownerId) {
		UUID mediaId = seedMedia(ownerId, "image/jpeg", jpegBytes());
		AvatarItem item = avatars
				.create(new PersonalActor(ownerId), mediaId, true, "dh-avatar-v1", UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10));
		UUID avatarId = UUID.fromString(item.id());
		avatars.dispatchPrepare(mediaRepository.findAvatarById(avatarId).block(Duration.ofSeconds(10)), jpegBytes())
				.block(Duration.ofSeconds(10));
		String manifest = "{\"avatarId\":\"" + avatarId + "\",\"revision\":1,\"backendId\":\"dh-it-backend\","
				+ "\"files\":[{\"objectRef\":\"dhav-" + avatarId + "-r1-normalized_png\","
				+ "\"relativePath\":\"normalized.png\",\"sha256\":\"a1\",\"sizeBytes\":11,"
				+ "\"contentType\":\"image/png\"}]}";
		avatars.applyAvatarArtifact(avatarId, 1, manifest, null).block(Duration.ofSeconds(10));
		return avatarId;
	}

	/** 直插 profile+revision+活动会话（引用该 avatar）——API 侧组合校验不收 personal，DB 引用链真实。 */
	private void seedSessionReferencingAvatar(String ownerId, UUID avatarId, String sessionState) {
		UUID profileId = UUID.randomUUID();
		db.sql("INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)"
				+ " VALUES (CAST(:p AS uuid), :owner, '角色', 1, 'active')").bind("p", profileId.toString())
				.bind("owner", ownerId).then().block(Duration.ofSeconds(10));
		db.sql("INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting,"
				+ " tone, avatar_id, avatar_revision, voice_id, catalog_version)"
				+ " VALUES (CAST(:r AS uuid), :owner, CAST(:p AS uuid), 1, '正式', '你好', 'natural',"
				+ " CAST(:a AS uuid), 1, 'v1', 1)").bind("r", UUID.randomUUID().toString()).bind("owner", ownerId)
				.bind("p", profileId.toString()).bind("a", avatarId.toString()).then().block(Duration.ofSeconds(10));
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'dh-it-backend',"
				+ " CAST(:pf AS uuid), CAST(:c AS uuid), CAST('{}' AS jsonb), CAST(:state AS text), now(), 1)")
				.bind("s", UUID.randomUUID().toString()).bind("owner", ownerId).bind("p", profileId.toString())
				.bind("pf", UUID.randomUUID().toString()).bind("c", UUID.randomUUID().toString())
				.bind("state", sessionState).then().block(Duration.ofSeconds(10));
	}

	private long countRows(String table, String where) {
		Long count = db.sql("SELECT count(*) AS n FROM " + table + " WHERE " + where)
				.map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private String avatarStatus(UUID avatarId) {
		return db.sql("SELECT status FROM dh_avatar WHERE id = CAST(:id AS uuid)").bind("id", avatarId.toString())
				.map(row -> row.get("status", String.class)).one().block(Duration.ofSeconds(10));
	}

	private String awaitAvatarStatus(UUID avatarId, String expected) {
		long deadline = System.currentTimeMillis() + 10_000;
		String status = null;
		while (System.currentTimeMillis() < deadline) {
			status = avatarStatus(avatarId);
			if (expected.equals(status)) {
				return status;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException interrupt) {
				Thread.currentThread().interrupt();
			}
		}
		throw new AssertionError("avatar 未进入 " + expected + "，当前=" + status);
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer : for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	// ---------- TC105F-05-03：录制隐私（Java 持久链子断言） ----------

	@Test
	void tc105f_05_03_micMarkerAbsentFromPersistedChain() {
		byte[] video = fixture("dh-recording-valid.mp4");
		long durationMs = DigitalHumanArtifactProbe.probe(writeTemp(video)).durationMs();
		UUID recordingId = seedReadyRecording(account, video, durationMs, true);

		SaveOutcome saved = artifacts.save(actor, recordingId, UUID.randomUUID(), "隐私链录制", true)
				.block(Duration.ofSeconds(30));
		assertThat(saved.completedNow()).isTrue();
		assertThat(putCalls).hasSize(2);

		// ① 身份链：对象存储字节与 runtime 产物逐字节一致（Java 不注入/不变换——mic 无从混入）。
		byte[] storedVideo = objectStore.get(DigitalHumanArtifactService
				.objectKey(DigitalHumanArtifactService.derivedMediaId(recordingId, "video")));
		assertThat(storedVideo).isEqualTo(video);
		byte[] storedSrt = objectStore.get(DigitalHumanArtifactService
				.objectKey(DigitalHumanArtifactService.derivedMediaId(recordingId, "subtitle")));
		assertThat(new String(storedSrt, StandardCharsets.UTF_8)).contains("你好，草场");

		// ② 下载路径同 identity（API36 服务层：读的是同一对象，不存在第二份转换副本）。
		DigitalHumanArtifactService.DownloadResponse download = artifacts.download(actor, recordingId, "mp4", null)
				.block(Duration.ofSeconds(10));
		assertThat(download).isNotNull();
		assertThat(download.body()).isEqualTo(video);
		assertThat(download.contentType()).isEqualTo("video/mp4");

		// ③ mic 合成标记不在任何持久对象（视频/字幕逐字节扫描）。
		assertThat(contains(storedVideo, MIC_MARKER)).as("视频产物不得含 mic 标记").isFalse();
		assertThat(contains(storedSrt, MIC_MARKER)).as("字幕产物不得含 mic 标记").isFalse();

		// ④ 无任何 audio/* 持久行：该 owner 的 media 持久面只有录制视频与字幕两类。
		assertThat(countRows("media_reference", "owner_account_id = '" + account + "'")).isEqualTo(2);
		assertThat(
				countRows("media_reference", "owner_account_id = '" + account + "'" + " AND mime_type LIKE 'audio/%'"))
				.as("无 mic 音频持久行").isZero();

		// ⑤ 文件 SHA 与库记录一致（checksum 列 == 产物 sha256）。
		String storedChecksum = db.sql("SELECT checksum FROM media_reference WHERE id = CAST(:id AS uuid)")
				.bind("id", DigitalHumanArtifactService.derivedMediaId(recordingId, "video").toString())
				.map(row -> row.get("checksum", String.class)).one().block(Duration.ofSeconds(10));
		assertThat(storedChecksum).isEqualTo(MediaChecksums.sha256(video));
	}

	@Test
	void tc105f_05_03_unconsentedSubtitleLeavesNoPersistentSurface() {
		byte[] video = fixture("dh-recording-valid.mp4");
		long durationMs = DigitalHumanArtifactProbe.probe(writeTemp(video)).durationMs();
		// includeSubtitles=false：未同意挂载的字幕不留任何持久面（不存对象、不建 media/附件行）。
		UUID recordingId = seedReadyRecording(account, video, durationMs, true);
		SaveOutcome saved = artifacts.save(actor, recordingId, UUID.randomUUID(), "不带字幕", false)
				.block(Duration.ofSeconds(30));
		assertThat(saved.completedNow()).isTrue();
		assertThat(putCalls).hasSize(1);
		assertThat(countRows("media_reference", "owner_account_id = '" + account + "'")).isEqualTo(1);
		assertThat(countRows("dh_asset_attachment", "owner_account_id = '" + account + "'")).isZero();
	}

	private static java.nio.file.Path writeTemp(byte[] bytes) {
		try {
			java.nio.file.Path temp = java.nio.file.Files.createTempFile("dh-life-it-", ".mp4");
			java.nio.file.Files.write(temp, bytes);
			return temp;
		} catch (IOException failure) {
			throw new IllegalStateException("临时文件写入失败", failure);
		}
	}

	// ---------- TC105F-05-04：功能撤销 ----------

	@Test
	void tc105f_05_04_revokeWithoutActiveReferenceCleansDerivativesAndBlocksResurrection() {
		UUID avatarId = readyAvatar(account);
		assertThat(avatarStatus(avatarId)).isEqualTo("ready");

		// 新建拒绝（组合校验）：personal 形象不在目录——撤销前即不可绑定角色（不因撤销而放宽）。
		var input = new DigitalHumanRecords.ProfileInput("角色", "正式", "你好", DigitalHumanRecords.Tone.natural,
				avatarId.toString(), "v1", 1);
		assertThatThrownBy(() -> catalog.validateCombination(input).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(422));

		// 无活动引用：撤销立即生效（revoked）→ 清理批次真实删派生（runtime + 远端）→ deleted。
		DigitalHumanMediaRepository.AvatarRow revoked = avatars.delete(actor, avatarId, UUID.randomUUID())
				.block(Duration.ofSeconds(10));
		assertThat(revoked.status()).isEqualTo("revoked");
		// 删除返回即 revoked（立即拒新建）；清理批次同步驱动（生产由 revoke 内联 kick + G worker 兜底）。
		avatars.processCleanup(avatarId).block(Duration.ofSeconds(20));
		awaitAvatarStatus(avatarId, "deleted");
		assertThat(
				countRows("dh_cleanup", "resource_id = CAST('" + avatarId + "' AS uuid) AND state NOT IN ('deleted')"))
				.isZero();

		// 迟到 INTERNAL11（撤销后旧任务回执）：不复活——accepted=false 且状态保持 deleted。
		ArtifactAcceptance late = avatars
				.applyAvatarArtifact(avatarId, 1,
						"{\"avatarId\":\"" + avatarId + "\",\"revision\":1,\"backendId\":\"dh-it-backend\","
								+ "\"files\":[{\"objectRef\":\"dhav-late\",\"relativePath\":\"x.png\","
								+ "\"sha256\":\"a1\",\"sizeBytes\":11,\"contentType\":\"image/png\"}]}",
						null)
				.block(Duration.ofSeconds(10));
		assertThat(late.accepted()).isFalse();
		assertThat(avatarStatus(avatarId)).isEqualTo("deleted");
		assertThatThrownBy(() -> avatars.get(actor, avatarId).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));

		// 跨主体：他人删除不可见（404 不泄漏存在性）。
		assertThatThrownBy(() -> avatars.delete(new PersonalActor(other), avatarId, UUID.randomUUID())
				.block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));
	}

	@Test
	void tc105f_05_04_activeSessionReferenceRefusesSilentRevokeUntilEnded() {
		UUID avatarId = readyAvatar(account);
		seedSessionReferencingAvatar(account, avatarId, "responding");

		// 活动会话引用：拒绝撤销（409）——不得静默终止活动会话；状态保持 ready。
		assertThatThrownBy(() -> avatars.delete(actor, avatarId, UUID.randomUUID()).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(409);
					assertThat(e.code()).isEqualTo("dh_state_conflict");
				});
		assertThat(avatarStatus(avatarId)).isEqualTo("ready");
		assertThat(fakeRuntime.deleteCalls).isEmpty();

		// 会话结束后撤销生效（终态 + cleanup_pending=false 即不再计活动引用）。
		db.sql("UPDATE dh_session SET state = 'ended', cleanup_pending = false").then().block(Duration.ofSeconds(10));
		DigitalHumanMediaRepository.AvatarRow revoked = avatars.delete(actor, avatarId, UUID.randomUUID())
				.block(Duration.ofSeconds(10));
		assertThat(revoked.status()).isEqualTo("revoked");
		avatars.processCleanup(avatarId).block(Duration.ofSeconds(20));
		awaitAvatarStatus(avatarId, "deleted");
		assertThat(fakeRuntime.deleteCalls).contains(avatarId.toString());
	}
}
