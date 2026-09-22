package com.grassland.intelligence.compliance;

import static com.grassland.identity.assertion.TestAssertionHelper.serviceSigner;
import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.PresignRequest;
import com.grassland.storage.StoredObject;
import com.grassland.storage.UploadTicket;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 注销对象物删 IT（任务书 #103 C103-10 / TC103-10-01~04）：按 manifest 精确 key
 * 物删（媒体/导出/暂存/token 缓存）；KYB 证据租约与共享素材挂载保留并记原因；配额 exactly-once 释放；存储故障保持 pending
 * 重试、恢复后收敛； 重复推进幂等；他人对象不动。C103-09 的 verify 只在对象全部终态后 completed（erased=true）。
 */
@Import(PersonalDataObjectCleanupIT.InMemoryStorageConfig.class)
class PersonalDataObjectCleanupIT extends IntelligenceItSupport {

	@Test
	void manifestStillDeletesObjectWhenMediaRowWasAlreadyGarbageCollected() {
		String account = "orphan-" + UUID.randomUUID();
		String key = "media/orphan-" + UUID.randomUUID();
		String media = seedMedia(account, key, "generated");
		UUID request = UUID.randomUUID();
		lifecycle.prepare(account, request).block();
		var manifest = erasure.plan(account, request).block();
		db.sql("DELETE FROM media_reference WHERE id=CAST(:id AS uuid)").bind("id", media).then().block();
		cleanup.advance(manifest.id()).block();
		assertThat(storage.objects).doesNotContainKey(key);
		assertThat(storage.deletedKeys).contains(key);
	}

	/**
	 * TC106-01-04 对象边界：归属冲突 manifest 的对象物删整段跳过——字节、媒体行状态与配额保持， 回执 needs_review；直接
	 * cleanup.advance 入口同样被门闸拦截（不绕过批次门闸物删）。
	 */
	@Test
	void conflictedManifestSkipsObjectCleanupEntirely() {
		String account = "t106obj-" + UUID.randomUUID();
		String other = "t106objo-" + UUID.randomUUID();
		UUID request = UUID.randomUUID();
		String hash = "h".repeat(64);
		String key = "media/t106-conflict";
		// F01 双链冲突 fixture：A 个人草稿 + B 个人分镜 + A agent 计划。
		db.sql("INSERT INTO creation_draft(id, owner_account_id, title, source_type, created_at, updated_at)"
				+ " VALUES (gen_random_uuid(), :a, '冲突草稿', 'independent', now(), now())").bind("a", account).then()
				.block();
		String otherStoryboard = UUID.randomUUID().toString();
		db.sql("INSERT INTO video_storyboard(id, account_id, target_duration_seconds, request_payload)"
				+ " VALUES (CAST(:s AS uuid), :a, 15, '{}'::jsonb)").bind("s", otherStoryboard).bind("a", other).then()
				.block();
		db.sql("INSERT INTO creation_canvas_agent_plan(id, account_id, operation_id, request_hash, draft_id,"
				+ " storyboard_id, base_draft_version, base_edit_version, base_canvas_revision, status,"
				+ " selected_node_ids, instruction, expires_at) VALUES (gen_random_uuid(), :a, gen_random_uuid(),"
				+ " CAST(:h AS char(64)), (SELECT id FROM creation_draft WHERE owner_account_id = :a LIMIT 1),"
				+ " CAST(:s AS uuid), 1, 1, 1, 'ready', '[]'::jsonb, '优化', now() + interval '1 day')")
				.bind("a", account).bind("h", hash).bind("s", otherStoryboard).then().block();
		seedMedia(account, key, "upload");
		seedQuota(account, 1, 1024);

		lifecycle.prepare(account, request).block();
		var manifest = erasure.plan(account, request).block();
		var receipt = erasure.process(manifest.id()).block();
		assertThat(receipt.state()).isEqualTo("needs_review");
		assertThat(receipt.erased()).isFalse();
		// 字节/行状态/配额全部保持（对象未物删、未标记 deleting、未释放）。
		assertThat(storage.objects).containsKey(key);
		assertThat(storage.deletedKeys).doesNotContain(key);
		assertThat((Object) db.sql("SELECT status FROM media_reference WHERE object_key = :k").bind("k", key)
				.map((r) -> r.get("status", String.class)).one().block()).isEqualTo("active");
		assertThat(quota(account)).isEqualTo("1/1024");
		assertThat(countObjects(manifest.id().toString(), "pending")).isEqualTo(1L);
		// 直接对象推进入口不能绕过门闸（D01：已有对象清理入口不能绕过此条件）。
		cleanup.advance(manifest.id()).block();
		assertThat(storage.objects).containsKey(key);
		assertThat(countObjects(manifest.id().toString(), "pending")).isEqualTo(1L);
	}

	/** 内存对象存储（IT 专用）：可注入故障 key；记录删除过的 key 供幂等断言。 */
	@TestConfiguration
	static class InMemoryStorageConfig {

		@Bean
		InMemoryStorage testObjectStorage() {
			return new InMemoryStorage();
		}
	}

	static final class InMemoryStorage implements ObjectStorageAdapter {

		final Map<String, byte[]> objects = new ConcurrentHashMap<>();
		final Set<String> failKeys = ConcurrentHashMap.newKeySet();
		final List<String> deletedKeys = new CopyOnWriteArrayList<>();

		@Override
		public UploadTicket presignUpload(PresignRequest request) {
			throw new UnsupportedOperationException("presign not needed in erasure IT");
		}

		@Override
		public URI presignDownload(String key, long expiresSeconds) {
			throw new UnsupportedOperationException("presign not needed in erasure IT");
		}

		@Override
		public void putObject(String key, byte[] content, String contentType) {
			objects.put(key, content);
		}

		@Override
		public byte[] getObject(String key) {
			byte[] content = objects.get(key);
			if (content == null) {
				throw new IllegalStateException("no such key: " + key);
			}
			return content;
		}

		@Override
		public java.util.Optional<StoredObject> headObject(String key) {
			byte[] content = objects.get(key);
			return java.util.Optional.ofNullable(content)
					.map((value) -> new StoredObject(key, value.length, null, null, Instant.now()));
		}

		@Override
		public void deleteObject(String key) {
			if (failKeys.contains(key)) {
				throw new IllegalStateException("simulated storage 5xx for " + key);
			}
			objects.remove(key);
			deletedKeys.add(key);
		}

		@Override
		public List<StoredObject> listObjects(String prefix) {
			return objects.keySet().stream().filter((key) -> key.startsWith(prefix))
					.map((key) -> new StoredObject(key, objects.get(key).length, null, null, Instant.now())).toList();
		}
	}

	@DynamicPropertySource
	static void erasureProps(DynamicPropertyRegistry registry) {
		registry.add("intelligence.erasure.enabled", () -> "false");
	}

	@Autowired
	private InMemoryStorage storage;

	@Autowired
	private IntelligenceAccountLifecycleRepository lifecycle;

	@Autowired
	private PersonalDataErasureService erasure;

	@Autowired
	private PersonalDataObjectCleanup cleanup;

	private String identityServiceAssertion() {
		Instant now = Instant.now();
		return serviceSigner("identity", "grassland-intelligence")
				.sign(new IdentityAssertion("service:identity", null, null, null, null, "service", "internal", null,
						"r", "t", "grassland-intelligence", now, now.plusSeconds(30), "service", "identity"));
	}

	private String seedMedia(String owner, String key, String source) {
		String id = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO media_reference(id, owner_account_id, purpose, object_key, mime_type,
				                            size_bytes, source, status)
				VALUES (CAST(:id AS uuid), :owner, 'user_upload', :key, 'image/png', 1024, :source, 'active')
				""").bind("id", id).bind("owner", owner).bind("key", key).bind("source", source).then().block();
		storage.putObject(key, new byte[]{1, 2, 3}, "image/png");
		return id;
	}

	private Map<String, Object> erase(String account, String closureRequestId) {
		byte[] body = client().post().uri("/internal/compliance/accounts/" + account + "/erase")
				.header("X-Grassland-Identity", identityServiceAssertion()).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("closureRequestId", closureRequestId)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.success").isEqualTo(true).returnResult().getResponseBody();
		try {
			Map<String, Object> envelope = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body,
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
					});
			@SuppressWarnings("unchecked")
			Map<String, Object> data = (Map<String, Object>) envelope.get("data");
			return data;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** TC103-10-01/02/04：全类别物删、租约/共享保留、配额只释放一次、重复幂等、他人对象不动。 */
	@Test
	void deletesExactObjectsRetainsLeasedAndReleasesQuotaOnce() {
		String account = "obj-" + UUID.randomUUID();
		String other = "other-" + UUID.randomUUID();
		UUID request = UUID.randomUUID();
		// A：个人媒体（upload 来源，占配额）→ 物删+配额释放。
		String mediaA = seedMedia(account, "media/obj-a", "upload");
		// B：被组织素材挂载 → 共享引用保留。
		String mediaB = seedMedia(account, "media/obj-b", "generated");
		db.sql("INSERT INTO content_asset(id, media_reference_id, library_type, category, owner_account_id,"
				+ " organization_id, title) VALUES (gen_random_uuid(), CAST(:m AS uuid), 'merchant', 'product',"
				+ " :a, 'org-1', '商家素材')").bind("m", mediaB).bind("a", account).then().block();
		// C：KYB 证据租约未释放 → 保留（优先于共享）。
		String mediaC = seedMedia(account, "media/obj-c", "generated");
		db.sql("INSERT INTO media_kyb_retention(media_reference_id, reference_id, organization_id, lease_until)"
				+ " VALUES (CAST(:m AS uuid), gen_random_uuid(), 'org-1', now() + interval '30 days')")
				.bind("m", mediaC).then().block();
		// 上传暂存 + 导出产物 + 公众号连接（token 缓存失效）。
		db.sql("""
				INSERT INTO media_reference(id, owner_account_id, purpose, object_key, upload_key,
				                            mime_type, size_bytes, source, status)
				VALUES (gen_random_uuid(), :a, 'user_upload', 'media/obj-staging', 'upload/obj-staging',
				        'image/png', 10, 'upload', 'active')
				""").bind("a", account).then().block();
		storage.putObject("upload/obj-staging", new byte[]{1}, "image/png");
		db.sql("INSERT INTO creation_export(id, owner_account_id, request_id, draft_id, version, format,"
				+ " payload_hash, manifest_json, state) VALUES (gen_random_uuid(), :a, 'req-exp', gen_random_uuid(),"
				+ " 1, 'bundle-zip', CAST(:h AS char(64)), CAST(:manifest AS jsonb), 'ready')").bind("a", account)
				.bind("h", "e".repeat(64)).bind("manifest", "{\"objectKey\":\"export/zip-1\"}").then().block();
		storage.putObject("export/zip-1", new byte[]{1}, "application/zip");
		db.sql("INSERT INTO creation_wechat_account(id, owner_account_id, display_name, app_id, version)"
				+ " VALUES (gen_random_uuid(), :a, '公众号', 'wx-app', 3)").bind("a", account).then().block();
		// 配额行：A + staging 两个 upload 对象（staging 的 upload_key 对象也释放其行配额——staging 行
		// source=upload）。
		db.sql("INSERT INTO media_owner_quota(owner_account_id, object_count, total_bytes) VALUES (:a, 2, 1034)")
				.bind("a", account).then().block();
		// 他人对象对照。
		seedMedia(other, "media/other-a", "upload");

		lifecycle.prepare(account, request).block();
		Map<String, Object> receipt = erase(account, request.toString());

		// 单次调用收敛：erased=true / completed（对象全部终态）。
		assertThat(receipt.get("erased")).isEqualTo(true);
		assertThat(receipt.get("state")).isEqualTo("completed");
		String manifestId = (String) receipt.get("manifestId");
		// A 物删 + 行删除审计 + 配额 exactly-once（upload 两行：A 与 staging 行）。
		assertThat(storage.objects).doesNotContainKeys("media/obj-a", "upload/obj-staging", "export/zip-1");
		assertThat(storage.objects).containsKey("media/other-a");
		assertThat((Object) db.sql("SELECT status FROM media_reference WHERE object_key = 'media/obj-a'")
				.map((r) -> r.get("status", String.class)).one().block()).isEqualTo("deleted");
		// B/C 保留：对象在、行保持 deleting（租约释放后由 GC 接管）、retention_reason 记录。
		assertThat(storage.objects).containsKeys("media/obj-b", "media/obj-c");
		assertThat((Object) db.sql("SELECT status FROM media_reference WHERE object_key = 'media/obj-b'")
				.map((r) -> r.get("status", String.class)).one().block()).isEqualTo("deleting");
		Long retained = countObjects(manifestId, "retained");
		assertThat(retained).isEqualTo(2L);
		String reasons = db
				.sql("SELECT string_agg(retention_reason, ',') AS reasons"
						+ " FROM personal_data_erasure_object WHERE manifest_id = CAST(:m AS uuid)"
						+ " AND state = 'retained'")
				.bind("m", manifestId).map((r) -> r.get("reasons", String.class)).one().block();
		assertThat(reasons).contains("kyb_evidence_lease").contains("shared_content_asset");
		// token 缓存对象（无 Redis=无缓存）按已失效收口。
		assertThat(countObjects(manifestId, "deleted")).isGreaterThanOrEqualTo(4L);
		// 配额：A(1024)+staging(10) 释放 → 0/0；重复推进不双扣。
		assertThat(quota(account)).isEqualTo("0/0");
		int deletedBeforeRepeat = storage.deletedKeys.size();
		erase(account, request.toString());
		assertThat(quota(account)).isEqualTo("0/0");
		assertThat(storage.deletedKeys.size()).isEqualTo(deletedBeforeRepeat);
		assertThat(lifecycle.find(account).block().state()).isEqualTo("erased");
	}

	/** TC103-10-03：对象存储 5xx → 保持 pending（attempts+1）不完成；恢复后重试收敛 completed。 */
	@Test
	void storageFailureRetriesAndConvergesAfterRecovery() {
		String account = "obj-fail-" + UUID.randomUUID();
		UUID request = UUID.randomUUID();
		seedMedia(account, "media/obj-flaky", "generated");
		storage.failKeys.add("media/obj-flaky");

		lifecycle.prepare(account, request).block();
		Map<String, Object> first = erase(account, request.toString());
		assertThat(first.get("erased")).isEqualTo(false);
		assertThat(first.get("state")).isEqualTo("objects_pending");
		assertThat(((Number) first.get("pendingObjects")).longValue()).isEqualTo(1L);
		String manifestId = (String) first.get("manifestId");
		assertThat(objectState(manifestId, "media/obj-flaky")).isEqualTo("pending");
		assertThat(objectAttempts(manifestId, "media/obj-flaky")).isGreaterThanOrEqualTo(1);

		// 故障恢复 → 同一 manifest 续跑（原 key、不新建）→ 收敛 completed。
		storage.failKeys.clear();
		Map<String, Object> second = erase(account, request.toString());
		assertThat(second.get("erased")).isEqualTo(true);
		assertThat(second.get("state")).isEqualTo("completed");
		assertThat(second.get("manifestId")).isEqualTo(manifestId);
		assertThat(storage.objects).doesNotContainKey("media/obj-flaky");
	}

	/**
	 * TC104-02-03：对象删除连续失败到上限（8）→ 条目 failed 可见、manifest 不误 verified；恢复后 failed
	 * 不自动重试。
	 */
	@Test
	void objectFailureRetriesUpToLimitThenStaysFailed() {
		String account = "obj-limit-" + UUID.randomUUID();
		UUID request = UUID.randomUUID();
		seedMedia(account, "media/obj-limit", "generated");
		storage.failKeys.add("media/obj-limit");

		lifecycle.prepare(account, request).block();
		Map<String, Object> receipt = null;
		for (int i = 0; i < 8; i++) {
			receipt = erase(account, request.toString());
		}
		String manifestId = (String) receipt.get("manifestId");
		assertThat(objectState(manifestId, "media/obj-limit")).isEqualTo("failed");
		assertThat(receipt.get("erased")).isEqualTo(false);
		assertThat(receipt.get("state")).isEqualTo("objects_pending");

		storage.failKeys.clear();
		Map<String, Object> afterRecovery = erase(account, request.toString());
		assertThat(afterRecovery.get("erased")).isEqualTo(false);
		assertThat(objectState(manifestId, "media/obj-limit")).isEqualTo("failed");
		assertThat(lifecycle.find(account).block().state()).isEqualTo("erasing");
	}

	/**
	 * TC104-02-01：组织产物引用 A 上传的个人媒体/导出——新登记不收录（组织产物可读、不物删、配额不动）， 个人独立对象照常物删。
	 */
	@Test
	void orgReferencedPersonalObjectsAreSkippedAndPersonalOnesDeleted() {
		String account = "t10421-" + UUID.randomUUID();
		UUID request = UUID.randomUUID();
		String org = "org-t10421";
		String orgDraft = UUID.randomUUID().toString();
		db.sql("INSERT INTO creation_draft(id, owner_account_id, organization_id, title, source_type)"
				+ " VALUES (CAST(:d AS uuid), :a, :org, '组织草稿', 'store')").bind("d", orgDraft).bind("a", account)
				.bind("org", org).then().block();
		String orgPlan = UUID.randomUUID().toString();
		db.sql("INSERT INTO creation_visual_plan(id, owner_account_id, draft_id, request_id, request_hash,"
				+ " source_document_id, source_content_hash, base_draft_version, base_content_hash, recipe_id,"
				+ " recipe_version, upstream_commit, status) VALUES (CAST(:p AS uuid), :a, CAST(:d AS uuid), 'rq1',"
				+ " CAST(:h AS char(64)), gen_random_uuid(), CAST(:h AS char(64)), 1, CAST(:h AS char(64)), 'card',"
				+ " 'v1', 'c1', 'ready')").bind("p", orgPlan).bind("a", account).bind("d", orgDraft)
				.bind("h", "h".repeat(64)).then().block();
		// 个人媒体被组织 artifact 引用（原图+交付图双引用）。
		String mediaArtifact = seedMedia(account, "media/t2-artifact", "upload");
		db.sql("INSERT INTO creation_visual_artifact(id, owner_account_id, draft_id, plan_id, plan_revision,"
				+ " item_id, attempt_id, original_media_id, delivery_media_id, target_aspect, width, height,"
				+ " content_hash) VALUES (gen_random_uuid(), :a, CAST(:d AS uuid), CAST(:p AS uuid), 1, 'i1',"
				+ " gen_random_uuid(), CAST(:m AS uuid), CAST(:m AS uuid), '1:1', 10, 10, CAST(:h AS char(64)))")
				.bind("a", account).bind("d", orgDraft).bind("p", orgPlan).bind("m", mediaArtifact)
				.bind("h", "h".repeat(64)).then().block();
		// 个人媒体被组织分镜 own-media 来源引用。
		String mediaShot = seedMedia(account, "media/t2-shot", "upload");
		String orgStoryboard = UUID.randomUUID().toString();
		db.sql("INSERT INTO video_storyboard(id, account_id, organization_id, target_duration_seconds,"
				+ " request_payload) VALUES (CAST(:s AS uuid), :a, :org, 15, '{}'::jsonb)").bind("s", orgStoryboard)
				.bind("a", account).bind("org", org).then().block();
		String orgShot = UUID.randomUUID().toString();
		db.sql("INSERT INTO video_shot(id, storyboard_id, seq, visual, narration, planned_seconds, camera_move,"
				+ " prompt) VALUES (CAST(:sh AS uuid), CAST(:s AS uuid), 1, 'v', 'n', 5, 'static', 'p')")
				.bind("sh", orgShot).bind("s", orgStoryboard).then().block();
		db.sql("INSERT INTO video_shot_media_source(shot_id, storyboard_id, source_kind, media_id, audio_mode)"
				+ " VALUES (CAST(:sh AS uuid), CAST(:s AS uuid), 'own-media', CAST(:m AS uuid), 'source')")
				.bind("sh", orgShot).bind("s", orgStoryboard).bind("m", mediaShot).then().block();
		// 个人媒体被保留 sync 的映射引用；组织导出 key。
		String mediaMapping = seedMedia(account, "media/t2-mapping", "upload");
		String orgSync = UUID.randomUUID().toString();
		db.sql("INSERT INTO creation_wechat_draft_sync(id, owner_account_id, request_id, account_id, account_version,"
				+ " draft_id, draft_version, export_id, payload_hash, payload_json, state, dispatch_state)"
				+ " VALUES (CAST(:s AS uuid), :a, 'rq2', gen_random_uuid(), 1, CAST(:d AS uuid), 1,"
				+ " gen_random_uuid(), CAST(:h AS char(64)), '{}'::jsonb, 'succeeded', 'completed')").bind("s", orgSync)
				.bind("a", account).bind("d", orgDraft).bind("h", "h".repeat(64)).then().block();
		db.sql("INSERT INTO creation_wechat_media_mapping(id, sync_id, owner_account_id, account_id, account_version,"
				+ " media_ref_id, purpose, content_hash, derived_object_key, state) VALUES (gen_random_uuid(),"
				+ " CAST(:s AS uuid), :a, gen_random_uuid(), 1, CAST(:m AS uuid), 'content',"
				+ " CAST(:h AS char(64)), 'wechat/t2-derived', 'succeeded')").bind("s", orgSync).bind("a", account)
				.bind("m", mediaMapping).bind("h", "h".repeat(64)).then().block();
		db.sql("INSERT INTO creation_export(id, owner_account_id, request_id, draft_id, version, format, payload_hash,"
				+ " manifest_json, state) VALUES (gen_random_uuid(), :a, 'rq3', CAST(:d AS uuid), 1, 'bundle-zip',"
				+ " CAST(:h AS char(64)), CAST('{\"objectKey\":\"export/t2-org\"}' AS jsonb), 'ready')")
				.bind("a", account).bind("d", orgDraft).bind("h", "h".repeat(64)).then().block();
		storage.putObject("wechat/t2-derived", new byte[]{1}, "image/png");
		storage.putObject("export/t2-org", new byte[]{1}, "application/zip");
		// 个人独立对照对象。
		seedMedia(account, "media/t2-personal", "upload");
		seedQuota(account, 4, 4096);

		lifecycle.prepare(account, request).block();
		Map<String, Object> receipt = erase(account, request.toString());
		assertThat(receipt.get("erased")).as("receipt=%s", receipt).isEqualTo(true);
		assertThat(receipt.get("state")).isEqualTo("completed");
		String manifestId = (String) receipt.get("manifestId");

		// 组织引用对象保留（字节在、行 active、未物删）；个人对象消失。
		assertThat(storage.objects).containsKeys("media/t2-artifact", "media/t2-shot", "media/t2-mapping",
				"wechat/t2-derived", "export/t2-org");
		assertThat(storage.objects).doesNotContainKey("media/t2-personal");
		assertThat((Object) db.sql("SELECT status FROM media_reference WHERE object_key = 'media/t2-artifact'")
				.map((r) -> r.get("status", String.class)).one().block()).isEqualTo("active");
		assertThat((Object) db.sql("SELECT status FROM media_reference WHERE object_key = 'media/t2-personal'")
				.map((r) -> r.get("status", String.class)).one().block()).isEqualTo("deleted");
		// 新登记不收录组织引用对象：manifest 无其条目（配额只释放个人那份）。
		assertThat(objectEntry(manifestId, "media/t2-artifact")).isNull();
		assertThat(objectEntry(manifestId, "wechat/t2-derived")).isNull();
		assertThat(objectEntry(manifestId, "export/t2-org")).isNull();
		assertThat(quota(account)).isEqualTo("3/3072");
	}

	/**
	 * TC104-02-02/04/05：旧（修复前）登记条目重验——active 组织引用 retained
	 * 可读；旧破坏状态（deleting+已释放）failed 保字节； 父证据缺失不猜；坏 key/kind failed；保留 reason 优先级稳定。
	 */
	@Test
	void legacyManifestEntriesReverifyBeforePhysicalDelete() {
		String account = "t10422-" + UUID.randomUUID();
		UUID request = UUID.randomUUID();
		String org = "org-t10422";
		String orgDraft = UUID.randomUUID().toString();
		db.sql("INSERT INTO creation_draft(id, owner_account_id, organization_id, title, source_type)"
				+ " VALUES (CAST(:d AS uuid), :a, :org, '组织草稿', 'store')").bind("d", orgDraft).bind("a", account)
				.bind("org", org).then().block();
		// active 个人媒体被组织 artifact + 共享素材同时引用（shared 优先级 2 < organization 3）。
		String mediaActive = seedMedia(account, "media/t22-active", "upload");
		db.sql("INSERT INTO creation_visual_artifact(id, owner_account_id, draft_id, plan_id, plan_revision,"
				+ " item_id, attempt_id, original_media_id, delivery_media_id, target_aspect, width, height,"
				+ " content_hash) VALUES (gen_random_uuid(), :a, CAST(:d AS uuid), gen_random_uuid(), 1, 'i1',"
				+ " gen_random_uuid(), CAST(:m AS uuid), CAST(:m AS uuid), '1:1', 10, 10, CAST(:h AS char(64)))")
				.bind("a", account).bind("d", orgDraft).bind("m", mediaActive).bind("h", "h".repeat(64)).then().block();
		db.sql("INSERT INTO content_asset(id, media_reference_id, library_type, category, owner_account_id,"
				+ " organization_id, title) VALUES (gen_random_uuid(), CAST(:m AS uuid), 'merchant', 'product', :a,"
				+ " :org, '素材')").bind("m", mediaActive).bind("a", account).bind("org", org).then().block();
		// 旧部分执行：deleting + 配额已释放、字节仍在。
		String mediaPartial = seedMedia(account, "media/t22-partial", "upload");
		seedQuota(account, 2, 2048);
		// 组织导出 + 保留 sync 派生 key。
		db.sql("INSERT INTO creation_export(id, owner_account_id, request_id, draft_id, version, format, payload_hash,"
				+ " manifest_json, state) VALUES (gen_random_uuid(), :a, 'rq1', CAST(:d AS uuid), 1, 'bundle-zip',"
				+ " CAST(:h AS char(64)), CAST('{\"objectKey\":\"export/t22-org\"}' AS jsonb), 'ready')")
				.bind("a", account).bind("d", orgDraft).bind("h", "h".repeat(64)).then().block();
		storage.putObject("export/t22-org", new byte[]{1}, "application/zip");
		String orgSync = UUID.randomUUID().toString();
		db.sql("INSERT INTO creation_wechat_draft_sync(id, owner_account_id, request_id, account_id, account_version,"
				+ " draft_id, draft_version, export_id, payload_hash, payload_json, state, dispatch_state)"
				+ " VALUES (CAST(:s AS uuid), :a, 'rq2', gen_random_uuid(), 1, CAST(:d AS uuid), 1,"
				+ " gen_random_uuid(), CAST(:h AS char(64)), '{}'::jsonb, 'succeeded', 'completed')").bind("s", orgSync)
				.bind("a", account).bind("d", orgDraft).bind("h", "h".repeat(64)).then().block();
		db.sql("INSERT INTO creation_wechat_media_mapping(id, sync_id, owner_account_id, account_id, account_version,"
				+ " media_ref_id, purpose, content_hash, derived_object_key, state) VALUES (gen_random_uuid(),"
				+ " CAST(:s AS uuid), :a, gen_random_uuid(), 1, gen_random_uuid(), 'content',"
				+ " CAST(:h AS char(64)), 'wechat/t22-derived', 'succeeded')").bind("s", orgSync).bind("a", account)
				.bind("h", "h".repeat(64)).then().block();
		storage.putObject("wechat/t22-derived", new byte[]{1}, "image/png");
		// 父证据缺失的旧条目（无媒体行的 key，字节在）+ 坏 key 条目。
		storage.putObject("media/t22-missing", new byte[]{1}, "image/png");

		lifecycle.prepare(account, request).block();
		var manifest = erasure.plan(account, request).block();
		String manifestId = manifest.id().toString();
		// 模拟旧登记（无 scope_verified 标记）+ 旧部分执行状态（V86 屏障 carve-out 允许 deleting 迁移）。
		db.sql("UPDATE media_reference SET status = 'deleting', quota_released = true"
				+ " WHERE object_key = 'media/t22-partial'").then().block();
		insertLegacyObject(manifestId, "media/t22-active", "media_object");
		insertLegacyObject(manifestId, "media/t22-partial", "media_object");
		insertLegacyObject(manifestId, "export/t22-org", "export_artifact");
		insertLegacyObject(manifestId, "wechat/t22-derived", "wechat_derived");
		insertLegacyObject(manifestId, "media/t22-missing", "media_object");
		insertLegacyObject(manifestId, "", "media_object");

		cleanup.advance(manifest.id()).block();

		// active 组织引用：retained、行保持 active 可读、字节在；reason 优先级 shared(2) 先于
		// organization(3)。
		assertThat(objectEntry(manifestId, "media/t22-active")).isEqualTo("retained:shared_content_asset");
		assertThat((Object) db.sql("SELECT status FROM media_reference WHERE object_key = 'media/t22-active'")
				.map((r) -> r.get("status", String.class)).one().block()).isEqualTo("active");
		// 旧破坏状态：failed+诊断、字节保留、不自动修复。
		assertThat(objectEntry(manifestId, "media/t22-partial")).isEqualTo("failed:prior_partial_cleanup");
		assertThat(storage.objects).containsKey("media/t22-partial");
		// 组织导出/派生：retained、字节在。
		assertThat(objectEntry(manifestId, "export/t22-org")).isEqualTo("retained:organization_project_reference");
		assertThat(objectEntry(manifestId, "wechat/t22-derived")).isEqualTo("retained:organization_project_reference");
		assertThat(storage.objects).containsKeys("export/t22-org", "wechat/t22-derived");
		// 父证据缺失的旧登记：不猜可删 → failed 保字节；坏 key → failed。
		assertThat(objectEntry(manifestId, "media/t22-missing")).isEqualTo("failed:unverifiable_provenance");
		assertThat(storage.objects).containsKey("media/t22-missing");
		assertThat(countObjects(manifestId, "failed")).isEqualTo(3L);
		// 未完成 manifest（failed>0，DB 阶段收敛后仍 objects_pending，非 completed）。
		var receipt = erasure.process(manifest.id()).block();
		assertThat(receipt.erased()).isFalse();
		assertThat(receipt.state()).isEqualTo("objects_pending");
	}

	/** TC104-02-06：101 个混合对象一批 ≤100、单次调用收敛；配额只释放一次。 */
	@Test
	void hundredOneObjectsConvergeWithinBoundedBatches() {
		String account = "t10426-" + UUID.randomUUID();
		UUID request = UUID.randomUUID();
		for (int i = 0; i < 101; i++) {
			seedMedia(account, "media/t26-" + i, "upload");
		}
		seedQuota(account, 101, 101 * 1024);

		lifecycle.prepare(account, request).block();
		Map<String, Object> receipt = erase(account, request.toString());
		assertThat(receipt.get("erased")).as("receipt=%s", receipt).isEqualTo(true);
		assertThat(receipt.get("state")).isEqualTo("completed");
		String manifestId = (String) receipt.get("manifestId");
		assertThat(countObjects(manifestId, "deleted")).isEqualTo(101L);
		assertThat(storage.objects).doesNotContainKeys("media/t26-0", "media/t26-50", "media/t26-100");
		assertThat(quota(account)).isEqualTo("0/0");
		// 重复推进幂等：不重复删除/释放。
		int before = storage.deletedKeys.size();
		erase(account, request.toString());
		assertThat(storage.deletedKeys.size()).isEqualTo(before);
		assertThat(quota(account)).isEqualTo("0/0");
	}

	private void seedQuota(String owner, long count, long bytes) {
		db.sql("INSERT INTO media_owner_quota(owner_account_id, object_count, total_bytes) VALUES (:a, :c, :b)")
				.bind("a", owner).bind("c", count).bind("b", bytes).then().block();
	}

	/** 旧（修复前）登记条目：无 scope_verified 标记（新登记已覆盖的 key 不覆盖标记）。 */
	private void insertLegacyObject(String manifestId, String key, String kind) {
		db.sql("INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind)"
				+ " VALUES (CAST(:m AS uuid), md5(:k), :k, :kind)"
				+ " ON CONFLICT (manifest_id, object_key_hash) DO NOTHING").bind("m", manifestId).bind("k", key)
				.bind("kind", kind).then().block();
	}

	private String objectEntry(String manifestId, String key) {
		return db
				.sql("SELECT state || ':' || COALESCE(retention_reason, '') FROM personal_data_erasure_object"
						+ " WHERE manifest_id = CAST(:m AS uuid) AND object_key = :k")
				.bind("m", manifestId).bind("k", key).map((r) -> r.get(0, String.class)).one().block();
	}

	private Long countObjects(String manifestId, String state) {
		return db
				.sql("SELECT count(*)::bigint AS c FROM personal_data_erasure_object"
						+ " WHERE manifest_id = CAST(:m AS uuid) AND state = :s")
				.bind("m", manifestId).bind("s", state).map((r) -> r.get("c", Long.class)).one().block();
	}

	private String objectState(String manifestId, String key) {
		return db
				.sql("SELECT state FROM personal_data_erasure_object WHERE manifest_id = CAST(:m AS uuid)"
						+ " AND object_key = :k")
				.bind("m", manifestId).bind("k", key).map((r) -> r.get("state", String.class)).one().block();
	}

	private Integer objectAttempts(String manifestId, String key) {
		return db
				.sql("SELECT attempts FROM personal_data_erasure_object WHERE manifest_id = CAST(:m AS uuid)"
						+ " AND object_key = :k")
				.bind("m", manifestId).bind("k", key).map((r) -> r.get("attempts", Integer.class)).one().block();
	}

	private String quota(String owner) {
		return db.sql("SELECT object_count || '/' || total_bytes FROM media_owner_quota WHERE owner_account_id = :a")
				.bind("a", owner).map((r) -> r.get(0, String.class)).one().block();
	}
}
