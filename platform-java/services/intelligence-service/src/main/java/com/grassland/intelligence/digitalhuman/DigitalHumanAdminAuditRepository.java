package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AdminAuditRow;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 数字人治理处置审计（任务书 #105G C105G-03 / K10、DH-R15）：dh_admin_audit 不可变追加——
 * 处置意图先于副作用落审计；同一 (actor, action, requestId) 幂等（UNIQUE 约束，重复 append 返回既有行，
 * 不重复计数）。reason 只存处置理由（1～200 字），metadata 白名单键脱敏后入库——<b>不抄用户聊天正文</b>。
 */
@Component
public class DigitalHumanAdminAuditRepository {

	/** metadata 允许键（K10 治理面证据字段；其余键一律丢弃，防正文/密钥借道审计入库）。 */
	private static final java.util.Set<String> METADATA_KEYS = java.util.Set.of("expectedVersion", "newVersion",
			"outcome", "providerEvidenceRef", "sessionState", "invocationState", "fromVersion", "detail");

	private final DatabaseClient db;

	public DigitalHumanAdminAuditRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * 追加审计（幂等）：UNIQUE(actor, action, request_id) 冲突 → 读回既有行（同意图重放不新增证据）。 metadata
	 * 值仅接受标量（字符串/数字/布尔），对象/数组丢弃。
	 */
	public Mono<AdminAuditRow> append(String actorAccountId, String action, UUID resourceId, UUID requestId,
			String reason, Map<String, ?> metadata) {
		String sanitized = sanitize(metadata);
		var spec = db.sql("""
				INSERT INTO dh_admin_audit(id, actor_account_id, action, resource_id, request_id, reason, metadata_json)
				VALUES (CAST(:id AS uuid), :actor, :action, CAST(:rid AS uuid), CAST(:req AS uuid), :reason,
				 CAST(:meta AS jsonb))
				ON CONFLICT (actor_account_id, action, request_id) DO NOTHING
				RETURNING id::text, actor_account_id, action, resource_id::text, request_id::text, reason,
				 metadata_json::text, created_at
				""").bind("id", UUID.randomUUID().toString()).bind("actor", actorAccountId).bind("action", action);
		// bind() 拒 null：resource_id 可空（config_update 无资源）走 bindNull。
		spec = resourceId == null ? spec.bindNull("rid", String.class) : spec.bind("rid", resourceId.toString());
		return spec.bind("req", requestId.toString()).bind("reason", reason).bind("meta", sanitized)
				.map(DigitalHumanAdminAuditRepository::mapRow).one()
				.switchIfEmpty(Mono.defer(() -> findByKey(actorAccountId, action, requestId)));
	}

	public Mono<AdminAuditRow> findByKey(String actorAccountId, String action, UUID requestId) {
		return db
				.sql("SELECT id::text, actor_account_id, action, resource_id::text, request_id::text, reason,"
						+ " metadata_json::text, created_at FROM dh_admin_audit WHERE actor_account_id = :actor"
						+ " AND action = :action AND request_id = CAST(:req AS uuid)")
				.bind("actor", actorAccountId).bind("action", action).bind("req", requestId.toString())
				.map(DigitalHumanAdminAuditRepository::mapRow).one();
	}

	private static AdminAuditRow mapRow(io.r2dbc.spi.Readable row) {
		return new AdminAuditRow(row.get("id", String.class), row.get("actor_account_id", String.class),
				row.get("action", String.class), row.get("resource_id", String.class),
				row.get("request_id", String.class), row.get("reason", String.class),
				row.get("metadata_json", String.class), row.get("created_at", OffsetDateTime.class).toInstant());
	}

	private static String sanitize(Map<String, ?> metadata) {
		Map<String, Object> allowed = new LinkedHashMap<>();
		if (metadata != null) {
			for (var entry : metadata.entrySet()) {
				if (METADATA_KEYS.contains(entry.getKey()) && entry.getValue() != null
						&& (entry.getValue() instanceof String || entry.getValue() instanceof Number
								|| entry.getValue() instanceof Boolean)) {
					allowed.put(entry.getKey(), entry.getValue());
				}
			}
		}
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(allowed);
		} catch (Exception failure) { // 不可能（标量 map），防御性空对象
			return "{}";
		}
	}
}
