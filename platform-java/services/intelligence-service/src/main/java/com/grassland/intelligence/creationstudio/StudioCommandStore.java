package com.grassland.intelligence.creationstudio;

import com.grassland.intelligence.creationstudio.plan.VisualPlanRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Durable intents for channel/account commands. Never stores credentials or
 * signed URLs.
 */
@Component
public class StudioCommandStore {
	public record Result(UUID resourceId, int version, Map<String, Object> body, boolean replayed) {
		public Result(UUID resourceId, int version, Map<String, Object> body) {
			this(resourceId, version, body, false);
		}
	}
	private final DatabaseClient db;
	private final VisualPlanRepository records;
	private final TransactionalOperator transactions;
	public StudioCommandStore(DatabaseClient db, VisualPlanRepository records, TransactionalOperator transactions) {
		this.db = db;
		this.records = records;
		this.transactions = transactions;
	}
	public Mono<Result> execute(String owner, String kind, UUID requestId, String hash, Supplier<Mono<Result>> action,
			Function<UUID, Mono<?>> readable) {
		return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
				.bind("key", owner + ":" + kind + ":" + requestId).then()
				.then(records.findStudioApply(owner, kind, requestId.toString()).flatMap(prior -> {
					if (!prior.requestHash().equals(hash))
						return Mono.error(conflict());
					return readable.apply(prior.resourceId())
							.thenReturn(new Result(prior.resourceId(), prior.appliedVersion(), prior.result(), true));
				}).switchIfEmpty(Mono.defer(action)
						.flatMap(result -> records
								.recordStudioApply(owner, kind, requestId.toString(), hash, result.resourceId(),
										result.version(), result.body())
								.flatMap(inserted -> inserted ? Mono.just(result) : Mono.error(conflict())))))
				.as(transactions::transactional);
	}
	private static IntelligenceException conflict() {
		return new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "同一 requestId 已用于不同请求");
	}
}
