package com.grassland.intelligence.hypit.build;

import com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Build 同步 worker（任务书 #107-2 C107-09 步 09.3/09.8）。
 *
 * <p>
 * 三件事：领 queued 的 hypit.build job → sidecar {@code build.submit}（B 侧以 commandId 幂等， 崩溃重领重发的是同一
 * command、同一 engineBuildId，不产生新原生 Build）；租约过期的 running job 重新排队（恢复观察， 不以「恢复」名义放大为新的收费提交
 * —— submit 幂等回放先查固定 ID）；对可观察 Build 收敛 lifecycle/outcome 并投影事件/收口 job。
 */
@Component
public class HypitBuildObserver {

	private static final Logger logger = LoggerFactory.getLogger(HypitBuildObserver.class);
	private static final Duration LEASE = Duration.ofSeconds(45);
	private static final int CLAIM_LIMIT = 5;
	private static final int MAX_ATTEMPTS = 10;

	private final HypitProperties properties;
	private final HypitSidecarClient sidecar;
	private final HypitCommandRepository commands;
	private final HypitBuildRepository builds;
	private final HypitJobRepository jobs;
	private final HypitBuildService service;
	private final DatabaseClient db;
	private final AtomicBoolean running = new AtomicBoolean();

	public HypitBuildObserver(HypitProperties properties, HypitSidecarClient sidecar, HypitCommandRepository commands,
			HypitBuildRepository builds, HypitJobRepository jobs, HypitBuildService service, DatabaseClient db) {
		this.properties = properties;
		this.sidecar = sidecar;
		this.commands = commands;
		this.builds = builds;
		this.jobs = jobs;
		this.service = service;
		this.db = db;
	}

	/** 调度入口：enabled=false 或上一轮未结束（CAS）时首行返回；照 DH 核对 worker 范式。 */
	@Scheduled(fixedDelayString = "${hypit.build-observer-poll-ms:2000}")
	public void runScheduled() {
		if (!properties.enabled() || !sidecar.configured() || !running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> logger.warn("hypit build observer cycle failed", error))
				.onErrorResume(error -> Mono.empty()).doFinally(signal -> running.set(false)).subscribe();
	}

	Mono<String> runOnce() {
		return requeueExpiredLeases()
				.then(claimLimit()).flatMap(claimed -> dispatch(claimed))
				.then(observeObservable()).map(ignored -> "ok");
	}

	/** 09.8：崩溃后租约过期的 running job 回 queued（幂等 submit 回放=恢复观察，不是新提交）。 */
	private Mono<Long> requeueExpiredLeases() {
		return db.sql("UPDATE hypit_job SET state = 'queued', lease_owner = NULL, updated_at = now()"
				+ " WHERE state = 'running' AND kind = '" + HypitBuildService.JOB_KIND + "'"
				+ " AND lease_until < now()").fetch().rowsUpdated();
	}

	/** 本 worker 只认领 hypit.build——泛化 claim 会误领其他 kind 的跟踪行。 */
	private Mono<List<ClaimedJob>> claimLimit() {
		return db.sql("""
				UPDATE hypit_job SET state = 'running', lease_owner = CAST(:owner AS uuid), lease_until = :lease,
				       updated_at = now()
				WHERE id IN (SELECT id FROM hypit_job WHERE state = 'queued' AND kind = 'hypit.build'
				             ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
				RETURNING id::text, command_id::text, attempt
				""").bind("owner", UUID.randomUUID()).bind("lease", Instant.now().plus(LEASE))
				.bind("limit", CLAIM_LIMIT)
				.map((row, meta) -> new ClaimedJob(UUID.fromString(row.get("id", String.class)),
						UUID.fromString(row.get("command_id", String.class)), row.get("attempt", Integer.class)))
				.all().collectList();
	}

	private record ClaimedJob(UUID id, UUID commandId, int attempt) {
	}

	/** 领到的 job → sidecar build.submit；失败退回 queued 并累计 attempt，超限落 failed 留诊断。 */
	private Mono<Void> dispatch(List<ClaimedJob> claimed) {
		Mono<Void> chain = Mono.empty();
		for (ClaimedJob job : claimed) {
			chain = chain.then(dispatchOne(job).onErrorResume(error -> {
				logger.warn("hypit build submit dispatch failed for job {}", job.id(), error);
				String message = String.valueOf(error.getMessage());
				String code = error instanceof com.grassland.intelligence.security.IntelligenceException intelligence
						? intelligence.code()
						: "hypit_backend_unavailable";
				return failOrRetry(job, code, message).then();
			}));
		}
		return chain;
	}

	private Mono<Void> dispatchOne(ClaimedJob job) {
		return builds.findByCommandId(job.commandId())
				.switchIfEmpty(Mono.error(
						new IllegalStateException("build row missing for job " + job.id())))
				.flatMap(build -> submitPayload(build).flatMap(payload -> Mono
						.fromCallable(() -> sidecar.command(build.commandId().toString(), "build.submit", payload))
						.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())))
				.flatMap(command -> command.result() == null
						? Mono.<Void>error(
								new IllegalStateException("build.submit failed: " + command.error()))
						: Mono.<Void>empty());
	}

	/** 提交载荷以冻结的 command payload 为准（plan/revision/runFile/title 提交时已定死）。 */
	private Mono<Map<String, Object>> submitPayload(BuildRow build) {
		return commands.findById(build.commandId()).map(command -> {
			Map<String, Object> payload = HypitJson.read(command.payloadJson());
			payload.put("projectId", build.projectId().toString());
			return payload;
		});
	}

	private Mono<Void> failOrRetry(ClaimedJob job, String code, String message) {
		Mono<Boolean> exceeded = db
				.sql("UPDATE hypit_job SET attempt = attempt + 1, updated_at = now() WHERE id = CAST(:id AS uuid)"
						+ " RETURNING attempt").bind("id", job.id().toString())
				.map((row, meta) -> row.get("attempt", Integer.class) != null
						&& row.get("attempt", Integer.class) >= MAX_ATTEMPTS)
				.one().defaultIfEmpty(true);
		return exceeded.flatMap(tooMany -> tooMany ? jobs.updateState(job.id(), "failed", code, message).then()
				: jobs.updateState(job.id(), "queued", null, null).then());
	}

	/** 观察循环：所有非终态且已提交引擎的 Build 收敛一次；finished→job succeeded、incomplete→waiting_input。 */
	private Mono<Long> observeObservable() {
		return builds.findObservable(50).flatMap(service::converge).count();
	}
}
