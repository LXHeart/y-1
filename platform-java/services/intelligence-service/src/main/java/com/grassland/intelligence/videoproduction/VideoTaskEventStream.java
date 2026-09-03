package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * 任务 SSE 事件推送（任务书 #65 卡4，§3 契约）：{@code GET /tasks/{id}/events} 任务级单播 +
 * 心跳。事件**不落库、不保证顺序与恰好一次**——前端收到事件后仍以 {@code GET /tasks/{id}} 快照 校准；断流 2
 * 个心跳周期由前端回落轮询（卡5）。
 *
 * <p>
 * 传输双模式（任务书 #69 卡D，{@code ai.video-production.events.transport}）：
 * <ul>
 * <li>{@code local}（默认）：Sinks 存本进程内存，**不做跨实例广播**——多实例部署时事件可能缺失，
 * 由快照轮询兜底（契约明示的降级路径），行为与 #65 交付逐字节不变；</li>
 * <li>{@code redis}：emit 在投本地 sink 的同时经 Redis
 * pub/sub（{@value #REDIS_CHANNEL}）广播， 订阅侧把远端帧投给本实例的 sink（回环抑制：跳过自己发布的帧——本地 sink
 * 已在 emit 时投过）。 pub/sub 丢消息可接受——SSE 契约本就以轮询快照兜底（D4）。</li>
 * </ul>
 *
 * <p>
 * 红线：全部发射入口 try-catch 吞异常——事件失败绝不影响 take/audio/compose 主流程； redis 发布失败仅 WARN
 * 不阻塞 emit 调用方。
 */
@RestController
public class VideoTaskEventStream implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(VideoTaskEventStream.class);

	/** 跨实例广播频道（D4 定死；payload 见 {@link RedisEvent}）。 */
	static final String REDIS_CHANNEL = "grassland:video-events";

	private final IntelligenceCallerResolver callers;
	private final VideoProductionTaskRepository tasks;
	private final VideoShotTakeRepository takes;
	private final VideoShotAudioRepository audios;
	private final Duration heartbeat;
	private final ObjectMapper mapper = new ObjectMapper();

	/** 跨实例模式与连接（local 模式恒为 null/未用）。 */
	private final boolean redisTransport;
	private final ReactiveStringRedisTemplate redis;

	/** 实例标记（#69 卡D 回环抑制：订阅侧跳过自己发布的帧）。 */
	private final String instanceId = UUID.randomUUID().toString();

	private volatile Disposable subscription;
	private volatile boolean running;

	/** 任务级 sink（单播语义：directBestEffort——无订阅者时事件直接丢弃，符合契约）。 */
	private final ConcurrentHashMap<UUID, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

	public VideoTaskEventStream(IntelligenceCallerResolver callers, VideoProductionTaskRepository tasks,
			VideoShotTakeRepository takes, VideoShotAudioRepository audios,
			@Value("${ai.video-production.sse-heartbeat-seconds:30}") long heartbeatSeconds,
			@Value("${ai.video-production.events.transport:local}") String transport,
			ReactiveStringRedisTemplate redis) {
		this.callers = callers;
		this.tasks = tasks;
		this.takes = takes;
		this.audios = audios;
		this.heartbeat = Duration.ofSeconds(Math.max(1, heartbeatSeconds));
		boolean redisWanted = "redis".equalsIgnoreCase(transport);
		if (!redisWanted && !"local".equalsIgnoreCase(transport)) {
			throw new IllegalStateException("ai.video-production.events.transport 仅支持 local|redis，当前: " + transport);
		}
		if (redisWanted && redis == null) {
			throw new IllegalStateException("events.transport=redis 需要 ReactiveStringRedisTemplate（缺少 Redis 配置）");
		}
		this.redisTransport = redisWanted;
		this.redis = redis;
	}

	// ---------------- SmartLifecycle：redis 订阅常驻（#69 卡D） ----------------

	@Override
	public void start() {
		if (!redisTransport || subscription != null) {
			return;
		}
		// 常驻订阅。Flux.defer 让 listenTo 的装配也进入重试域——连接不可达时订阅容器在装配期
		// 即抛（ReactiveRedisMessageListenerContainer 构造即建连），retry 重连直到恢复
		// （pub/sub 语义允许丢消息，快照轮询兜底）。
		subscription = Flux.defer(
				() -> redis.listenTo(ChannelTopic.of(REDIS_CHANNEL)).doOnNext(message -> receive(message.getMessage())))
				.retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(30)))
				.subscribe();
		running = true;
	}

	@Override
	public void stop() {
		running = false;
		Disposable current = subscription;
		subscription = null;
		if (current != null && !current.isDisposed()) {
			current.dispose();
		}
	}

	@Override
	public boolean isRunning() {
		return !redisTransport || running;
	}

	/** 应用关闭时取消订阅（SmartLifecycle stop 之外的双保险，幂等）。 */
	@PreDestroy
	void shutdown() {
		stop();
	}

	/** 远端帧落地：回环抑制（跳过自己发的）→ 已有 sink 才投（无订阅者丢弃，与契约一致）。 */
	private void receive(String envelope) {
		try {
			RedisEvent event = mapper.readValue(envelope, RedisEvent.class);
			if (event == null || instanceId.equals(event.instanceId())) {
				return;
			}
			deliverLocal(sinks.get(UUID.fromString(event.taskId())), UUID.fromString(event.taskId()), event.frame(),
					event.complete());
		} catch (Exception error) {
			log.debug("video event redis receive failed", error);
		}
	}

	@GetMapping("/api/video-production/tasks/{id}/events")
	public Mono<ResponseEntity<Flux<DataBuffer>>> events(@PathVariable UUID id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> tasks.findById(id, caller.accountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
				.map(task -> sseBody(live(task.id()), exchange));
	}

	/** 心跳 + 事件流合流；事件流终态完成（takeUntilOther 连带停跳）或客户端断开即收口。包级可见供 IT 直接订阅。 */
	Flux<String> live(UUID taskId) {
		// multicast：多订阅者（刷新页面旧标签未关）可并存；无订阅者时 tryEmitNext 即丢（契约容忍）
		Flux<String> events = sinks.computeIfAbsent(taskId, key -> Sinks.many().multicast().onBackpressureBuffer())
				.asFlux();
		Flux<String> beats = Flux.interval(heartbeat).map(ignored -> frame(Map.of("type", "heartbeat")))
				.onBackpressureDrop().takeUntilOther(events.ignoreElements());
		return beats.mergeWith(events).doFinally(signal -> release(taskId));
	}

	private void release(UUID taskId) {
		Sinks.Many<String> sink = sinks.get(taskId);
		if (sink != null && sink.currentSubscriberCount() == 0) {
			sinks.remove(taskId, sink);
		}
	}

	private ResponseEntity<Flux<DataBuffer>> sseBody(Flux<String> payloads, ServerWebExchange exchange) {
		// 帧格式沿用 storyboard SSE 契约（data: {...}\n\n）；无 [DONE] 终止帧——活流由断开/终态收口
		Flux<DataBuffer> body = payloads.map(payload -> {
			byte[] bytes = ("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
			DataBuffer buffer = exchange.getResponse().bufferFactory().allocateBuffer(bytes.length);
			buffer.write(bytes);
			return buffer;
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.set("X-Accel-Buffering", "no");
		headers.setCacheControl("no-cache");
		return new ResponseEntity<>(body, headers, HttpStatus.OK);
	}

	// ---------------- 发射入口（全部吞异常） ----------------

	/** storyboard 维度入口（worker 手里只有 storyboard）：响应式解析最近任务号后发射。 */
	private void resolveTaskId(VideoStoryboard storyboard, java.util.function.Consumer<UUID> emitter) {
		try {
			tasks.findLatestByStoryboard(storyboard.id(), storyboard.accountId()).map(VideoProductionTask::id)
					.subscribe(emitter::accept,
							error -> log.debug("event task resolve failed storyboardId={}", storyboard.id(), error));
		} catch (RuntimeException error) {
			log.debug("event task resolve failed storyboardId={}", storyboard.id(), error);
		}
	}

	public void emitPhaseFor(VideoStoryboard storyboard, String phase) {
		resolveTaskId(storyboard, taskId -> emitPhase(taskId, phase));
	}

	public void emitTakeFor(VideoStoryboard storyboard, VideoShotTake take, String status) {
		resolveTaskId(storyboard, taskId -> emitTake(taskId, take, status));
	}

	public void emitShotFor(VideoStoryboard storyboard, UUID shotId, String status) {
		resolveTaskId(storyboard, taskId -> emitShot(taskId, shotId, status));
	}

	public void emitAudioFor(VideoStoryboard storyboard, VideoShotAudio audio, String status) {
		resolveTaskId(storyboard, taskId -> emitAudio(taskId, audio, status));
	}

	/** phase 变更帧；succeeded/failed/cancelled 后 complete 流（前端据此收口）。 */
	public void emitPhase(UUID taskId, String phase) {
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "phase");
			payload.put("phase", phase);
			dispatch(taskId, frame(payload), VideoProductionTask.isTerminalPhase(phase));
		} catch (RuntimeException error) {
			log.debug("phase event emit failed taskId={}", taskId, error);
		}
	}

	public void emitShot(UUID taskId, UUID shotId, String status) {
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "shot");
			payload.put("shotId", shotId.toString());
			payload.put("status", status);
			dispatch(taskId, frame(payload), false);
		} catch (RuntimeException error) {
			log.debug("shot event emit failed taskId={}", taskId, error);
		}
	}

	/** take 状态帧：queued/submitted/processing 归并为 generating（§3 契约值集）。 */
	public void emitTake(UUID taskId, VideoShotTake take, String status) {
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "take");
			payload.put("shotId", take.shotId().toString());
			payload.put("takeId", take.id().toString());
			payload.put("status", switch (status == null ? "" : status) {
				case VideoShotTake.STATUS_SUCCEEDED -> "succeeded";
				case VideoShotTake.STATUS_FAILED, VideoShotTake.STATUS_CANCELLED -> "failed";
				default -> "generating";
			});
			dispatch(taskId, frame(payload), false);
		} catch (RuntimeException error) {
			log.debug("take event emit failed taskId={}", taskId, error);
		}
	}

	/** audio 状态帧：succeeded/failed/skipped/pending/generating（§3 契约值集）。 */
	public void emitAudio(UUID taskId, VideoShotAudio audio, String status) {
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "audio");
			payload.put("shotId", audio.shotId().toString());
			payload.put("status", switch (status == null ? "" : status) {
				case VideoShotAudio.STATUS_SUCCEEDED -> "succeeded";
				case VideoShotAudio.STATUS_FAILED -> "failed";
				case VideoShotAudio.STATUS_SKIPPED -> "skipped";
				case VideoShotAudio.STATUS_PROCESSING, VideoShotAudio.STATUS_SUBMITTED -> "generating";
				default -> "pending";
			});
			dispatch(taskId, frame(payload), false);
		} catch (RuntimeException error) {
			log.debug("audio event emit failed taskId={}", taskId, error);
		}
	}

	/** compose 每镜进度帧（0..100，完成一镜发一帧）。 */
	public void emitComposeProgress(UUID taskId, int percent) {
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "compose_progress");
			payload.put("percent", Math.max(0, Math.min(100, percent)));
			dispatch(taskId, frame(payload), false);
		} catch (RuntimeException error) {
			log.debug("compose progress event emit failed taskId={}", taskId, error);
		}
	}

	/**
	 * 'selecting' 帧（§3）：全部 take 与 audio 终态、任务未进 composing 时发一帧。
	 * 幂等性由「不保证恰好一次」语义覆盖——重复帧前端只触发快照刷新，无副作用。
	 */
	public Mono<Void> maybeEmitSelecting(VideoStoryboard storyboard) {
		return tasks.findLatestByStoryboard(storyboard.id(), storyboard.accountId()).flatMap(task -> {
			if (task == null || task.isTerminal() || VideoProductionTask.PHASE_COMPOSING.equals(task.phase())) {
				return Mono.empty();
			}
			return takes.findByStoryboard(storyboard.id()).collectList()
					.flatMap(takeList -> audios.findByStoryboard(storyboard.id()).collectList().flatMap(audioList -> {
						boolean allTakesTerminal = !takeList.isEmpty()
								&& takeList.stream().allMatch(VideoShotTake::isTerminal);
						boolean allAudiosTerminal = audioList.isEmpty()
								|| audioList.stream().allMatch(VideoShotAudio::isSettled);
						if (allTakesTerminal && allAudiosTerminal) {
							emitSelecting(task.id());
						}
						return Mono.empty();
					}));
		}).onErrorResume(error -> {
			log.debug("selecting event check failed storyboardId={}", storyboard.id(), error);
			return Mono.empty();
		}).then();
	}

	private void emitSelecting(UUID taskId) {
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "phase");
			payload.put("phase", "selecting");
			dispatch(taskId, frame(payload), false);
		} catch (RuntimeException error) {
			log.debug("selecting event emit failed taskId={}", taskId, error);
		}
	}

	// ---------------- 分发（#69 卡D 双模式） ----------------

	/** Redis 广播信封：instanceId 供回环抑制，complete 供远端终态收口。 */
	record RedisEvent(String instanceId, String taskId, String frame, boolean complete) {
	}

	/**
	 * 帧分发：local 模式只投本地 sink（无订阅者即丢，#65 现状逐字节不变）； redis 模式在投本地 sink
	 * 的同时广播（远端实例订阅侧落地，回环抑制见 {@link #receive}）。
	 */
	private void dispatch(UUID taskId, String payload, boolean terminal) {
		if (redisTransport) {
			deliverLocal(sinks.get(taskId), taskId, payload, terminal);
			publish(taskId, payload, terminal);
			return;
		}
		deliverLocal(sinks.get(taskId), taskId, payload, terminal);
	}

	private void deliverLocal(Sinks.Many<String> sink, UUID taskId, String payload, boolean terminal) {
		if (sink == null) {
			return;
		}
		sink.tryEmitNext(payload);
		if (terminal) {
			sink.tryEmitComplete();
			sinks.remove(taskId, sink);
		}
	}

	/** redis 发布：Mono.defer 包裹后异步订阅（装配期不求值），失败仅 WARN 不阻塞 emit 调用方。 */
	private void publish(UUID taskId, String payload, boolean terminal) {
		try {
			String envelope = mapper
					.writeValueAsString(new RedisEvent(instanceId, taskId.toString(), payload, terminal));
			Mono.defer(() -> redis.convertAndSend(REDIS_CHANNEL, envelope)).subscribeOn(Schedulers.boundedElastic())
					.subscribe(receivers -> {
					}, error -> log.warn("video event redis publish failed taskId={}", taskId, error));
		} catch (Exception error) {
			log.warn("video event redis publish failed taskId={}", taskId, error);
		}
	}

	private String frame(Map<String, Object> payload) {
		try {
			return mapper.writeValueAsString(payload);
		} catch (Exception error) {
			return "{\"type\":\"heartbeat\"}";
		}
	}
}
