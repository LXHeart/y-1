package com.grassland.intelligence.hypit.job;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * hypit_job_event 仓储（任务书 #107-1 C107-04 / 04.9 / §6.3）。
 *
 * <p>
 * sequence 在锁住 job 的短事务内递增：先 {@code SELECT ... FOR UPDATE} 占住 job 行，再
 * {@code MAX(sequence)+1} 插入，事务提交后序号无洞、SSE Last-Event-ID 可精确续接。
 */
@Component
public class HypitJobEventRepository {

	private final DatabaseClient db;
	private final TransactionalOperator transactions;

	public HypitJobEventRepository(DatabaseClient db, TransactionalOperator transactions) {
		this.db = db;
		this.transactions = transactions;
	}

	public record EventRow(UUID jobId, long sequence, String type, String payloadJson, Instant createdAt) {
		public String eventId() {
			return "evt-" + jobId + "-" + sequence;
		}
	}

	/**
	 * 锁 job 行后递增 sequence 追加；并发撞号（RETURNING 空）时有界重算重插，调用方视角 sequence 连续无洞、SSE
	 * Last-Event-ID 可精确续接。
	 */
	public Mono<EventRow> append(UUID jobId, String type, String payloadJson) {
		return appendWithAttempts(jobId, type, payloadJson, 0).as(transactions::transactional);
	}

	private Mono<EventRow> appendWithAttempts(UUID jobId, String type, String payloadJson, int attempt) {
		if (attempt >= 20) {
			return Mono.error(new IllegalStateException("hypit event sequence contention: " + jobId));
		}
		return db.sql("SELECT id FROM hypit_job WHERE id = CAST(:id AS uuid) FOR UPDATE").bind("id", jobId.toString())
				.fetch().rowsUpdated()
				.then(db.sql("SELECT COALESCE(MAX(sequence), 0) + 1 AS next FROM hypit_job_event"
						+ " WHERE job_id = CAST(:id AS uuid)").bind("id", jobId.toString())
						.map((row, meta) -> row.get("next", Long.class)).one())
				.flatMap(sequence -> db
						.sql("INSERT INTO hypit_job_event(job_id, sequence, type, payload)"
								+ " VALUES (CAST(:job AS uuid), :sequence, :type, CAST(:payload AS jsonb))"
								+ " ON CONFLICT (job_id, sequence) DO NOTHING RETURNING job_id::text, sequence,"
								+ " type, payload::text, created_at")
						.bind("job", jobId.toString()).bind("sequence", sequence).bind("type", type)
						.bind("payload", payloadJson).map(HypitJobEventRepository::mapRow).one())
				.switchIfEmpty(Mono.defer(() -> appendWithAttempts(jobId, type, payloadJson, attempt + 1)));
	}

	public Flux<EventRow> listAfter(UUID jobId, long afterSequence) {
		return db
				.sql("SELECT job_id::text, sequence, type, payload::text, created_at FROM hypit_job_event"
						+ " WHERE job_id = CAST(:job AS uuid) AND sequence > :after ORDER BY sequence")
				.bind("job", jobId.toString()).bind("after", afterSequence).map(HypitJobEventRepository::mapRow).all();
	}

	public Mono<Long> lastSequence(UUID jobId) {
		return db
				.sql("SELECT COALESCE(MAX(sequence), 0) AS max FROM hypit_job_event"
						+ " WHERE job_id = CAST(:job AS uuid)")
				.bind("job", jobId.toString()).map((row, meta) -> row.get("max", Long.class)).one().defaultIfEmpty(0L);
	}

	public Mono<List<EventRow>> snapshot(UUID jobId, long afterSequence) {
		return listAfter(jobId, afterSequence).collectList();
	}

	private static EventRow mapRow(io.r2dbc.spi.Readable row) {
		return new EventRow(UUID.fromString(row.get("job_id", String.class)), row.get("sequence", Long.class),
				row.get("type", String.class), row.get("payload", String.class), row.get("created_at", Instant.class));
	}
}
