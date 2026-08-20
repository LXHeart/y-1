package com.grassland.messaging.outbox;

/**
 * outbox 表的物理方言。五服务共库同 schema，表名必须互不相同； 列契约（event_id 唯一、published_at/claim
 * 租约、attempt_count 退避）由各服务 Flyway 保证。
 *
 * <p>
 * {@code trust_outbox} 是唯一历史分叉：bigserial 主键 + jsonb payload （其余四表为 uuid +
 * json）——不改线上表结构，以方言参数吸收差异。
 */
public record OutboxSchema(String table, boolean bigintId, boolean jsonbPayload) {

	public OutboxSchema {
		if (table == null || table.isBlank()) {
			throw new IllegalArgumentException("outbox table must not be blank");
		}
	}

	/** uuid 主键 + json payload 的标准形态（identity/finance/intelligence/marketplace）。 */
	public static OutboxSchema standard(String table) {
		return new OutboxSchema(table, false, false);
	}

	/** bigserial 主键 + jsonb payload 的历史形态（仅 trust_outbox）。 */
	public static OutboxSchema bigintJsonb(String table) {
		return new OutboxSchema(table, true, true);
	}

	String idCast() {
		return bigintId ? "bigint" : "uuid";
	}

	String payloadCast() {
		return jsonbPayload ? "jsonb" : "json";
	}
}
