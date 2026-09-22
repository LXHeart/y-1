package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 有序事件（任务书 #105C C105C-03 / K06、K13.1）：序号、durable/volatile 分层、只读重连。
 *
 * <p>
 * 状态/用量事件进 dh_event（seq 由同语句 last_seq+1 分配；eventId UNIQUE 去重）；正文事件<b>绝不</b>落表
 * （DigitalHumanContentBuffer 承接）。SSE：先 snapshot(seq=S)，再回放 (afterSeq,S] durable
 * 事件（缺口 → replayComplete=false，不虚构缺失文本），再接 live（进程内 sink）；20s 注释心跳；每 10s 复核
 * owner/生命周期， 撤销即终止；每账号最多 2 条读流（429）。旧 epoch 迟到事件只丢弃不改状态。
 */
@Component
public class DigitalHumanEventService {

	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
	/** 注释心跳哨兵（controller 层外的流内部表示）。 */
	static final String HEARTBEAT = "::heartbeat";
	private static final int MAX_STREAMS_PER_ACCOUNT = 2;

	private final DatabaseClient db;
	private final DigitalHumanAuthorization authorization;
	private final Map<String, Sinks.Many<String>> liveSinks = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> accountStreams = new ConcurrentHashMap<>();

	public DigitalHumanEventService(DatabaseClient db, DigitalHumanAuthorization authorization) {
		this.db = db;
		this.authorization = authorization;
	}

	// ---------- append ----------

	public record AppendResult(String eventId, long seq, boolean duplicated, boolean staleEpoch) {
	}

	/** durable 写入：eventId 重复回原 seq；旧 epoch 迟到事件丢弃（无状态作用）。 */
	public Mono<AppendResult> append(String sessionId, UUID eventId, String eventType, long leaseEpoch,
			Map<String, Object> payload) {
		return db.sql("SELECT owner_account_id AS owner, lease_epoch FROM dh_session" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId).map((row, meta) -> row).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(session -> {
					if (leaseEpoch < session.get("lease_epoch", Long.class)) {
						return Mono.just(new AppendResult(eventId.toString(), -1, false, true));
					}
					return db.sql("SELECT seq FROM dh_event WHERE event_id = CAST(:eid AS uuid)")
							.bind("eid", eventId.toString()).map(row -> row.get("seq", Long.class)).one()
							.map(existing -> new AppendResult(eventId.toString(), existing, true, false))
							.switchIfEmpty(Mono.defer(() -> db
									.sql("UPDATE dh_session SET last_seq = last_seq + 1 WHERE id = CAST(:sid AS uuid)"
											+ " RETURNING last_seq")
									.bind("sid", sessionId).map(row -> row.get("last_seq", Long.class)).one()
									.flatMap(seq -> db
											.sql("INSERT INTO dh_event(session_id, seq, event_id, event_type, payload,"
													+ " owner_account_id) VALUES (CAST(:id AS uuid), :seq,"
													+ " CAST(:eid AS uuid), :type, CAST(:payload AS jsonb), :owner)"
													+ " RETURNING seq")
											.bind("id", sessionId).bind("seq", seq).bind("eid", eventId.toString())
											.bind("type", eventType).bind("payload", writeJson(payload))
											.bind("owner", session.get("owner", String.class))
											.map(row -> row.get("seq", Long.class)).one())
									.map(seq -> new AppendResult(eventId.toString(), seq, false, false))
									// 并发同 seq 冲突（PK）→ 旧事件迟到竞争：读回已存在行幂等返回。
									.onErrorResume(org.springframework.dao.DataIntegrityViolationException.class,
											conflict -> db
													.sql("SELECT seq FROM dh_event WHERE session_id = CAST(:id AS uuid)"
															+ " AND event_id = CAST(:eid AS uuid)")
													.bind("id", sessionId).bind("eid", eventId.toString())
													.map(row -> row.get("seq", Long.class)).one()
													.map(seq -> new AppendResult(eventId.toString(), seq, false,
															false)))));
				});
	}

	/** 事务提交后的进程内通知（live 订阅者）。 */
	public void publishLive(String sessionId, String frame) {
		Sinks.Many<String> sink = liveSinks.get(sessionId);
		if (sink != null) {
			sink.tryEmitNext(frame);
		}
	}

	// ---------- SSE ----------

	/** API18：owner 授权 + 快照 → 回放 → live；撤销/越权终止。 */
	public Flux<ServerSentEvent<String>> stream(PersonalActor actor, UUID sessionId, Long afterSeq) {
		return Flux.usingWhen(Mono.fromCallable(() -> acquire(actor)),
				acquired -> openStream(actor, sessionId, afterSeq == null ? 0L : afterSeq),
				acquired -> Mono.fromRunnable(() -> release(actor)));
	}

	/**
	 * 预检入口（SSE 端点必须在返回 Flux <b>之前</b>完成 404/429 判定——响应提交后错误只能关闭连接，
	 * 不能改状态码）。预检通过后返回惰性事件流（release 由流终止承担）。
	 */
	public Mono<Flux<ServerSentEvent<String>>> open(PersonalActor actor, UUID sessionId, Long afterSeq) {
		long from = afterSeq == null ? 0L : afterSeq;
		return loadHead(actor, sessionId).map(head -> {
			acquire(actor); // 满额 → 429（响应提交前抛出）
			return Flux.usingWhen(Mono.just(true), acquired -> openStream(actor, sessionId, from),
					acquired -> Mono.fromRunnable(() -> release(actor)));
		});
	}

	private Void acquire(PersonalActor actor) {
		// CAS 抢占：满额（≥2）时第 3 条拒绝，不污染计数。
		AtomicInteger counter = accountStreams.computeIfAbsent(actor.accountId(), key -> new AtomicInteger());
		while (true) {
			int current = counter.get();
			if (current >= MAX_STREAMS_PER_ACCOUNT) {
				throw new IntelligenceException(429, "dh_capacity_full", "读流已达上限，请先关闭其它订阅。");
			}
			if (counter.compareAndSet(current, current + 1)) {
				return null;
			}
		}
	}

	private void release(PersonalActor actor) {
		AtomicInteger counter = accountStreams.get(actor.accountId());
		if (counter != null) {
			counter.updateAndGet(current -> Math.max(0, current - 1));
		}
	}

	private Flux<ServerSentEvent<String>> openStream(PersonalActor actor, UUID sessionId, long from) {
		Mono<Head> head = loadHead(actor, sessionId);
		return head.flatMapMany(
				h -> db.sql("SELECT min(seq) AS s FROM dh_event WHERE session_id = CAST(:id AS uuid) AND seq > :from")
						.bind("id", sessionId.toString()).bind("from", from)
						// min() 聚合在无命中时返回 NULL 行（非空结果集），不能只靠 defaultIfEmpty。
						.map(row -> row.get("s", Long.class) == null ? Long.valueOf(-1L) : row.get("s", Long.class))
						.one().defaultIfEmpty(-1L).flatMapMany(firstDurableSeq -> {
							boolean replayComplete = firstDurableSeq == -1 || firstDurableSeq <= from + 1;
							ServerSentEvent<String> snapshot = sse("session.snapshot",
									frame(h.lastSeq(),
											UUID.nameUUIDFromBytes((sessionId + ":snapshot").getBytes()).toString(),
											"session.snapshot",
											Map.of("state", h.state(), "replayComplete", replayComplete), sessionId));
							Flux<ServerSentEvent<String>> replay = db
									.sql("SELECT seq, event_id::text AS eid, event_type, payload::text AS payload"
											+ " FROM dh_event WHERE session_id = CAST(:id AS uuid)"
											+ " AND seq > :from AND seq <= :to ORDER BY seq")
									.bind("id", sessionId.toString()).bind("from", from).bind("to", h.lastSeq())
									.map((row, meta) -> sse(row.get("event_type", String.class),
											frame(row.get("seq", Long.class), row.get("eid", String.class),
													row.get("event_type", String.class),
													readJson(row.get("payload", String.class)), sessionId)))
									.all();
							Sinks.Many<String> sink = liveSinks.computeIfAbsent(sessionId.toString(),
									key -> Sinks.many().multicast().directBestEffort());
							Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(20))
									.map(tick -> ServerSentEvent.<String>builder().comment("heartbeat").build());
							Flux<ServerSentEvent<String>> guard = Flux.interval(Duration.ofSeconds(10))
									.concatMap(tick -> alive(actor, sessionId).flatMap(ok -> ok
											? Mono.<ServerSentEvent<String>>empty()
											: Mono.error(new IllegalStateException("permission revoked"))));
							Flux<ServerSentEvent<String>> live = Flux.merge(
									sink.asFlux().map(frameText -> sse(eventTypeOf(frameText), frameText)), heartbeat,
									guard);
							return Flux.concat(Mono.just(snapshot), replay, live);
						}));
	}

	private Mono<Boolean> alive(PersonalActor actor, UUID sessionId) {
		return authorization.requireActiveAccount(actor)
				.then(db.sql("SELECT owner_account_id AS owner FROM dh_session WHERE id = CAST(:id AS uuid)")
						.bind("id", sessionId.toString()).map(row -> row.get("owner", String.class)).one())
				.map(owner -> owner != null && owner.equals(actor.accountId())).onErrorReturn(false);
	}

	private Mono<Head> loadHead(PersonalActor actor, UUID sessionId) {
		return db.sql(
				"SELECT owner_account_id AS owner, last_seq, state FROM dh_session" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId.toString())
				.map((row, meta) -> new Head(row.get("owner", String.class), row.get("last_seq", Long.class),
						row.get("state", String.class)))
				.one().switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(head -> head.owner().equals(actor.accountId())
						? Mono.just(head)
						: Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private record Head(String owner, long lastSeq, String state) {
	}

	// ---------- 帧工具 ----------

	private static String frame(long seq, String eventId, String type, Map<String, Object> payload, UUID sessionId) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("v", 1);
		frame.put("eventId", eventId);
		frame.put("sessionId", sessionId.toString());
		frame.put("seq", seq);
		frame.put("type", type);
		frame.put("payload", payload == null ? Map.of() : payload);
		return writeJson(frame);
	}

	private static ServerSentEvent<String> sse(String event, String data) {
		return ServerSentEvent.builder(data).event(event).build();
	}

	private static String eventTypeOf(String frameText) {
		try {
			JsonNode node = JSON.readTree(frameText);
			return node.path("type").asText("session.event");
		} catch (Exception failure) {
			return "session.event";
		}
	}

	private static String writeJson(Object value) {
		try {
			// K01：正文/载荷不允许 U+0000——序列化后防御性剔除（避免任何路径把 NUL 带入绑定参数）。
			return JSON.writeValueAsString(value).replace("\u0000", "").replace("\0", "");
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static Map<String, Object> readJson(String json) {
		try {
			return JSON.readValue(json, Map.class);
		} catch (Exception failure) {
			return new LinkedHashMap<>();
		}
	}

	// ---------- 查询（IT 断言） ----------

	public Mono<Long> durableCount(UUID sessionId) {
		return db.sql("SELECT count(*) AS n FROM dh_event WHERE session_id = CAST(:id AS uuid)")
				.bind("id", sessionId.toString()).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
	}
}
