package com.grassland.intelligence.hypit.job;

import com.grassland.intelligence.hypit.api.HypitDtos.Job;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository.EventRow;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Hypit 任务查询与事件流（任务书 #107-1 C107-04 / 04.9 / §6.3）。
 *
 * <p>
 * SSE：先推 snapshot（cursor 之后的存量事件），再 1s 轮询增量；Last-Event-ID 支持断线续接， 旧 cursor
 * 无法解析时先发 snapshot reset（type=snapshot、data 含 reset 标记）。终帧幂等由 事件表 PK 保证。
 */
@Service
public class HypitJobService {

	private final HypitJobRepository jobs;
	private final HypitJobEventRepository events;
	private final HypitProjectRepository projects;

	public HypitJobService(HypitJobRepository jobs, HypitJobEventRepository events, HypitProjectRepository projects) {
		this.jobs = jobs;
		this.events = events;
		this.projects = projects;
	}

	/** 项目路径查询：owner 校验 + job 归属项目校验。 */
	public Mono<JobRow> ownedJob(String accountId, UUID projectId, UUID jobId) {
		return projects.findOwned(accountId, projectId).switchIfEmpty(Mono.error(notFound())).then(jobs.findById(jobId))
				.switchIfEmpty(Mono.error(notFound()))
				.flatMap(job -> job.projectId() != null && job.projectId().equals(projectId)
						? Mono.just(job)
						: Mono.error(notFound()));
	}

	/**
	 * 全局 Job 查询（§6.2）：operator，或该 job 的提交账号本人；其余 404。
	 */
	public Mono<JobRow> jobById(com.grassland.intelligence.security.IntelligenceCallerResolver.Caller caller,
			com.grassland.intelligence.hypit.security.HypitAccessService access, UUID jobId) {
		return jobs.findById(jobId).switchIfEmpty(Mono.error(notFound()))
				.flatMap(job -> access.isOperator(caller) || job.accountId().equals(caller.accountId())
						? Mono.just(job)
						: Mono.error(notFound()));
	}

	public static Job toDto(JobRow row) {
		return new Job(row.id().toString(), row.projectId() == null ? null : row.projectId().toString(), row.kind(),
				row.state(), row.phase(),
				row.progressJson() == null
						? null
						: com.grassland.intelligence.hypit.project.HypitJson.read(row.progressJson()),
				row.checkpointJson(), row.blockedReason(), List.of(),
				row.errorCode() == null
						? null
						: java.util.Map.of(row.errorCode(), row.errorMessage() == null ? "" : row.errorMessage()),
				row.createdAt().toString(), row.updatedAt().toString());
	}

	public Flux<ServerSentEvent<String>> eventStream(String accountId, UUID projectId, UUID jobId, String lastEventId) {
		long cursor = parseCursor(lastEventId);
		return ownedJob(accountId, projectId, jobId).flatMapMany(job -> {
			Flux<List<EventRow>> snapshots = events.snapshot(jobId, cursor).repeat();
			return snapshots.concatMap(batch -> Flux.fromIterable(batch)).map(HypitJobService::toSse)
					.takeUntil(sse -> "terminal".equals(sse.event())).mergeWith(snapshotResetIfStale(jobId, cursor));
		});
	}

	/** 旧/不可解析 cursor：显式 snapshot reset 帧让客户端重建状态。 */
	private Flux<ServerSentEvent<String>> snapshotResetIfStale(UUID jobId, long cursor) {
		if (cursor > 0) {
			return Flux.empty();
		}
		return Flux.just(ServerSentEvent.<String>builder().event("snapshot").id("evt-" + jobId + "-0")
				.data("{\"reset\":true}").build());
	}

	private static ServerSentEvent<String> toSse(EventRow event) {
		return ServerSentEvent.<String>builder().id(event.eventId()).event(event.type())
				.data(event.payloadJson() == null ? "{}" : event.payloadJson()).build();
	}

	/** 轮询增量（终帧后完成流）。 */
	public Flux<ServerSentEvent<String>> tailing(String accountId, UUID projectId, UUID jobId, String lastEventId) {
		long cursor = parseCursor(lastEventId);
		return eventStream(accountId, projectId, jobId, lastEventId)
				.concatWith(pollAfter(accountId, projectId, jobId, cursor));
	}

	private Flux<ServerSentEvent<String>> pollAfter(String accountId, UUID projectId, UUID jobId, long startCursor) {
		return Flux
				.defer(() -> ownedJob(accountId, projectId, jobId)
						.flatMapMany(job -> events.listAfter(jobId, startCursor).map(HypitJobService::toSse)
								.takeUntil(sse -> "terminal".equals(sse.event()))))
				.delaySubscription(Duration.ofSeconds(1));
	}

	static long parseCursor(String lastEventId) {
		if (lastEventId == null || lastEventId.isBlank()) {
			return 0;
		}
		int dash = lastEventId.lastIndexOf('-');
		if (dash < 0) {
			return 0;
		}
		try {
			return Long.parseLong(lastEventId.substring(dash + 1));
		} catch (NumberFormatException error) {
			return 0;
		}
	}

	private static IntelligenceException notFound() {
		return new IntelligenceException(404, "hypit_not_found", "资源不存在。");
	}

	/** SSE 输出 mediaType（controller 复用）。 */
	public static final MediaType TEXT_EVENT_STREAM = MediaType.TEXT_EVENT_STREAM;
}
