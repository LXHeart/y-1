package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 费用核对 worker（任务书 #105D C105D-06 / 共享契约 K08 表第 5 行、K07.1
 * ReconciliationSummary）。
 *
 * <p>
 * 只领 {@code state=unknown} 或
 * {@code state=succeeded 且 settlement_state IN (pending,failed)} 的
 * invocation——<b>绝不重新调用生成接口</b>；unknown 保留经济事实待人工/真实查询证据（仅报告）；已确认 结果但结算失败（usage
 * 已持久）只重放结算（原经济键）。批处理诊断（非公开 API）。
 */
@Component
public class DigitalHumanReconciliationWorker {

	private static final Logger logger = LoggerFactory.getLogger(DigitalHumanReconciliationWorker.class);
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

	/** K07.1：scanned/claimed/succeeded/failed/pending，无用户内容。 */
	public record ReconciliationSummary(int scanned, int claimed, int succeeded, int failed, int pending) {
	}

	private final DatabaseClient db;
	private final DigitalHumanInvocationService invocations;

	public DigitalHumanReconciliationWorker(DatabaseClient db, DigitalHumanInvocationService invocations) {
		this.db = db;
		this.invocations = invocations;
	}

	public Mono<ReconciliationSummary> reconcilePending(Instant now, int limit) {
		return db.sql("""
				SELECT id::text, owner_account_id, session_id::text, turn_id::text, stage, resource_id::text,
				       segment_index, operation_id::text, ai_run_id::text, state, settlement_state,
				       provider_snapshot::text, budget_snapshot::text, usage_json::text, provider_run_id,
				       request_hash, deadline_at, next_attempt_at, version, created_at, updated_at
				FROM dh_invocation
				WHERE state = 'unknown' OR (state = 'succeeded' AND settlement_state IN ('pending', 'failed'))
				ORDER BY updated_at
				LIMIT :limit
				""").bind("limit", Math.min(Math.max(limit, 1), 200)).map(DigitalHumanInvocationRepository::rowOf).all()
				.collectList().flatMap(rows -> reconcile(rows, now));
	}

	private Mono<ReconciliationSummary> reconcile(List<InvocationRow> rows, Instant now) {
		int[] succeeded = {0};
		int[] failed = {0};
		int[] pending = {0};
		Mono<Void> chain = Mono.empty();
		for (InvocationRow row : rows) {
			if (row.state() == InvocationState.unknown) {
				// 未知：保留经济事实，仅报告（K08：不自动重放推理/退款；人工/真实查询证据才确认）。
				pending[0]++;
				continue;
			}
			UsageUnits usage = parseUsage(row.usageJson());
			if (usage == null) {
				// succeeded 但 usage 丢失：不可按 0 结算，留核对（不发明用量）。
				pending[0]++;
				continue;
			}
			chain = chain.then(invocations.rehydrate(UUID.fromString(row.id()))
					.flatMap(
							prepared -> invocations.settleSuccess(UUID.fromString(row.id()), prepared.context(), usage))
					.doOnNext(ok -> {
						if (Boolean.TRUE.equals(ok)) {
							succeeded[0]++;
						} else {
							failed[0]++;
						}
					}).onErrorResume(error -> {
						logger.warn("dh settlement retry failed: invocationId={} type={}", row.id(),
								error.getClass().getSimpleName());
						failed[0]++;
						return Mono.empty();
					}).then());
		}
		// 计数在 chain 订阅期更新：summary 必须延迟构造（Mono.just 会在组装期急切读计数）。
		return chain.then(Mono.fromCallable(() -> new ReconciliationSummary(rows.size(),
				succeeded[0] + failed[0] + pending[0], succeeded[0], failed[0], pending[0])));
	}

	private static UsageUnits parseUsage(String usageJson) {
		if (usageJson == null || usageJson.isBlank()) {
			return null;
		}
		try {
			return JSON.readValue(usageJson, UsageUnits.class);
		} catch (Exception failure) {
			return null;
		}
	}
}
