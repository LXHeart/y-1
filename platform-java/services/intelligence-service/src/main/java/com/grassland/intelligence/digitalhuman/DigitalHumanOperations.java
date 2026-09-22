package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationRow;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 数字人操作幂等层（任务书 #105B C105B-03 / K04、K13.6）：owner+kind+requestId + payloadHash。
 *
 * <p>
 * 持久键 {@code (owner_account_id, kind, request_id)}；payloadHash 是<b>版本化
 * canonical JSON SHA-256</b>——只哈希 schema 确认后的业务字段（不含 requestId/运输层头），键按 Unicode
 * 码点排序、UTF-8、 无空白、字符串 JSON 最小转义（正常中文不转义）、null 显式。同 hash 重放返回原 receipt；异 hash →
 * 409 {@code dh_request_conflict}。reserve 不执行任何副作用；真实业务行由调用方在同一事务内落库。
 */
@Component
public class DigitalHumanOperations {

	private static final String HASH_VERSION = "v1";

	private final DatabaseClient db;
	private final TransactionalOperator transactions;

	public DigitalHumanOperations(DatabaseClient db, TransactionalOperator transactions) {
		this.db = db;
		this.transactions = transactions;
	}

	/**
	 * Canonical JSON SHA-256（K13.6）。入参为 schema 确认后的业务字段（camelCase 键），值允许
	 * String/Number/Boolean/null；不接受的类型直接拒绝（不猜序列化）。
	 */
	public static String canonicalHash(Map<String, Object> businessFields) {
		Map<String, String> sorted = new TreeMap<>(DigitalHumanOperations::compareCodePoints);
		for (Map.Entry<String, Object> entry : businessFields.entrySet()) {
			sorted.put(entry.getKey(), renderValue(entry.getValue()));
		}
		StringBuilder canonical = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> entry : sorted.entrySet()) {
			if (!first) {
				canonical.append(',');
			}
			first = false;
			canonical.append(renderString(entry.getKey())).append(':').append(entry.getValue());
		}
		canonical.append('}');
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of()
					.formatHex(digest.digest((HASH_VERSION + ":" + canonical).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256 不可用", failure);
		}
	}

	/** Unicode 码点逐位比较（ASCII 键与 compareTo 一致；含增补平面键时仍确定）。 */
	private static int compareCodePoints(String left, String right) {
		int i = 0;
		int j = 0;
		while (i < left.length() && j < right.length()) {
			int a = left.codePointAt(i);
			int b = right.codePointAt(j);
			if (a != b) {
				return Integer.compare(a, b);
			}
			i += Character.charCount(a);
			j += Character.charCount(b);
		}
		return Integer.compare(left.length() - i, right.length() - j);
	}

	private static String renderValue(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Boolean flag) {
			return flag ? "true" : "false";
		}
		if (value instanceof Integer || value instanceof Long) {
			return value.toString();
		}
		if (value instanceof String text) {
			return renderString(text);
		}
		throw new IllegalArgumentException("canonical JSON 不支持的字段类型：" + value.getClass().getName());
	}

	/** JSON 最小转义：仅引号/反斜杠/控制字符；非 ASCII 原样（不转义正常中文）。 */
	private static String renderString(String text) {
		StringBuilder rendered = new StringBuilder("\"");
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> rendered.append("\\\"");
				case '\\' -> rendered.append("\\\\");
				case '\b' -> rendered.append("\\b");
				case '\f' -> rendered.append("\\f");
				case '\n' -> rendered.append("\\n");
				case '\r' -> rendered.append("\\r");
				case '\t' -> rendered.append("\\t");
				default -> {
					if (c < 0x20) {
						rendered.append(String.format("\\u%04x", (int) c));
					} else {
						rendered.append(c);
					}
				}
			}
		}
		return rendered.append('"').toString();
	}

	/**
	 * 预留操作 receipt：同键已存在时读原行（同 hash 幂等返回、异 hash 409）；不存在时插入 pending 行。 不在此执行业务副作用。
	 */
	public Mono<OperationRow> reserve(PersonalActor actor, OperationKind kind, UUID requestId, String payloadHash,
			UUID resourceId) {
		Mono<OperationRow> body = db.sql("""
				INSERT INTO dh_operation(id, owner_account_id, kind, request_id, payload_hash, resource_id, state)
				VALUES (CAST(:id AS uuid), :owner, :kind, CAST(:requestId AS uuid), :payloadHash,
				        CAST(:resourceId AS uuid), 'pending')
				ON CONFLICT (owner_account_id, kind, request_id) DO NOTHING
				RETURNING id::text, owner_account_id, kind, request_id::text, payload_hash, resource_id::text,
				          state, result_ref::text, error_code, retry_at, lease_owner, lease_until, version,
				          created_at, updated_at
				""").bind("id", UUID.randomUUID().toString()).bind("owner", actor.accountId()).bind("kind", kind.name())
				.bind("requestId", requestId.toString()).bind("payloadHash", payloadHash)
				.bindNull("resourceId", String.class).map(DigitalHumanOperations::mapRow).one()
				.switchIfEmpty(Mono.defer(() -> findByKey(actor, kind, requestId).flatMap(existing -> {
					if (existing.payloadHash().equals(payloadHash)) {
						return Mono.just(existing);
					}
					return Mono.error(new IntelligenceException(409, "dh_request_conflict", "同一请求号已受理其它内容，请刷新后重试。"));
				})));
		// resourceId 在业务行落库后由 completeOperation 回填；reserve 阶段可为 null。
		return transactions.transactional(body);
	}

	public Mono<OperationRow> findByKey(PersonalActor actor, OperationKind kind, UUID requestId) {
		return db.sql("""
				SELECT id::text, owner_account_id, kind, request_id::text, payload_hash, resource_id::text,
				       state, result_ref::text, error_code, retry_at, lease_owner, lease_until, version,
				       created_at, updated_at
				FROM dh_operation WHERE owner_account_id = :owner AND kind = :kind
				  AND request_id = CAST(:requestId AS uuid)
				""").bind("owner", actor.accountId()).bind("kind", kind.name()).bind("requestId", requestId.toString())
				.map(DigitalHumanOperations::mapRow).one();
	}

	/** 归属校验的只读 operation（API39）：非本人/不存在统一 404。 */
	public Mono<OperationRow> read(PersonalActor actor, UUID operationId) {
		return db.sql("""
				SELECT id::text, owner_account_id, kind, request_id::text, payload_hash, resource_id::text,
				       state, result_ref::text, error_code, retry_at, lease_owner, lease_until, version,
				       created_at, updated_at
				FROM dh_operation WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner
				""").bind("id", operationId.toString()).bind("owner", actor.accountId())
				.map(DigitalHumanOperations::mapRow).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	/** 严格 JSON 解码（K00：公开 JSON 拒绝未声明字段）；失败 → 422 dh_invalid_input。 */
	public static <T> T parseStrict(String body, Class<T> type) {
		try {
			return STRICT_JSON.readValue(body, type);
		} catch (Exception failure) {
			throw new IntelligenceException(422, "dh_invalid_input", "请求体格式不正确或包含未定义字段。");
		}
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper STRICT_JSON = new com.fasterxml.jackson.databind.ObjectMapper()
			.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

	/** 业务行落库后回填 resource 引用（同事务）。 */
	public Mono<Void> attachResource(UUID operationId, UUID resourceId) {
		return db
				.sql("UPDATE dh_operation SET resource_id = CAST(:r AS uuid), version = version + 1,"
						+ " updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", operationId.toString()).bind("r", resourceId.toString()).then();
	}

	private static OperationRow mapRow(io.r2dbc.spi.Readable r) {
		return new OperationRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				OperationKind.valueOf(r.get("kind", String.class)), r.get("request_id", String.class),
				r.get("payload_hash", String.class), r.get("resource_id", String.class),
				DigitalHumanRecords.OperationState.valueOf(r.get("state", String.class)),
				r.get("result_ref", String.class), r.get("error_code", String.class),
				toInstant(r.get("retry_at", OffsetDateTime.class)), r.get("lease_owner", String.class),
				toInstant(r.get("lease_until", OffsetDateTime.class)), r.get("version", Integer.class),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}
}
