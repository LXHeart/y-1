package com.grassland.intelligence.hypit.job;

import com.grassland.intelligence.hypit.api.HypitDtos.Job;
import com.grassland.intelligence.hypit.job.HypitJobEventRepository.EventRow;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * SSE（C107F2-13 §6.6）：完整 {@code HypitSseEvent}
 * envelope（id={jobUuid}:{sequence}）；初始/非法/跨 job cursor 先发 snapshot reset（权威 job
 * + 最新 sequence），此后 1 秒一次增量查询（每批≤100）、15 秒 心跳、terminal 即停；有效 cursor
 * 严格大于续播，无新事件不重发历史。
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
		return tailing(accountId, projectId, jobId, lastEventId);
	}

	/**
	 * C107F2-13（F24/F06，§6.6）：统一 SSE 订阅——完整 envelope、named events、 每订阅独立 cursor（1
	 * 秒增量查询、批≤100）、15 秒心跳、terminal 即停；无新事件不重发历史。
	 */
	public Flux<ServerSentEvent<String>> tailing(String accountId, UUID projectId, UUID jobId, String lastEventId) {
		return ownedJob(accountId, projectId, jobId).flatMapMany(job -> subscription(job, lastEventId));
	}

	private static final int BATCH = 100;
	private static final Duration POLL = Duration.ofSeconds(1);
	private static final Duration HEARTBEAT = Duration.ofSeconds(15);

	private Flux<ServerSentEvent<String>> subscription(JobRow job, String lastEventId) {
		UUID jobId = job.id();
		return events.lastSequence(jobId).flatMapMany(latest -> {
			long cursor = resolveCursor(jobId, lastEventId, latest);
			boolean reset = cursor < 0;
			long start = reset ? latest : cursor;
			// 终态且无可续事件：reset 时补权威快照即止，否则直接完成（不空转轮询）。
			if (job.terminal() && start >= latest) {
				return reset ? Flux.just(resetFrame(job, latest)) : Flux.<ServerSentEvent<String>>empty();
			}
			java.util.concurrent.atomic.AtomicLong track = new java.util.concurrent.atomic.AtomicLong(start);
			Flux<ServerSentEvent<String>> resetHead = reset
					? Flux.just(resetFrame(job, latest))
					: Flux.<ServerSentEvent<String>>empty();
			Flux<ServerSentEvent<String>> backlog = events.listAfter(jobId, start, BATCH).collectList()
					.expand(batch -> batch.size() < BATCH
							? Mono.<List<EventRow>>empty()
							: events.listAfter(jobId, batch.get(batch.size() - 1).sequence(), BATCH).collectList())
					.concatMap(Flux::fromIterable).doOnNext(row -> track.set(row.sequence()))
					.map(row -> envelopeFrame(job, row));
			Flux<ServerSentEvent<String>> polling = Flux.interval(POLL)
					.concatMap(tick -> events.listAfter(jobId, track.get(), BATCH))
					.doOnNext(row -> track.set(row.sequence())).map(row -> envelopeFrame(job, row));
			Flux<ServerSentEvent<String>> heartbeat = Flux.interval(HEARTBEAT).map(tick -> heartbeatFrame(jobId));
			return resetHead.concatWith(backlog).concatWith(polling.mergeWith(heartbeat))
					.takeUntil(sse -> "terminal".equals(sse.event()));
		});
	}

	/**
	 * cursor 解析（§6.6）：正式 {@code {jobUuid}:{sequence}}；旧
	 * {@code evt-{jobUuid}-{sequence}} 兼容。跨 job、超可用历史、不可解析一律 -1（触发 snapshot
	 * reset，不泄漏他 job 数据）。
	 */
	static long resolveCursor(UUID jobId, String lastEventId, long latest) {
		if (lastEventId == null || lastEventId.isBlank()) {
			return -1;
		}
		String owner = null;
		String sequence = null;
		int colon = lastEventId.lastIndexOf(':');
		if (colon > 0) {
			owner = lastEventId.substring(0, colon);
			sequence = lastEventId.substring(colon + 1);
		} else if (lastEventId.startsWith("evt-")) {
			int dash = lastEventId.lastIndexOf('-');
			if (dash > "evt-".length()) {
				owner = lastEventId.substring(4, dash);
				sequence = lastEventId.substring(dash + 1);
			}
		}
		if (owner == null || !owner.equals(jobId.toString())) {
			return -1;
		}
		long parsed;
		try {
			parsed = Long.parseLong(sequence);
		} catch (NumberFormatException error) {
			return -1;
		}
		return parsed >= 0 && parsed <= latest ? parsed : -1;
	}

	/** snapshot reset 头帧：当前权威 job + 最新 sequence，客户端据此重建状态。 */
	private static ServerSentEvent<String> resetFrame(JobRow job, long latest) {
		Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("reset", true);
		data.put("job", HypitJson.read(HypitJson.write(toDto(job))));
		data.put("latestSequence", latest);
		return envelope(job.id(), latest, "snapshot", job.projectId(), null, java.time.Instant.now(), data);
	}

	private static ServerSentEvent<String> heartbeatFrame(UUID jobId) {
		Map<String, Object> envelope = new java.util.LinkedHashMap<>();
		envelope.put("type", "heartbeat");
		envelope.put("jobId", jobId.toString());
		envelope.put("at", java.time.Instant.now().toString());
		// 无 id：心跳不得推进客户端 Last-Event-ID。
		return ServerSentEvent.<String>builder().event("heartbeat").data(HypitJson.write(envelope)).build();
	}

	private static ServerSentEvent<String> envelopeFrame(JobRow job, EventRow event) {
		Map<String, Object> payload = HypitJson.read(event.payloadJson() == null ? "{}" : event.payloadJson());
		return envelope(event.jobId(), event.sequence(), event.type(), job.projectId(),
				HypitJson.stringValue(payload.get("buildId"), null), event.createdAt(), payload);
	}

	private static ServerSentEvent<String> envelope(UUID jobId, long sequence, String type, UUID projectId,
			String buildId, java.time.Instant at, Map<String, Object> data) {
		String id = jobId + ":" + sequence;
		Map<String, Object> envelope = new java.util.LinkedHashMap<>();
		envelope.put("id", id);
		envelope.put("sequence", sequence);
		envelope.put("type", type);
		envelope.put("projectId", projectId == null ? null : projectId.toString());
		envelope.put("jobId", jobId.toString());
		if (buildId != null) {
			envelope.put("buildId", buildId);
		}
		envelope.put("at", at.toString());
		envelope.put("data", data);
		return ServerSentEvent.<String>builder().id(id).event(type).data(HypitJson.write(envelope)).build();
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
