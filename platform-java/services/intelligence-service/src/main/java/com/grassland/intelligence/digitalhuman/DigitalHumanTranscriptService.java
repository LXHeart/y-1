package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Page;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.TranscriptEntry;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 转写保存（任务书 #105C C105C-04 / K03 API22～26、K05）：主动同意与 contentEpoch 墓碑。
 *
 * <p>
 * 未勾选时 dh_transcript 零行；save 只复制缓冲中的<b>final</b> 文本（同 utteranceId 一次，DB UNIQUE
 * 裁定）；关闭偏好不删除已保存。删除墓碑：事务先 contentEpoch+1 + content_deleted=true（迟到 final/重新保存一律
 * 409 {@code dh_content_deleted}），提交后清易失缓冲（幂等重试）。导出 txt 标 角色/UTC 时间/中断状态；结束 10
 * 分钟后保存 410 {@code dh_content_expired}。
 */
@Component
public class DigitalHumanTranscriptService {

	private static final Duration SAVE_WINDOW = Duration.ofMinutes(10);

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final DigitalHumanOperations operations;
	private final DigitalHumanContentBuffer buffer;

	public DigitalHumanTranscriptService(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanOperations operations, DigitalHumanContentBuffer buffer) {
		this.db = db;
		this.transactions = transactions;
		this.operations = operations;
		this.buffer = buffer;
	}

	private record SessionRow(String owner, String state, Instant endedAt, boolean saveTranscript,
			int transcriptVersion, long contentEpoch, boolean contentDeleted) {
	}

	private Mono<SessionRow> load(PersonalActor actor, UUID sessionId) {
		return db
				.sql("SELECT owner_account_id AS owner, state, ended_at, save_transcript, transcript_version,"
						+ " content_epoch, content_deleted FROM dh_session WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId.toString())
				.map((row, meta) -> new SessionRow(row.get("owner", String.class), row.get("state", String.class),
						toInstant(row.get("ended_at", OffsetDateTime.class)),
						Boolean.TRUE.equals(row.get("save_transcript", Boolean.class)),
						row.get("transcript_version", Integer.class), row.get("content_epoch", Long.class),
						Boolean.TRUE.equals(row.get("content_deleted", Boolean.class))))
				.one().switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(row -> row.owner().equals(actor.accountId())
						? Mono.just(row)
						: Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	// API22
	public Mono<Map<String, Object>> setPreference(PersonalActor actor, UUID sessionId, boolean save,
			int expectedVersion, UUID requestId) {
		Mono<Map<String, Object>> body = operations
				.reserve(actor, OperationKind.transcript_preference, requestId,
						DigitalHumanOperations.canonicalHash(
								Map.of("sessionId", sessionId.toString(), "save", save, "version", expectedVersion)),
						sessionId)
				.flatMap(operation -> operation.state() == OperationState.succeeded
						? currentVersion(actor, sessionId)
						: load(actor, sessionId).flatMap(session -> {
							if (session.contentDeleted()) {
								return Mono.error(tombstone());
							}
							return db
									.sql("UPDATE dh_session SET save_transcript = :save,"
											+ " transcript_version = transcript_version + 1, version = version + 1,"
											+ " updated_at = now() WHERE id = CAST(:id AS uuid)"
											+ " AND transcript_version = :expected RETURNING transcript_version")
									.bind("save", save).bind("id", sessionId.toString())
									.bind("expected", expectedVersion)
									.map(row -> row.get("transcript_version", Integer.class)).one()
									.switchIfEmpty(Mono.error(
											new IntelligenceException(409, "dh_version_conflict", "会话状态已变化，请刷新后重试。")))
									.flatMap(version -> complete(operation.id(), sessionId).thenReturn(
											Map.<String, Object>of("saveTranscript", save, "version", version)));
						}));
		return transactions.transactional(body);
	}

	// API23：保存缓冲中的 final（结束 10 分钟内；墓碑 409）。
	public Mono<Map<String, Object>> saveBuffered(PersonalActor actor, UUID sessionId, int expectedVersion,
			UUID requestId) {
		return load(actor, sessionId).flatMap(session -> {
			if (session.contentDeleted()) {
				return Mono.error(tombstone());
			}
			if (session.endedAt() != null && Instant.now().isAfter(session.endedAt().plus(SAVE_WINDOW))) {
				return Mono.error(new IntelligenceException(410, "dh_content_expired", "保存窗口已过期，文本已按未保存处理。"));
			}
			return buffer.read(sessionId.toString(), session.contentEpoch()).flatMap(frames -> {
				List<Map<String, Object>> finals = new ArrayList<>();
				for (String frame : frames) {
					Map<String, Object> parsed = parseFrame(frame);
					if ("final".equals(parsed.get("kind"))) {
						finals.add(parsed);
					}
				}
				Mono<Long> insertEach = Mono.just(0L);
				for (Map<String, Object> utterance : finals) {
					insertEach = insertEach.flatMap(count -> db.sql("""
							INSERT INTO dh_transcript(id, owner_account_id, session_id, utterance_id,
							    utterance_seq, role, final_text, status, started_at, ended_at, content_epoch)
							VALUES (gen_random_uuid(), :owner, CAST(:sid AS uuid), CAST(:uid AS uuid), :seq,
							    :role, :text, :status, :started, :ended, :epoch)
							ON CONFLICT (session_id, utterance_id) DO NOTHING
							""").bind("owner", actor.accountId()).bind("sid", sessionId.toString())
							.bind("uid", String.valueOf(utterance.get("utteranceId")))
							.bind("seq", ((Number) utterance.getOrDefault("utteranceSeq", 0)).longValue())
							.bind("role", String.valueOf(utterance.getOrDefault("role", "assistant")))
							.bind("text", String.valueOf(utterance.getOrDefault("text", "")))
							.bind("status", String.valueOf(utterance.getOrDefault("status", "complete")))
							.bind("started", OffsetDateTime.now()).bind("ended", OffsetDateTime.now())
							.bind("epoch", session.contentEpoch()).fetch().rowsUpdated().defaultIfEmpty(0L)
							.map(inserted -> count + inserted));
				}
				Mono<Map<String, Object>> body = insertEach.flatMap(inserted -> db
						.sql("UPDATE dh_session SET transcript_version = transcript_version + 1, version = version + 1,"
								+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND transcript_version ="
								+ " :expected RETURNING transcript_version")
						.bind("id", sessionId.toString()).bind("expected", expectedVersion)
						.map(row -> row.get("transcript_version", Integer.class)).one()
						.switchIfEmpty(
								Mono.error(new IntelligenceException(409, "dh_version_conflict", "会话状态已变化，请刷新后重试。")))
						.map(version -> Map.<String, Object>of("savedUtterances", inserted, "version", version)));
				return transactions.transactional(body);
			});
		});
	}

	// API24：utteranceSeq ASC 分页（keyset 简化：offset-free 用 seq 游标）。
	public Mono<Page<TranscriptEntry>> list(PersonalActor actor, UUID sessionId, String cursor, Integer limit) {
		int pageSize = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
		long fromSeq = 0;
		if (cursor != null && !cursor.isBlank()) {
			try {
				fromSeq = Long.parseLong(cursor.trim()) + 1;
			} catch (NumberFormatException invalid) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "分页游标无效。"));
			}
		}
		long finalFrom = fromSeq;
		return load(actor, sessionId).flatMap(session -> {
			if (session.contentDeleted()) {
				return Mono.error(tombstone());
			}
			return db
					.sql("SELECT id::text, utterance_seq, role, final_text, status, started_at, ended_at"
							+ " FROM dh_transcript WHERE session_id = CAST(:sid AS uuid) AND utterance_seq >= :from"
							+ " ORDER BY utterance_seq LIMIT " + (pageSize + 1))
					.bind("sid", sessionId.toString()).bind("from", finalFrom)
					.map(DigitalHumanTranscriptService::mapEntry).all().collectList()
					.map(rows -> rows.size() <= pageSize
							? new Page<>(rows, null)
							: new Page<>(new ArrayList<>(rows.subList(0, pageSize)),
									String.valueOf(rows.get(pageSize - 1).utteranceSeq())));
		});
	}

	// API25：txt 导出（角色/UTC 时间/中断状态）。
	public Mono<String> exportText(PersonalActor actor, UUID sessionId) {
		return list(actor, sessionId, null, 100).flatMap(first -> {
			StringBuilder text = new StringBuilder();
			appendPage(text, first.items());
			if (first.nextCursor() == null) {
				return Mono.just(text.toString());
			}
			return exportRemaining(actor, sessionId, first.nextCursor(), text);
		});
	}

	private Mono<String> exportRemaining(PersonalActor actor, UUID sessionId, String cursor, StringBuilder text) {
		return list(actor, sessionId, cursor, 100).flatMap(page -> {
			appendPage(text, page.items());
			return page.nextCursor() == null
					? Mono.just(text.toString())
					: exportRemaining(actor, sessionId, page.nextCursor(), text);
		});
	}

	private static void appendPage(StringBuilder text, List<TranscriptEntry> entries) {
		for (TranscriptEntry entry : entries) {
			text.append('[').append(entry.role()).append(" ").append(entry.startedAt()).append(" ")
					.append(entry.status()).append("] ").append(entry.text()).append("\n");
		}
	}

	// API26：删除墓碑（事务 contentEpoch+1 + content_deleted；提交后清缓冲）。
	public Mono<Map<String, Object>> delete(PersonalActor actor, UUID sessionId, UUID requestId) {
		Mono<Map<String, Object>> body = operations
				.reserve(actor, OperationKind.transcript_delete, requestId,
						DigitalHumanOperations.canonicalHash(Map.of("sessionId", sessionId.toString())), sessionId)
				.flatMap(operation -> operation.state() == OperationState.succeeded
						? Mono.just(Map.<String, Object>of("id", operation.id(), "state", "succeeded"))
						: load(actor, sessionId).flatMap(session -> {
							if (session.contentDeleted()) {
								return complete(operation.id(), sessionId)
										.thenReturn(Map.<String, Object>of("id", operation.id(), "state", "succeeded"));
							}
							return db
									.sql("UPDATE dh_session SET content_deleted = true,"
											+ " content_epoch = content_epoch + 1, version = version + 1,"
											+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND NOT content_deleted"
											+ " RETURNING content_epoch")
									.bind("id", sessionId.toString()).map(row -> row.get("content_epoch", Long.class))
									.one()
									.flatMap(epoch -> db
											.sql("DELETE FROM dh_transcript WHERE session_id =" + " CAST(:id AS uuid)")
											.bind("id", sessionId.toString()).then()
											.then(complete(operation.id(), sessionId))
											.thenReturn(Map.<String, Object>of("id", operation.id(), "state",
													"succeeded", "newContentEpoch", epoch)));
						}));
		return transactions.transactional(body).flatMap(result -> {
			// 提交后清缓冲（新旧 epoch 键都清；幂等）。
			return buffer.erase(sessionId.toString(), 0L).then(buffer.erase(sessionId.toString(), 1L))
					.then(buffer.erase(sessionId.toString(), 2L)).thenReturn(result);
		});
	}

	private static IntelligenceException tombstone() {
		return new IntelligenceException(409, "dh_content_deleted", "该会话文本已删除，不能恢复或重新保存。");
	}

	private Mono<Map<String, Object>> currentVersion(PersonalActor actor, UUID sessionId) {
		return load(actor, sessionId).map(session -> Map.<String, Object>of("saveTranscript", session.saveTranscript(),
				"version", session.transcriptVersion()));
	}

	private Mono<Void> complete(String operationId, UUID resourceId) {
		return db
				.sql("UPDATE dh_operation SET state = 'succeeded', result_ref = CAST(:r AS uuid),"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", operationId).bind("r", resourceId.toString()).then();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseFrame(String frame) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readValue(frame, Map.class);
		} catch (Exception failure) {
			return new LinkedHashMap<>();
		}
	}

	private static TranscriptEntry mapEntry(io.r2dbc.spi.Readable r) {
		return new TranscriptEntry(r.get("id", String.class), r.get("utterance_seq", Long.class),
				com.grassland.intelligence.digitalhuman.DigitalHumanRecords.TranscriptRole
						.valueOf(r.get("role", String.class)),
				r.get("final_text", String.class),
				com.grassland.intelligence.digitalhuman.DigitalHumanRecords.TranscriptStatus
						.valueOf(r.get("status", String.class)),
				toInstant(r.get("started_at", OffsetDateTime.class)),
				toInstant(r.get("ended_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? Instant.EPOCH : time.toInstant();
	}
}
