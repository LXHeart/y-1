package com.grassland.intelligence.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 清理后媒体对 GC 仍可见（任务书 #103 C103-09 起，取代旧 erasePii 直删测试）： 分阶段清理只把个人媒体标记 deleting
 * 并登记对象，行保留给对象删除/GC（C103-10），不提前物删。
 */
class IntelligenceComplianceRepositoryIT extends IntelligenceItSupport {

	@DynamicPropertySource
	static void erasureProps(DynamicPropertyRegistry registry) {
		// 测试直驱 service；调度 worker 并发会抢批次（见 PersonalDataErasureIT 同款说明）。
		registry.add("intelligence.erasure.enabled", () -> "false");
	}

	@Autowired
	private PersonalDataErasureService erasure;

	@Autowired
	private IntelligenceAccountLifecycleRepository lifecycle;

	@Test
	void personalMediaRemainsVisibleToGarbageCollectorAfterErasureIsQueued() {
		String accountId = UUID.randomUUID().toString();
		String mediaId = UUID.randomUUID().toString();
		UUID request = UUID.randomUUID();
		db.sql("""
				INSERT INTO media_reference(id, owner_account_id, purpose, object_key,
				                            mime_type, size_bytes, source, status)
				VALUES (CAST(:mediaId AS uuid), :accountId, 'user_upload', :objectKey,
				        'image/png', 128, 'upload', 'active')
				""").bind("mediaId", mediaId).bind("accountId", accountId).bind("objectKey", "compliance/" + mediaId)
				.then().block();

		lifecycle.prepare(accountId, request).block();
		var manifest = erasure.plan(accountId, request).block();
		Boolean more = erasure.drain(manifest.id()).block();
		assertThat(more).isFalse();

		var receipt = erasure.verify(manifest.id()).block();
		assertThat(receipt.state()).isEqualTo("objects_pending");
		assertThat(receipt.counts()).containsEntry("media_mark_deleting", 1L);
		MapRow row = db.sql("SELECT status, deleted_at FROM media_reference WHERE id = CAST(:id AS uuid)")
				.bind("id", mediaId).map((r) -> new MapRow(r.get("status", String.class),
						r.get("deleted_at", java.time.OffsetDateTime.class)))
				.one().block();

		assertThat(row.status()).isEqualTo("deleting");
		assertThat(row.deletedAt()).isNull();
		Long objects = db
				.sql("SELECT count(*)::bigint AS c FROM personal_data_erasure_object"
						+ " WHERE manifest_id = :m AND state = 'pending' AND object_key = :k")
				.bind("m", manifest.id()).bind("k", "compliance/" + mediaId).map((r) -> r.get("c", Long.class)).one()
				.block();
		assertThat(objects).isEqualTo(1L);
	}

	private record MapRow(String status, java.time.OffsetDateTime deletedAt) {
	}
}
