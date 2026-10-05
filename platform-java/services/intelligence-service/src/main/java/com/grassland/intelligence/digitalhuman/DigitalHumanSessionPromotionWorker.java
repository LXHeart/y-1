package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanEventService.AppendResult;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 排队晋升 worker（任务书 105-fix-2 REQ-002/004 / RULE-003/006/007/008 / §7.2/§7.4）。
 *
 * <p>
 * 由 Spring
 * {@code @Scheduled}（{@code digital-human.promotion.poll-interval-ms}，默认 5s）驱动
 * {@link #runScheduled}；{@link #runOnce} 包内测试入口（enabled/running 语义同
 * {@link DigitalHumanSessionReaper}，IT 基座经 {@code IntelligenceItSupport}
 * 静默）。每轮：
 * <ol>
 * <li>{@link #expireAged}：队列总等待截止＝创建时间+60s（RULE-007，age&gt;=60s 到期未派发进
 * ending，回退/接管不能无限续 60s；created_at 永不更新）；</li>
 * <li>{@link #reconcileExpired}：只恢复 §7.2 {@code promotion:} 前缀
 * preparing——reserved（未发送） 回 queued；sent（已发送未决）换 fence 后按 RULE-006
 * 只查询收敛，无前缀行不由本 worker 恢复；</li>
 * <li>{@link #claimAndDispatch}：FIFO（created_at,id）至多晋升 1 个——owner gate（FOR
 * SHARE，缺行 INSERT ON CONFLICT DO NOTHING）→ catalog 单例 FOR UPDATE → session
 * 行认领（§7.4 锁序，每轮一 owner）；容量口径复用 create、解析复用
 * {@link DigitalHumanSessionService#readMaxSessions}； 认领/占槽/fence
 * 同事务提交，候选改变则释放事务下轮重选。</li>
 * </ol>
 * 网络调用绝不在 DB
 * 事务/行锁内：派发（{@link DigitalHumanSessionService#dispatchRuntime}）、state 查询与补偿 end
 * 全部在事务外执行。派发前先 CAS reserved→sent（§7.2 同 fence UUID）；成功＝runtime 返回同
 * sessionId/leaseEpoch/mediaEpoch 且可连接状态，再在当前 fence 下 preparing→connecting CAS
 * 与一条 {@code session.state} durable append 同事务（事务内重新获取 owner gate 并校验
 * fence/epoch/state），<b>提交后</b>才
 * {@link DigitalHumanEventService#publishSessionState}
 * （RULE-008：回滚没有通知）。明确未受理（create 入口 503 拒绝）且 state 精确 404 才回 queued； 超时/5xx
 * 未知保留 preparing+sent 标记只查询，不重复 create、不凭一次 404 释放槽。end/freeze 竞态 CAS 落败时不发
 * connecting、不因旧 fence 落败停止新持有者合法活跃实例——仅 DB 已 ending/terminal 或 gate 非 active
 * 才补偿停止该 runtime。
 */
@Component
public class DigitalHumanSessionPromotionWorker {

	private static final Logger log = LoggerFactory.getLogger(DigitalHumanSessionPromotionWorker.class);

	/** §7.2：认领租约 30s（内部常量，不设环境菜单）。 */
	static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
	/** RULE-007：队列总等待截止＝创建时间+60s（age&gt;=60s 到期；created_at 永不更新，不可续命）。 */
	static final Duration QUEUE_DEADLINE = Duration.ofSeconds(60);
	/** §7.2：reserved（已认领未发送）标记前缀；sent 前缀复用 {@link DigitalHumanSessionReaper} 常量。 */
	static final String PROMOTION_RESERVED_PREFIX = "promotion:reserved:";
	/** fake/无超时 transport 的单项调用上限（默认 transport 自带 3~5s 超时，此处兜底）。 */
	private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

	/** 每轮候选快照（无锁读，仅供选择；事务中必须复查）。 */
	private record Candidate(String id, String owner) {
	}

	/** 认领行（claim RETURNING）：dispatchRuntime 需要的全部行权威值。 */
	record ClaimedRow(String id, String owner, String backendId, String profileId, int profileRevision, String state,
			long leaseEpoch, long mediaEpoch, String controllerId, Instant createdAt, Instant expiresAt,
			Instant pausedUntil, Instant leaseExpiresAt, long contentEpoch, String workerId) {

		/** 沿 §6 组装共享 binding 的行视图（readyAt/lastSeq/saveTranscript 不参与 binding，取中性值）。 */
		DigitalHumanSessionService.SessionRowView toView() {
			return new DigitalHumanSessionService.SessionRowView(id, profileId, profileRevision, state, leaseEpoch,
					mediaEpoch, controllerId, createdAt, null, expiresAt, pausedUntil, leaseExpiresAt, 0, false, 1,
					contentEpoch);
		}
	}

	/** 当前行状态（fence 落败后分类：仅 ending/terminal 或 gate 非 active 才补偿停止）。 */
	private record CurrentState(String state, String workerId, boolean gateActive) {
	}

	private static final String FIFO_SQL = """
			SELECT s.id::text AS id, s.owner_account_id AS owner
			FROM dh_session s
			LEFT JOIN intelligence_account_lifecycle g ON g.account_id = s.owner_account_id
			WHERE s.state = 'queued' AND s.deleted_at IS NULL
			  AND s.created_at > :cutoff
			  AND COALESCE(g.state, 'active') = 'active'
			ORDER BY s.created_at, s.id
			LIMIT 1
			""";

	private static final String ACTIVE_COUNT_SQL = """
			SELECT count(*) FILTER (WHERE state <> 'queued' AND state NOT IN ('ended','failed')) AS active
			FROM dh_session
			""";

	private static final String CLAIM_RETURNING = """
			RETURNING id::text AS id, owner_account_id AS owner, backend_id,
			      profile_id::text AS profileId, profile_revision, state, lease_epoch, media_epoch,
			      controller_id::text AS controllerId, created_at, expires_at, paused_until,
			      lease_expires_at, content_epoch, worker_id
			""";

	private static final String EXPIRED_CLAIM_SQL = """
			SELECT id::text AS id, owner_account_id AS owner, worker_id
			FROM dh_session
			WHERE state = 'preparing' AND worker_id LIKE 'promotion:%'
			  AND worker_lease_expires_at IS NOT NULL AND worker_lease_expires_at <= :now
			ORDER BY created_at
			LIMIT 100
			""";

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final Clock clock;
	private final boolean enabled;
	private final DigitalHumanSessionService sessions;
	private final DigitalHumanRuntimeClient runtime;
	private final DigitalHumanEventService events;
	private final AtomicBoolean running = new AtomicBoolean();

	/** 生产构造（§6.8）：enabled 默认 true（application.yml 登记），IT 基座静默。 */
	@org.springframework.beans.factory.annotation.Autowired
	public DigitalHumanSessionPromotionWorker(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanSessionService sessions, DigitalHumanRuntimeClient runtime, DigitalHumanEventService events,
			@Value("${digital-human.promotion.enabled:true}") boolean enabled) {
		this(db, transactions, Clock.systemUTC(), enabled, sessions, runtime, events);
	}

	/** 测试直构入口（固定 Clock + fake runtime/sessions/events）。 */
	DigitalHumanSessionPromotionWorker(DatabaseClient db, TransactionalOperator transactions, Clock clock,
			boolean enabled, DigitalHumanSessionService sessions, DigitalHumanRuntimeClient runtime,
			DigitalHumanEventService events) {
		this.db = db;
		this.transactions = transactions;
		this.clock = clock;
		this.enabled = enabled;
		this.sessions = sessions;
		this.runtime = runtime;
		this.events = events;
	}

	/**
	 * 调度入口：enabled=false 或上一轮未结束（running CAS）时首行返回，不排队（照 reaper 既有范式）。
	 */
	// Retired: never schedule queued sessions into a new runtime. Retain reconciliation helpers for history tests.
	public void runScheduled() {
		if (!enabled || !running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> log.warn("dh promotion round failed", error)).onErrorResume(error -> Mono.empty())
				.doFinally(signal -> running.set(false)).subscribe();
	}

	/**
	 * 一轮＝队列到期收口 → 过期认领恢复/收敛（重领后收敛 connecting 计入晋升数）→ 至多晋升 1 个； 返回本轮成功晋升数。
	 */
	Mono<Integer> runOnce() {
		Instant now = clock.instant();
		return expireAged(now).then(reconcileExpired(now))
				.flatMap(reconciled -> claimAndDispatch(now).map(claimed -> claimed + reconciled));
	}

	// ---------- ① 队列截止（RULE-007：到期未派发进 ending，不可续命） ----------

	private Mono<Void> expireAged(Instant now) {
		OffsetDateTime cutoff = ts(now.minus(QUEUE_DEADLINE));
		// promotion 前缀 preparing（reserved/sent）超截止：占槽未派发 → ending（marker 保留，
		// sent 未决的停止证明规则归 reaper finalize）。
		Mono<Long> preparing = db.sql("""
				UPDATE dh_session SET state = 'ending', state_entered_at = now(), version = version + 1,
				       updated_at = now()
				WHERE state = 'preparing' AND worker_id LIKE 'promotion:%' AND created_at <= :cutoff
				""").bind("cutoff", cutoff).fetch().rowsUpdated();
		// queued 超截止（含回退曾重置 state_entered_at 的行）：created_at 权威，不无限续 60s。
		// 与 reaper 的 queued 60s 判定幂等重叠（双方按 state CAS，谁先谁生效，不复活终态）。
		Mono<Long> queued = db.sql("""
				UPDATE dh_session SET state = 'ending', state_entered_at = now(), version = version + 1,
				       updated_at = now()
				WHERE state = 'queued' AND created_at <= :cutoff
				""").bind("cutoff", cutoff).fetch().rowsUpdated();
		return preparing.then(queued).then();
	}

	// ---------- ② 过期认领恢复（RULE-007：只恢复 promotion 前缀；换 fence 查同 sessionId）
	// ----------

	private Mono<Integer> reconcileExpired(Instant now) {
		return db.sql(EXPIRED_CLAIM_SQL).bind("now", ts(now))
				.map((row, meta) -> new String[]{row.get("id", String.class), row.get("worker_id", String.class)}).all()
				.collectList().flatMap(rows -> {
					if (!rows.isEmpty()) {
						log.info("dh promotion reclaim candidates={} now={}", rows.size(), now);
					}
					return rows.isEmpty()
							? Mono.just(0)
							: Flux.fromIterable(rows).concatMap(item -> reconcileOne(item[0], item[1], now)).reduce(0,
									Integer::sum);
				});
	}

	private Mono<Integer> reconcileOne(String sessionId, String workerId, Instant now) {
		boolean reserved = workerId != null && workerId.startsWith(PROMOTION_RESERVED_PREFIX);
		boolean sent = workerId != null && workerId.startsWith(DigitalHumanSessionReaper.PROMOTION_SENT_PREFIX);
		if (!reserved && !sent) {
			return Mono.just(0); // 无前缀/未知前缀：不由本 worker 恢复（§7.2，不「顺手修」旧行）
		}
		if (reserved) {
			// reserved（未发送）：回 queued（RULE-007「仅 reserved 未发送标记…可回 queued」）；
			// created_at 不动 → 60s 截止不延长；回队后由下一轮 FIFO 正常认领（不计晋升数）。
			return db.sql("""
					UPDATE dh_session SET state = 'queued', state_entered_at = now(), version = version + 1,
					       updated_at = now(), worker_id = NULL, worker_lease_expires_at = NULL
					WHERE id = CAST(:id AS uuid) AND state = 'preparing' AND worker_id = :marker
					""").bind("id", sessionId).bind("marker", workerId).fetch().rowsUpdated().thenReturn(0);
		}
		// sent（已发送未决）：换 fence 刷新租约（保留 sent 阶段，§7.2「重领 CAS 刷新并换 UUID」），
		// 再按 RULE-006 只查询收敛——绝不重复 create；旧 fence 并发写由 WHERE marker CAS 排除。
		String nextMarker = DigitalHumanSessionReaper.PROMOTION_SENT_PREFIX + UUID.randomUUID();
		return db.sql("""
				UPDATE dh_session SET worker_id = :next, worker_lease_expires_at = :lease,
				       version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND state = 'preparing' AND worker_id = :prev
				  AND worker_lease_expires_at <= :now
				""").bind("next", nextMarker).bind("lease", ts(now.plus(CLAIM_LEASE))).bind("id", sessionId)
				.bind("prev", workerId).bind("now", ts(now)).fetch().rowsUpdated().flatMap(reclaimed -> {
					log.info("dh promotion reclaim swapped id={} reclaimed={}", sessionId, reclaimed);
					return reclaimed > 0
							? loadClaimed(sessionId, nextMarker)
									.flatMap(row -> runtime.state(sessionId).timeout(CALL_TIMEOUT)
											.flatMap(state -> classifyAndCommit(row, nextMarker, state)).onErrorResume(
													failure -> onStateProbeFailure(row, nextMarker, failure, false)))
									.switchIfEmpty(Mono.defer(() -> {
										log.warn("dh promotion reclaim row missing id={} marker={}", sessionId,
												nextMarker);
										return Mono.just(0);
									}))
							: Mono.just(0);
				});
	}

	// ---------- ③ 认领并派发（RULE-003：每轮至多 1 个） ----------

	Mono<Integer> claimAndDispatch(Instant now) {
		OffsetDateTime cutoff = ts(now.minus(QUEUE_DEADLINE));
		return db.sql(FIFO_SQL).bind("cutoff", cutoff)
				.map((row, meta) -> new Candidate(row.get("id", String.class), row.get("owner", String.class))).one()
				.flatMap(head -> head == null
						? Mono.just(0)
						: claimTransaction(head, now)
								.flatMap(claimed -> claimed == null ? Mono.just(0) : dispatchClaimed(claimed))
								.defaultIfEmpty(0))
				.defaultIfEmpty(0);
	}

	/**
	 * 认领事务（§7.4 锁序：owner gate FOR SHARE → catalog FOR UPDATE → session 认领）。
	 * 目录缺行/无法读取时不新增认领（恢复与收尾仍进行）；gate 非 active 或候选改变→空（释放事务， 下轮重选）；认领、占槽与 fence
	 * 一起提交。全程无网络调用。
	 */
	private Mono<ClaimedRow> claimTransaction(Candidate head, Instant now) {
		String reservedMarker = PROMOTION_RESERVED_PREFIX + UUID.randomUUID();
		OffsetDateTime cutoff = ts(now.minus(QUEUE_DEADLINE));
		Mono<ClaimedRow> body = gateShareActive(head.owner()).flatMap(gateActive -> {
			if (!gateActive) {
				return Mono.empty(); // gate 非 active → 跳过候选
			}
			// ② catalog 单例锁（容量/队列锁）；缺行 → 空认领（不新增认领，恢复与收尾仍进行）。
			return db.sql("SELECT config_json::text AS config FROM dh_catalog WHERE singleton_id = 1" + " FOR UPDATE")
					.map((row, meta) -> row.get("config", String.class)).one().flatMap(config -> {
						// ③ 容量重查：口径同 create；解析复用 readMaxSessions（1～100 夹限/缺省 1）。
						int maxSessions = DigitalHumanSessionService.readMaxSessions(config);
						return db.sql(ACTIVE_COUNT_SQL).map((row, meta) -> row.get("active", Long.class)).one()
								.defaultIfEmpty(0L).flatMap(active -> {
									if (active >= maxSessions) {
										return Mono.empty(); // 无空槽：不认领
									}
									// ④ FIFO 复查（事务内）：候选改变 → 释放事务下轮重选。
									return db.sql(FIFO_SQL).bind("cutoff", cutoff)
											.map((row, meta) -> row.get("id", String.class)).one()
											.flatMap(current -> head.id().equals(current)
													// ⑤ 认领：queued→preparing + 占槽 + fence 同事务提交。
													? db.sql("""
															UPDATE dh_session SET state = 'preparing',
															       state_entered_at = now(),
															       version = version + 1, updated_at = now(),
															       worker_id = :marker,
															       worker_lease_expires_at = :lease
															WHERE id = CAST(:id AS uuid) AND state = 'queued'
															  AND deleted_at IS NULL
															""" + CLAIM_RETURNING).bind("id", head.id())
															.bind("marker", reservedMarker)
															.bind("lease", ts(now.plus(CLAIM_LEASE)))
															.map(DigitalHumanSessionPromotionWorker::mapClaimed).one()
													: Mono.<ClaimedRow>empty());
								});
					});
		});
		return transactions.transactional(body);
	}

	private Mono<ClaimedRow> loadClaimed(String sessionId, String marker) {
		return db.sql("SELECT id::text AS id, owner_account_id AS owner, backend_id," + """
				profile_id::text AS profileId, profile_revision, state, lease_epoch, media_epoch,
				controller_id::text AS controllerId, created_at, expires_at, paused_until,
				lease_expires_at, content_epoch, worker_id
				FROM dh_session WHERE id = CAST(:id AS uuid) AND state = 'preparing' AND worker_id = :marker
				""").bind("id", sessionId).bind("marker", marker).map(DigitalHumanSessionPromotionWorker::mapClaimed)
				.one();
	}

	private static ClaimedRow mapClaimed(io.r2dbc.spi.Readable row) {
		return new ClaimedRow(row.get("id", String.class), row.get("owner", String.class),
				row.get("backend_id", String.class), row.get("profileId", String.class),
				row.get("profile_revision", Integer.class), row.get("state", String.class),
				row.get("lease_epoch", Long.class), row.get("media_epoch", Long.class),
				row.get("controllerId", String.class), toInstant(row.get("created_at", OffsetDateTime.class)),
				toInstant(row.get("expires_at", OffsetDateTime.class)),
				toInstant(row.get("paused_until", OffsetDateTime.class)),
				toInstant(row.get("lease_expires_at", OffsetDateTime.class)), row.get("content_epoch", Long.class),
				row.get("worker_id", String.class));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}

	private static OffsetDateTime ts(Instant at) {
		return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
	}

	// ---------- 派发与收敛（RULE-006；网络在事务外） ----------

	private Mono<Integer> dispatchClaimed(ClaimedRow row) {
		String fence = row.workerId().substring(PROMOTION_RESERVED_PREFIX.length());
		String sentMarker = DigitalHumanSessionReaper.PROMOTION_SENT_PREFIX + fence;
		return casSent(row.id(), row.workerId(), sentMarker).flatMap(sent -> {
			if (!sent) {
				// fence 已丢（未发网络）：仅 DB 已 ending/terminal 或 gate 非 active 才补偿停止。
				return fenceLostCompensation(row);
			}
			// actor 仅取已锁行 owner（不接受客户端 owner）；backendId 取自本 worker 锁定的行。
			PersonalActor actor = new PersonalActor(row.owner());
			return sessions.dispatchRuntime(actor, row.toView(), row.backendId()).timeout(CALL_TIMEOUT)
					.flatMap(state -> classifyAndCommit(row, sentMarker, state))
					.onErrorResume(failure -> onDispatchFailure(row, sentMarker, failure));
		});
	}

	/** §7.2：网络前 reserved→sent（同 fence UUID）；仅 state 仍 preparing 且 fence 有效才能改。 */
	private Mono<Boolean> casSent(String sessionId, String reservedMarker, String sentMarker) {
		return db
				.sql("UPDATE dh_session SET worker_id = :sent WHERE id = CAST(:id AS uuid) AND state = 'preparing'"
						+ " AND worker_id = :reserved")
				.bind("sent", sentMarker).bind("id", sessionId).bind("reserved", reservedMarker).fetch().rowsUpdated()
				.map(updated -> updated > 0).defaultIfEmpty(false);
	}

	/** RULE-006：绑定匹配且可连接 → connecting CAS+append 同事务；否则 ending+收尾（不复活）。 */
	private Mono<Integer> classifyAndCommit(ClaimedRow row, String sentMarker,
			DigitalHumanRuntimeClient.RuntimeState state) {
		boolean bindingMatch = row.id().equals(state.sessionId()) && state.leaseEpoch() == row.leaseEpoch()
				&& state.mediaEpoch() == row.mediaEpoch();
		boolean connectable = "connecting".equals(state.state()) || "ready".equals(state.state());
		if (bindingMatch && connectable) {
			return commitConnecting(row, sentMarker);
		}
		log.info("dh promotion rejected id={} runtimeState={} bindingMatch={}", row.id(), state.state(), bindingMatch);
		return stopAndToEnding(row.id(), sentMarker);
	}

	/**
	 * RULE-006/008：preparing→connecting CAS 与 {@code session.state} durable append
	 * <b>同一事务</b>； 事务内重新获取 owner gate 并校验
	 * fence/epoch/state；{@code transactions.transactional} 的提交在
	 * 下游看到值<b>之前</b>完成——因此仅在 tx 有值（已提交）时 publishSessionState；回滚/落败走 empty→补偿，绝不发布。
	 */
	private Mono<Integer> commitConnecting(ClaimedRow row, String sentMarker) {
		UUID eventId = UUID.randomUUID();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("state", "connecting");
		payload.put("reasonCode", "queue_promoted");
		payload.put("expiresAt", row.expiresAt() == null ? null : row.expiresAt().toString());
		payload.put("pausedUntil", row.pausedUntil() == null ? null : row.pausedUntil().toString());
		Mono<AppendResult> tx = transactions.transactional(gateShareActive(row.owner()).flatMap(gateActive -> {
			if (!gateActive) {
				return Mono.empty(); // gate 非 active：中止（触发器同款屏障，事件写入不被绕过）
			}
			// fence/epoch/state 校验的单行 CAS；成功即同事务 append（事件随 CAS 一起提交/回滚）。
			return db.sql("""
					UPDATE dh_session SET state = 'connecting', state_entered_at = now(),
					       version = version + 1, updated_at = now(), worker_id = NULL,
					       worker_lease_expires_at = NULL
					WHERE id = CAST(:id AS uuid) AND state = 'preparing' AND worker_id = :marker
					  AND lease_epoch = :epoch
					RETURNING id::text
					""").bind("id", row.id()).bind("marker", sentMarker).bind("epoch", row.leaseEpoch())
					.map((updated, meta) -> updated.get("id", String.class)).one()
					.flatMap(casOk -> casOk == null
							? Mono.<AppendResult>empty()
							: events.append(row.id(), eventId, "session.state", row.leaseEpoch(), payload));
		}));
		return tx.map(appendResult -> {
			// 事务已提交：发布与 durable 回放完全相同的 v/eventId/sessionId/seq/type/payload 帧。
			events.publishSessionState(row.id(), appendResult, payload);
			return 1;
		}).onErrorResume(error -> {
			// append 失败/触发器拒绝：CAS+事件一起回滚，没有 live 通知（warn 带栈，排障需要）。
			log.warn("dh promotion commit failed id={}", row.id(), error);
			return Mono.empty();
		}).defaultIfEmpty(0).flatMap(committed -> committed > 0 ? Mono.just(committed) : fenceLostCompensation(row));
	}

	/** RULE-006：CAS 落败后——仅 DB 已 ending/terminal 或 gate 非 active 才补偿停止该 runtime。 */
	private Mono<Integer> fenceLostCompensation(ClaimedRow row) {
		return currentGateAndState(row.id()).flatMap(current -> {
			boolean gone = "ending".equals(current.state()) || "ended".equals(current.state())
					|| "failed".equals(current.state()) || !current.gateActive();
			if (!gone) {
				// 新 fence/其它路径合法持有（仍 preparing/connecting 等）——不停止新持有者的活跃实例。
				return Mono.just(0);
			}
			return compensateStop(row.id(), current.workerId());
		}).defaultIfEmpty(0);
	}

	/** 补偿停止（网络在事务外）；ending 行在停止证实后清空本人 marker，其余交既有收尾链。 */
	private Mono<Integer> compensateStop(String sessionId, String marker) {
		return runtime.end(sessionId, "promotion_fallback").timeout(CALL_TIMEOUT).flatMap(stopped -> {
			boolean confirmed = ("ended".equals(stopped.state()) || "failed".equals(stopped.state()))
					&& !stopped.cleanupPending();
			return confirmed ? clearMarker(sessionId, marker).thenReturn(0) : Mono.just(0);
		}).onErrorResume(error -> {
			// 停止未知：保留现状（槽不冒充释放），finalize/下轮收敛。
			log.info("dh promotion stop undecided id={} type={}", sessionId, error.getClass().getSimpleName(), error);
			return Mono.just(0);
		});
	}

	/** runtime 返回终态/不匹配绑定：ending + 补偿停止，不复活（RULE-006）。 */
	private Mono<Integer> stopAndToEnding(String sessionId, String sentMarker) {
		return casToEnding(sessionId, sentMarker)
				.then(runtime.end(sessionId, "promotion_reconcile_stop").timeout(CALL_TIMEOUT).flatMap(stopped -> {
					boolean confirmed = ("ended".equals(stopped.state()) || "failed".equals(stopped.state()))
							&& !stopped.cleanupPending();
					return confirmed ? clearMarker(sessionId, sentMarker).then() : Mono.<Void>empty(); // 未证实：marker
																										// 保留，finalize 的
																										// 404 防护继续有效
				}).onErrorResume(error -> {
					log.info("dh promotion stop undecided id={} type={}", sessionId, error.getClass().getSimpleName(),
							error);
					return Mono.<Void>empty();
				})).thenReturn(0);
	}

	private Mono<Long> casToEnding(String sessionId, String sentMarker) {
		return db.sql("""
				UPDATE dh_session SET state = 'ending', state_entered_at = now(), version = version + 1,
				       updated_at = now()
				WHERE id = CAST(:id AS uuid) AND state = 'preparing' AND worker_id = :marker
				""").bind("id", sessionId).bind("marker", sentMarker).fetch().rowsUpdated().defaultIfEmpty(0L);
	}

	/** 停止证实后清空本人 promotion 标记（§7.2：成功/确认停止后清空；仅清 matching marker）。 */
	private Mono<Long> clearMarker(String sessionId, String marker) {
		if (marker == null || !marker.startsWith("promotion:")) {
			return Mono.just(0L);
		}
		return db.sql("UPDATE dh_session SET worker_id = NULL, worker_lease_expires_at = NULL,"
				+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid) AND worker_id = :marker")
				.bind("id", sessionId).bind("marker", marker).fetch().rowsUpdated().defaultIfEmpty(0L);
	}

	// ---------- 派发失败分类（RULE-006：未知只 query，不重复 create） ----------

	private Mono<Integer> onDispatchFailure(ClaimedRow row, String sentMarker, Throwable failure) {
		boolean explicitRejection = failure instanceof IntelligenceException exception && exception.status() == 503;
		log.info("dh promotion dispatch deferred id={} explicitRejection={} type={}", row.id(), explicitRejection,
				failure.getClass().getSimpleName());
		// 单次 state 查询收敛（存在且绑定匹配按成功收敛；未知/404 保守保留）。
		return runtime.state(row.id()).timeout(CALL_TIMEOUT).flatMap(state -> classifyAndCommit(row, sentMarker, state))
				.onErrorResume(stateFailure -> onStateProbeFailure(row, sentMarker, stateFailure, explicitRejection));
	}

	private Mono<Integer> onStateProbeFailure(ClaimedRow row, String sentMarker, Throwable stateFailure,
			boolean explicitRejection) {
		if (isSessionGone(stateFailure)) {
			if (explicitRejection) {
				// 明确未受理（create 入口 503 拒绝）且 state 精确 404 → 确认不存在，回 queued。
				return returnToQueued(row, sentMarker);
			}
			// sent 未决：404 只证明当前没查到，不能排除迟到 create——保留占槽，日志单列未决项。
			log.info("dh promotion undecided id={} phase=sent reason=state_404_after_unknown_create", row.id());
			return Mono.just(0);
		}
		// state 也未知：保留 preparing+sent 标记，只查询（下轮重试），不释放槽。
		log.info("dh promotion undecided id={} phase=sent reason=state_unknown type={}", row.id(),
				stateFailure.getClass().getSimpleName());
		return Mono.just(0);
	}

	/** 明确未受理：preparing→queued（created_at 不动，截止不延长）；用户 end 已先行则只清 marker。 */
	private Mono<Integer> returnToQueued(ClaimedRow row, String sentMarker) {
		Mono<Long> fallback = db.sql("""
				UPDATE dh_session SET state = 'queued', state_entered_at = now(), version = version + 1,
				       updated_at = now(), worker_id = NULL, worker_lease_expires_at = NULL
				WHERE id = CAST(:id AS uuid) AND state = 'preparing' AND worker_id = :marker
				""").bind("id", row.id()).bind("marker", sentMarker).fetch().rowsUpdated().defaultIfEmpty(0L);
		return fallback.flatMap(moved -> moved > 0 ? Mono.just(0) : clearMarker(row.id(), sentMarker).thenReturn(0));
	}

	// ---------- 行/gate 状态读取（fence 分类） ----------

	private Mono<CurrentState> currentGateAndState(String sessionId) {
		return db
				.sql("""
						SELECT s.state, s.worker_id, COALESCE(g.state, 'active') AS gate_state
						FROM dh_session s
						LEFT JOIN intelligence_account_lifecycle g ON g.account_id = s.owner_account_id
						WHERE s.id = CAST(:id AS uuid)
						""").bind("id", sessionId).map((row, meta) -> new CurrentState(row.get("state", String.class),
						row.get("worker_id", String.class), "active".equals(row.get("gate_state", String.class))))
				.one();
	}

	/** 事务内重新获取 owner gate（缺行补行 + FOR SHARE；非 active 返回 false 由调用方中止）。 */
	private Mono<Boolean> gateShareActive(String owner) {
		return db
				.sql("INSERT INTO intelligence_account_lifecycle(account_id) VALUES (:o)"
						+ " ON CONFLICT (account_id) DO NOTHING")
				.bind("o", owner).then()
				.then(db.sql("SELECT state FROM intelligence_account_lifecycle WHERE account_id = :o FOR SHARE")
						.bind("o", owner).map((row, meta) -> row.get("state", String.class)).one())
				.map("active"::equals).defaultIfEmpty(false);
	}

	/**
	 * 精确 404 辨识（同 {@code DigitalHumanSessionReaper} 口径）：IntelligenceException(404,
	 * dh_not_found) 或原始 WebClientResponseException 404 且响应体 code=dh_not_found；不按任意
	 * 404 字符串/消息判断。
	 */
	private static boolean isSessionGone(Throwable error) {
		if (error instanceof IntelligenceException exception) {
			return exception.status() == 404 && "dh_not_found".equals(exception.code());
		}
		if (error instanceof WebClientResponseException response) {
			if (response.getStatusCode().value() != 404) {
				return false;
			}
			try {
				com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
						.readTree(response.getResponseBodyAsString());
				return node.hasNonNull("code") && "dh_not_found".equals(node.get("code").asText());
			} catch (Exception ignored) {
				return false; // 非 JSON/无 code 的 404：不构成证明
			}
		}
		return false;
	}
}
