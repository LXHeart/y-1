package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 持久 Agent worker（任务书 #107-2 C107-14 / 14.6）：认领 hypit.agent job， 逐步骤经
 * StepService 执行 scope 白名单内的工具，全部步骤完成即收口 succeeded/failed。崩溃恢复沿用 K06.1
 * 租约语义（过期租约由 observe 侧统一 重排或下次重领），步骤进度持久在
 * job.checkpoint_json={stepIndex,scope}， 单步动作行由 hypit_job_action 承载（input_hash
 * 幂等定位）。
 */
@Component
public class HypitAgentWorker {

	private static final Logger logger = LoggerFactory.getLogger(HypitAgentWorker.class);
	static final String JOB_KIND = "hypit.agent";
	private static final Duration LEASE = Duration.ofSeconds(45);
	private static final int CLAIM_LIMIT = 3;
	private static final int MAX_STEPS = 40;

	private final HypitJobRepository jobs;
	private final HypitAgentStepService steps;
	private final DatabaseClient db;
	private final boolean enabled;
	private final AtomicBoolean running = new AtomicBoolean();

	public HypitAgentWorker(HypitJobRepository jobs, HypitAgentStepService steps, DatabaseClient db,
			@Value("${hypit.agent-worker.enabled:true}") boolean enabled) {
		this.jobs = jobs;
		this.steps = steps;
		this.db = db;
		this.enabled = enabled;
	}

	@Scheduled(fixedDelayString = "${hypit.agent-worker.poll-ms:5000}")
	public void runScheduled() {
		if (!enabled || !running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> logger.warn("hypit agent worker cycle failed", error))
				.onErrorResume(error -> Mono.empty()).doFinally(signal -> running.set(false)).subscribe();
	}

	public Mono<Integer> runOnce() {
		return claimDue().flatMap(this::driveToCompletion).collectList().map(List::size);
	}

	private Flux<UUID> claimDue() {
		return db.sql("""
				UPDATE hypit_job SET state = 'running', lease_owner = CAST(:owner AS uuid), lease_until = :lease,
				       updated_at = now()
				WHERE id IN (SELECT id FROM hypit_job WHERE state = 'queued' AND kind = 'hypit.agent'
				             ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
				RETURNING id::text
				""").bind("owner", UUID.randomUUID()).bind("lease", Instant.now().plus(LEASE))
				.bind("limit", CLAIM_LIMIT).map((row, meta) -> UUID.fromString(row.get("id", String.class))).all();
	}

	/** 单批步骤执行：checkpoint 里 actions 为空→直接 succeeded；越界步骤数→failed。 */
	private Mono<Void> driveToCompletion(UUID jobId) {
		return jobFields(jobId).<Void>flatMap(fields -> {
			Map<String, Object> checkpoint = fields.checkpoint();
			int stepIndex = checkpoint.get("stepIndex") instanceof Number number ? number.intValue() : 0;
			HypitAgentScope scope = "execute".equals(checkpoint.get("scope"))
					? HypitAgentScope.executeScope()
					: HypitAgentScope.readOnlyScope();
			List<HypitAgentAction> requested = readActions(checkpoint);
			if (stepIndex >= MAX_STEPS) {
				return jobs.updateState(jobId, "failed", "hypit_agent_max_steps", null).then();
			}
			if (requested.isEmpty()) {
				return jobs.updateState(jobId, "succeeded", null, null).then();
			}
			Map<String, Object> next = new HashMap<>(checkpoint);
			next.put("stepIndex", stepIndex + 1);
			return steps.run(jobId, fields.projectId(), scope, stepIndex, requested)
					.flatMap(rows -> jobs.saveCheckpoint(jobId, HypitJson.write(next)).then())
					.then(jobs.updateState(jobId, "succeeded", null, null).then());
		});
	}

	private record JobFields(UUID id, UUID projectId, Map<String, Object> checkpoint) {
	}

	private Mono<JobFields> jobFields(UUID jobId) {
		return db.sql("""
				SELECT project_id::text AS project, checkpoint_json::text AS checkpoint
				FROM hypit_job WHERE id = CAST(:id AS uuid)
				""").bind("id", jobId.toString()).map((row, meta) -> {
			String project = row.get("project", String.class);
			String checkpoint = row.get("checkpoint", String.class);
			Map<String, Object> parsed = checkpoint == null || checkpoint.isBlank()
					? Map.of()
					: HypitJson.read(checkpoint);
			return new JobFields(jobId, project == null ? null : UUID.fromString(project), parsed);
		}).one().switchIfEmpty(Mono.error(new IllegalStateException("agent job vanished: " + jobId)));
	}

	private static List<HypitAgentAction> readActions(Map<String, Object> checkpoint) {
		Object raw = checkpoint.get("actions");
		List<HypitAgentAction> actions = new ArrayList<>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					actions.add(new HypitAgentAction(String.valueOf(map.get("kind")),
							map.get("input") == null ? "{}" : HypitJson.write(map.get("input")), null, false));
				}
			}
		}
		return actions;
	}
}
