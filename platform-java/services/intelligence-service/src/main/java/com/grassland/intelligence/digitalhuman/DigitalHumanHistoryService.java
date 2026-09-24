package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationDto;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Page;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionBilling;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionSummary;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 个人历史检索与异步删除（任务书 #105G C105G-01 / K03 API09、API38；K04、K13.4）。
 *
 * <p>
 * 列表：owner 固定本人 + {@code deleted_at IS NULL}，{@code (created_at,id)} 倒序
 * keyset（删行不重不漏、 不 COUNT 全表）；时间窗 {@code [from,to)} UTC 半开、跨度 ≤90 天；游标绑定 owner
 * 指纹（他人游标 → 422， 不返回本人页）。billing 按 dh_invocation×ai_run
 * 聚合：confirmed=用户计费项实耗、subsidized=平台补贴项
 * 实耗、platformCost=平台侧实耗合计、pendingCount=未结/unknown 笔数（K01：未知不填 0、不以 0 冒充）。
 *
 * <p>
 * 删除：事务内置墓碑（{@code deleted_at} + {@code content_deleted} +
 * {@code contentEpoch+1}；活动场单向推进 ending）并同步清 transcript/event
 * 与临时录制行，提交后清易失缓冲、runtime 收尾 ending→ended，operation 如实
 * pending→running→succeeded；终止失败停在 running（重试同键续跑），不提前宣称物理删除完成。已保存 asset/账务
 * 独立保留（K09：删除 session 不删已保存视频、附属字幕与账务）。
 */
@Component
public class DigitalHumanHistoryService {

	static final Duration MAX_WINDOW = Duration.ofDays(90);
	static final int PAGE_DEFAULT = 20;
	static final int PAGE_MAX = 100;
	static final int CURSOR_MAX_CHARS = 512;

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final DigitalHumanOperations operations;
	private final DigitalHumanContentBuffer buffer;
	private final DigitalHumanRuntimeClient runtime;

	public DigitalHumanHistoryService(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanOperations operations, DigitalHumanContentBuffer buffer, DigitalHumanRuntimeClient runtime) {
		this.db = db;
		this.transactions = transactions;
		this.operations = operations;
		this.buffer = buffer;
		this.runtime = runtime;
	}

	/** K03 API09 过滤器：只含 profileId/state/from/to（组织/正文不入列表条件）。 */
	public record HistoryFilter(UUID profileId, SessionState state, Instant from, Instant to) {

		public HistoryFilter {
			if (from != null && to != null) {
				if (!from.isBefore(to)) {
					throw new IntelligenceException(422, "dh_invalid_input", "时间窗起点必须早于终点。");
				}
				if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
					throw new IntelligenceException(422, "dh_invalid_input", "时间窗跨度不能超过 90 天。");
				}
			}
		}
	}

	record CursorPoint(Instant createdAt, UUID id) {
	}

	// ---------- API09：keyset 分页 ----------

	public Mono<Page<SessionSummary>> list(PersonalActor actor, HistoryFilter filter, String cursor, Integer limit) {
		int pageSize = limit == null ? PAGE_DEFAULT : limit;
		if (pageSize < 1 || pageSize > PAGE_MAX) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "limit 必须在 1～" + PAGE_MAX + " 之间。"));
		}
		CursorPoint point = cursor == null || cursor.isBlank() ? null : decodeCursor(actor, cursor);
		StringBuilder sql = new StringBuilder("""
				SELECT s.id::text, s.profile_id::text, s.profile_name_at_creation, s.state, s.created_at, s.ended_at,
				       EXISTS(SELECT 1 FROM dh_transcript t WHERE t.session_id = s.id) AS has_saved_transcript,
				       (SELECT count(*) FROM dh_recording r WHERE r.session_id = s.id) AS recording_count,
				       (SELECT count(*) FROM dh_recording r WHERE r.session_id = s.id AND r.asset_id IS NOT NULL)
				           AS saved_asset_count,
				       s.config_snapshot->>'priceTableVersion' AS price_table_version, b.*
				FROM dh_session s
				LEFT JOIN LATERAL (
				    SELECT COALESCE(SUM(CASE WHEN i.budget_snapshot->>'feature' IN ('AI_RUN_TEXT','AI_RUN_VOICE')
				               THEN COALESCE(r.actual_cents, 0) ELSE 0 END), 0)::bigint AS confirmed_cents,
				           COALESCE(SUM(CASE WHEN i.provider_snapshot->>'type' = 'PLATFORM'
				               THEN COALESCE(r.actual_cents, 0) ELSE 0 END), 0)::bigint AS platform_cost_cents,
				           COALESCE(SUM(CASE WHEN i.provider_snapshot->>'type' = 'PLATFORM'
				               AND i.budget_snapshot->>'feature' IS NULL
				               THEN COALESCE(r.actual_cents, 0) ELSE 0 END), 0)::bigint AS subsidized_cents,
				           COUNT(*) FILTER (WHERE i.settlement_state = 'pending' OR i.state = 'unknown')::int
				               AS pending_count
				    FROM dh_invocation i
				    LEFT JOIN ai_run r ON r.id = i.ai_run_id
				    WHERE i.session_id = s.id
				) b ON true
				WHERE s.owner_account_id = :owner AND s.deleted_at IS NULL""");
		if (filter.profileId() != null) {
			sql.append(" AND s.profile_id = CAST(:profileId AS uuid)");
		}
		if (filter.state() != null) {
			sql.append(" AND s.state = :state");
		}
		if (filter.from() != null) {
			sql.append(" AND s.created_at >= :fromAt");
		}
		if (filter.to() != null) {
			sql.append(" AND s.created_at < :toAt");
		}
		if (point != null) {
			sql.append(" AND (s.created_at, s.id) < (CAST(:cursorAt AS timestamptz), CAST(:cursorId AS uuid))");
		}
		sql.append(" ORDER BY s.created_at DESC, s.id DESC LIMIT ").append(pageSize + 1);
		var spec = db.sql(sql.toString()).bind("owner", actor.accountId());
		if (filter.profileId() != null) {
			spec = spec.bind("profileId", filter.profileId().toString());
		}
		if (filter.state() != null) {
			spec = spec.bind("state", filter.state().name());
		}
		if (filter.from() != null) {
			spec = spec.bind("fromAt", OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
		}
		if (filter.to() != null) {
			spec = spec.bind("toAt", OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
		}
		if (point != null) {
			spec = spec.bind("cursorAt", OffsetDateTime.ofInstant(point.createdAt(), ZoneOffset.UTC)).bind("cursorId",
					point.id().toString());
		}
		return spec.map(DigitalHumanHistoryService::mapSummary).all().collectList()
				.map(rows -> rows.size() <= pageSize
						? new Page<>(rows, null)
						: new Page<>(rows.subList(0, pageSize), encodeCursor(actor, rows.get(pageSize - 1))));
	}

	// ---------- API38：异步删除（先墓碑，后收尾） ----------

	public Mono<OperationDto> delete(PersonalActor actor, UUID sessionId, UUID requestId) {
		Mono<OperationDto> tombstone = operations
				.reserve(actor, OperationKind.session_delete, requestId,
						DigitalHumanOperations.canonicalHash(Map.of("sessionId", sessionId.toString())), sessionId)
				.flatMap(operation -> operation.state() == OperationState.succeeded
						? Mono.just(toDto(operation))
						: loadForDelete(actor, sessionId).flatMap(session -> applyTombstone(operation, session)));
		// reserve 阶段 resource_id 尚未回填（业务行落库后才写）：sessionId 显式传递，不从 DTO 读。
		return transactions.transactional(tombstone).flatMap(dto -> finalizeAfterCommit(dto, sessionId));
	}

	private record DeletableSession(UUID id, String state, boolean deleted, long contentEpoch) {
	}

	private Mono<DeletableSession> loadForDelete(PersonalActor actor, UUID sessionId) {
		return db
				.sql("SELECT state, deleted_at, content_epoch FROM dh_session"
						+ " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner")
				.bind("id", sessionId.toString()).bind("owner", actor.accountId())
				.map((row, meta) -> new DeletableSession(sessionId, row.get("state", String.class),
						row.get("deleted_at", OffsetDateTime.class) != null, row.get("content_epoch", Long.class)))
				.one().switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private Mono<OperationDto> applyTombstone(OperationRow operation, DeletableSession session) {
		if (session.deleted()) {
			// 前一请求已置墓碑（并发/重放）：不重复写墓碑；统一走提交后收尾（终止未完成不得提前 succeeded）。
			return db
					.sql("UPDATE dh_operation SET state = 'running', version = version + 1, updated_at = now()"
							+ " WHERE id = CAST(:id AS uuid) AND state = 'pending'")
					.bind("id", operation.id()).then().thenReturn(toDto(operation));
		}
		Mono<OperationDto> body = db.sql("""
				UPDATE dh_session SET deleted_at = now(), content_deleted = true,
				       content_epoch = content_epoch + 1,
				       state = CASE WHEN state IN ('ended','failed') THEN state ELSE 'ending' END,
				       state_entered_at = CASE WHEN state IN ('ended','failed')
				           THEN state_entered_at ELSE now() END,
				       version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner AND deleted_at IS NULL
				""").bind("id", session.id().toString()).bind("owner", operation.ownerAccountId()).then()
				.then(db.sql("DELETE FROM dh_transcript WHERE session_id = CAST(:id AS uuid)")
						.bind("id", session.id().toString()).then())
				.then(db.sql("DELETE FROM dh_event WHERE session_id = CAST(:id AS uuid)")
						.bind("id", session.id().toString()).then())
				// 临时录制段（未挂 asset）随会话删除收口；saving/saved 段的 asset 生命周期独立（K09）。
				.then(db.sql("""
						UPDATE dh_recording SET state = 'deleted', version = version + 1, updated_at = now()
						WHERE session_id = CAST(:id AS uuid) AND asset_id IS NULL
						  AND state IN ('recording', 'finalizing', 'ready')
						""").bind("id", session.id().toString()).then())
				.then(db.sql("UPDATE dh_operation SET state = 'running', version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid)").bind("id", operation.id()).then())
				.thenReturn(toDto(operation));
		return body;
	}

	/**
	 * 提交后收尾：清易失缓冲（新旧 epoch 与初始段，幂等）→ runtime 关闭（仅活动场；失败停在 running， 同键重试续跑） →
	 * ending→ended → operation succeeded。已保存 asset/账务不动。
	 */
	private Mono<OperationDto> finalizeAfterCommit(OperationDto pending, UUID sessionId) {
		if (pending.state() != OperationState.pending && pending.state() != OperationState.running) {
			return Mono.just(pending);
		}
		Mono<OperationDto> erase = buffer.erase(sessionId.toString(), 0L).then(buffer.erase(sessionId.toString(), 1L))
				.then(buffer.erase(sessionId.toString(), 2L)).thenReturn(pending);
		return erase.flatMap(dto -> db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId.toString()).map(row -> row.get("state", String.class)).one().flatMap(state -> {
					if (!"ending".equals(state)) {
						// 已终态（或从未活动）：无需 runtime 收尾，直接完成。
						return completeAndRead(pending.id(), sessionId);
					}
					return runtime.end(sessionId.toString(), "user_delete")
							.onErrorResume(ignored -> Mono.error(
									new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，删除将在重试后继续。")))
							.then(db.sql("UPDATE dh_session SET state = 'ended', ended_at = COALESCE(ended_at, now()),"
									+ " state_entered_at = now(), version = version + 1, updated_at = now()"
									+ " WHERE id = CAST(:id AS uuid) AND state = 'ending'")
									.bind("id", sessionId.toString()).then())
							.then(completeAndRead(pending.id(), sessionId));
				}).onErrorResume(IntelligenceException.class,
						failure -> "dh_runtime_unavailable".equals(failure.code())
								? readOperation(pending.id())
								: Mono.error(failure)));
	}

	private Mono<OperationDto> completeAndRead(String operationId, UUID resourceId) {
		return complete(operationId, resourceId).then(readOperation(operationId));
	}

	private Mono<Void> complete(String operationId, UUID resourceId) {
		return db
				.sql("UPDATE dh_operation SET state = 'succeeded', result_ref = CAST(:r AS uuid),"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", operationId).bind("r", resourceId.toString()).then();
	}

	private Mono<OperationDto> readOperation(String operationId) {
		return db.sql("""
				SELECT id::text, owner_account_id, kind, request_id::text, payload_hash, resource_id::text,
				       state, result_ref::text, error_code, retry_at, lease_owner, lease_until, version,
				       created_at, updated_at
				FROM dh_operation WHERE id = CAST(:id AS uuid)
				""").bind("id", operationId).map(DigitalHumanHistoryService::mapOperation).one()
				.map(DigitalHumanHistoryService::toDto);
	}

	// ---------- 游标（owner 指纹绑定；他人/伪造 → 422） ----------

	static String encodeCursor(PersonalActor actor, SessionSummary summary) {
		String raw = "v1:" + ownerTag(actor.accountId()) + ":" + summary.createdAt().toEpochMilli() + ":"
				+ summary.id();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
	}

	static CursorPoint decodeCursor(PersonalActor actor, String cursor) {
		if (cursor.length() > CURSOR_MAX_CHARS) {
			throw invalidCursor();
		}
		String raw;
		try {
			raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
		} catch (IllegalArgumentException bad) {
			throw invalidCursor();
		}
		String[] parts = raw.split(":", -1);
		if (parts.length != 4 || !"v1".equals(parts[0]) || !ownerTag(actor.accountId()).equals(parts[1])) {
			throw invalidCursor();
		}
		try {
			Instant createdAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
			UUID id = UUID.fromString(parts[3]);
			return new CursorPoint(createdAt, id);
		} catch (RuntimeException bad) {
			throw invalidCursor();
		}
	}

	private static IntelligenceException invalidCursor() {
		return new IntelligenceException(422, "dh_invalid_input", "分页游标无效。");
	}

	private static String ownerTag(String accountId) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String hex = HexFormat.of().formatHex(digest.digest(accountId.getBytes(StandardCharsets.UTF_8)));
			return hex.substring(0, 16);
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256 不可用", failure);
		}
	}

	// ---------- 行映射 ----------

	private static SessionSummary mapSummary(io.r2dbc.spi.Readable r) {
		String priceTableVersion = r.get("price_table_version", String.class);
		return new SessionSummary(r.get("id", String.class), r.get("profile_id", String.class),
				r.get("profile_name_at_creation", String.class), SessionState.valueOf(r.get("state", String.class)),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("ended_at", OffsetDateTime.class)),
				Boolean.TRUE.equals(r.get("has_saved_transcript", Boolean.class)),
				r.get("recording_count", Long.class).intValue(), r.get("saved_asset_count", Long.class).intValue(),
				new SessionBilling(r.get("confirmed_cents", Long.class), r.get("platform_cost_cents", Long.class),
						r.get("subsidized_cents", Long.class), r.get("pending_count", Long.class).intValue(),
						priceTableVersion == null ? "" : priceTableVersion));
	}

	private static OperationRow mapOperation(io.r2dbc.spi.Readable r) {
		return new OperationRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				OperationKind.valueOf(r.get("kind", String.class)), r.get("request_id", String.class),
				r.get("payload_hash", String.class), r.get("resource_id", String.class),
				OperationState.valueOf(r.get("state", String.class)), r.get("result_ref", String.class),
				r.get("error_code", String.class), toInstant(r.get("retry_at", OffsetDateTime.class)),
				r.get("lease_owner", String.class), toInstant(r.get("lease_until", OffsetDateTime.class)),
				r.get("version", Integer.class), toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	static OperationDto toDto(OperationRow row) {
		return new OperationDto(row.id(), row.kind().name(), row.state(), row.resourceId(), row.resultRef(),
				row.errorCode(), row.createdAt(), row.updatedAt());
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}
}
