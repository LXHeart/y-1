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
