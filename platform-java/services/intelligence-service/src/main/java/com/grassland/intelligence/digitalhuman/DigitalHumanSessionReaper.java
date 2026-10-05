package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionState;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 会话回收器（任务书 #105C C105C-02 / K04）：独立于浏览器的失效回收。
 *
 * <p>
 * 由 Spring {@code @Scheduled} 每 5s（可配）驱动 {@link #runScheduled}（任务书 #105fix-1
 * C105X-01 接线）；{@link #scanExpired} 供测试注入固定 {@link Clock} 直调（不真实 sleep 45s，真实
 * kill 归 H）。一次最多 {@code limit}（≤100）场：{@code FOR UPDATE SKIP LOCKED} 领取， CAS
 * 收尾在事务内，runtime end 在事务后（不持事务等待网络）。条件（K01/K04/K13.4）：
 * <ul>
 * <li>queued 60s / connecting 90s 超时（state_entered_at 起）→ ending/failed；</li>
 * <li>paused/reconnecting 的 resume 窗口（paused_until+30s / 最后心跳+30s 再 +30s）到期 →
 * ending；</li>
 * <li>浏览器失联：活动场 last_browser_heartbeat_at+30s → reconnecting（记录）；再 +30s 未回 →
 * ending；</li>
 * <li>idle（last_activity_at+idle 120s）与总时长（expires_at）→ ending。</li>
 * </ul>
 * 只把会话推进 ending/failed/reconnecting（单向），绝不复活终态、不动账务（pending 独立）。
 *
 * <p>
 * ending 的安全收尾（任务书 105-fix-2 RULE-002 / REQ-001）：{@link #runOnce} 在
 * {@link #scanExpired} 之后调用 {@link #finalizeEnding}——只取 ending 且
 * {@code state_entered_at < now-grace}（宽限默认 15s，§6.8），每轮最多 100 个、逐项串行。
 * runtime.end 在事务外且单项最多 5s；返回 state=ended/failed 且 cleanupPending=false，或该
 * session 精确返回 404 dh_not_found（{@link IntelligenceException} 或原始
 * {@link WebClientResponseException}，不按任意 404 字符串判断）且不存在已发送未决晋升标记 （§7.2
 * {@code promotion:sent:} 前缀——该 404 只证明当前没查到，不能排除迟到 create），才允许
 * ending→ended。503/超时/未知异常记录类型并留 ending 下轮重试，不吞成成功、不影响后续行。CAS 匹配读取的
 * state_entered_at/version，写 ended_at（已有值不覆盖）、state_entered_at、updated_at、
 * version+1；不清理账务/cleanup_pending。并发可重发幂等 end，DB 终态只生效一次。
 */
@Component
public class DigitalHumanSessionReaper {

	private static final Logger log = LoggerFactory.getLogger(DigitalHumanSessionReaper.class);

	/** K01 固定窗口。 */
	static final Duration LEASE_TTL = Duration.ofSeconds(30);
	static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(60);
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(90);
	static final Duration IDLE_TIMEOUT = Duration.ofSeconds(120);

	/** ending→ended 收尾宽限（任务书 105-fix-2 §6.8：默认 15s，1～60 整数，非法启动失败）。 */
	static final Duration FINALIZE_GRACE_DEFAULT = Duration.ofSeconds(15);
	/** RULE-002：finalize 单项 runtime.end 最多 5s（含排队/响应）。 */
	private static final Duration END_CALL_TIMEOUT = Duration.ofSeconds(5);
	/** §7.2 晋升「已发送未决」标记前缀：finalize 见到它时 404 不构成停止证明。 */
	static final String PROMOTION_SENT_PREFIX = "promotion:sent:";

	/** K07.1 ReaperResult：批处理诊断，不含用户内容。 */
	public record ReaperResult(int scanned, int claimed, int succeeded, int failed, int pending) {
	}

	private static final String CLAIM_SQL = """
			SELECT s.id::text AS id, s.state,
			       COALESCE(g.state, 'active') AS gate_state
			FROM dh_session s
			LEFT JOIN intelligence_account_lifecycle g ON g.account_id = s.owner_account_id
			WHERE s.state IN ('queued','connecting','ready','listening','responding','paused','reconnecting')
			  AND (
			    (s.state = 'queued' AND s.state_entered_at < :cutoff - INTERVAL '60 seconds')
			    OR (s.state = 'connecting' AND s.state_entered_at < :cutoff - INTERVAL '90 seconds')
			    OR (s.state IN ('paused','reconnecting')
			        AND COALESCE(s.last_browser_heartbeat_at, s.state_entered_at)
			            < :cutoff - INTERVAL '60 seconds')
			    OR (s.state IN ('ready','listening','responding')
			        AND COALESCE(s.last_browser_heartbeat_at, s.state_entered_at)
			            < :cutoff - INTERVAL '30 seconds')
			    OR (s.expires_at IS NOT NULL AND s.expires_at < :cutoff)
			    OR (s.last_activity_at IS NOT NULL AND s.last_activity_at < :cutoff - INTERVAL '120 seconds')
			  )
			ORDER BY s.created_at
			LIMIT :limit
			FOR UPDATE OF s SKIP LOCKED
			""";

	/** RULE-002 finalize 扫描：仅 ending 且严格早于 cutoff（now−grace）；无锁（网络在事务外，CAS 兜底）。 */
	private static final String FINALIZE_SQL = """
			SELECT s.id::text AS id, s.state_entered_at, s.version, s.worker_id
			FROM dh_session s
			WHERE s.state = 'ending' AND s.state_entered_at < :cutoff
			ORDER BY s.created_at
			LIMIT :limit
			""";

	/**
	 * RULE-002 CAS：匹配读取的 state_entered_at/version；ended_at 已有值不覆盖；不清理账务/
	 * cleanup_pending。并发 finalize 只有第一个 CAS 生效（DB 终态只推进一次）。
	 */
	private static final String CAS_ENDED_SQL = """
			UPDATE dh_session
			SET state = 'ended', ended_at = COALESCE(ended_at, :now),
			    state_entered_at = :now, updated_at = :now, version = version + 1
			WHERE id = CAST(:id AS uuid) AND state = 'ending'
			  AND state_entered_at = :entered AND version = :version
			""";

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final Clock clock;
	private final boolean enabled;
	private final DigitalHumanRuntimeClient runtime;
	private final Duration finalizeGrace;
	private final AtomicBoolean running = new AtomicBoolean();

	/** 生产构造（任务书 105-fix-2 §11 C-02 步骤1）：注入 runtime 客户端与校验后的 finalize 宽限。 */
	@org.springframework.beans.factory.annotation.Autowired
	public DigitalHumanSessionReaper(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanRuntimeClient runtime, @Value("${digital-human.reaper.enabled:true}") boolean enabled,
			@Value("${digital-human.reaper.finalize-grace-seconds:15}") int finalizeGraceSeconds) {
		this(db, transactions, Clock.systemUTC(), enabled, runtime, validatedGrace(finalizeGraceSeconds));
	}

	/** 测试直构入口（固定 Clock；enabled 恒 true——测试要么直调 {@link #scanExpired}，要么走真调度）。 */
	DigitalHumanSessionReaper(DatabaseClient db, TransactionalOperator transactions, Clock clock) {
		this(db, transactions, clock, true);
	}

	DigitalHumanSessionReaper(DatabaseClient db, TransactionalOperator transactions, Clock clock, boolean enabled) {
		// 未配置 runtime 的 fail-closed 客户端（end → 503）：既有测试只扫不放，finalize 保持 ending。
		this(db, transactions, clock, enabled, new DigitalHumanRuntimeClient("", "", "", "", ""),
				FINALIZE_GRACE_DEFAULT);
	}

	/** 测试直构全参入口（105-fix-2）：fake runtime + 固定宽限。 */
	DigitalHumanSessionReaper(DatabaseClient db, TransactionalOperator transactions, Clock clock, boolean enabled,
			DigitalHumanRuntimeClient runtime, Duration finalizeGrace) {
		this.db = db;
		this.transactions = transactions;
		this.clock = clock;
		this.enabled = enabled;
		this.runtime = runtime;
		this.finalizeGrace = validatedGrace(finalizeGrace);
	}

	/** §6.8：finalize 宽限 1～60 整秒，非法启动失败（不能负宽限提前释放）。 */
	static Duration validatedGrace(int seconds) {
		if (seconds < 1 || seconds > 60) {
			throw new IllegalArgumentException(
					"digital-human.reaper.finalize-grace-seconds 必须为 1～60 整数，实际: " + seconds);
		}
		return Duration.ofSeconds(seconds);
	}

	static Duration validatedGrace(Duration grace) {
		if (grace == null || grace.isNegative() || grace.isZero() || grace.compareTo(Duration.ofSeconds(60)) > 0
				|| grace.toMillis() % 1000 != 0) {
			throw new IllegalArgumentException(
					"digital-human.reaper.finalize-grace-seconds 必须为 1～60 的整秒，实际: " + (grace == null ? "null" : grace));
		}
		return grace;
	}

	/**
	 * 调度入口（任务书 #105fix-1 C105X-01）：5s 周期（可配）驱动；enabled=false 或上一轮未结束 （running
	 * CAS）时首行返回，不排队。照 {@code PersonalDataErasureWorker} 既有范式。
	 */
	@Scheduled(fixedDelayString = "${digital-human.reaper.poll-interval-ms:5000}")
	public void runScheduled() {
		if (!enabled || !running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> log.warn("dh reaper scan failed", error)).onErrorResume(error -> Mono.empty())
				.doFinally(signal -> running.set(false)).subscribe();
	}

	/**
	 * 一轮 = 先 scanExpired（推进 ending/failed/reconnecting，原计数语义不变），再 finalizeEnding
	 * （任务书 105-fix-2 §11 C-02 步骤1）；返回值仍为 scanExpired 的批结果。
	 */
	Mono<ReaperResult> runOnce() {
		Instant now = clock.instant();
		return scanExpired(now, 100).flatMap(result -> finalizeEnding(now, 100).doOnNext(finalized -> {
			if (finalized > 0) {
				log.info("dh reaper finalized ending sessions count={}", finalized);
			}
		}).thenReturn(result));
	}

	/** 扫描并回收一批失效会话；CAS 单向推进（不复活终态）。 */
	public Mono<ReaperResult> scanExpired(Instant now, int limit) {
		int bounded = Math.max(1, Math.min(100, limit));
		Mono<List<Claimed>> claim = db.sql(CLAIM_SQL).bind("cutoff", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
				.bind("limit", bounded).map((row, meta) -> new Claimed(row.get("id", String.class),
						row.get("state", String.class), row.get("gate_state", String.class)))
				.all().collectList();
		// 认领在事务内（FOR UPDATE SKIP LOCKED 锁定候选），单项 CAS 在事务外独立执行——
		// 单条语句失败只影响自身（记 pending 下轮重扫），不会把整批事务拖进 ROLLBACK。
		Mono<List<Claimed>> claimTx = transactions.transactional(claim);
		return claimTx.flatMap(claimed -> {
			if (claimed.isEmpty()) {
				return Mono.just(new ReaperResult(0, 0, 0, 0, 0));
			}
			// 逐场单向 CAS（不复活终态）。connecting 超时=failed；活动心跳刚过期=reconnecting
			// （保留 30s 恢复窗；冻结/清理账号只放行单向终态——V88 守卫，直接 ending）；其余→ending。
			return Flux.fromIterable(claimed).concatMap(item -> {
				String target = switch (SessionState.valueOf(item.state())) {
					case connecting -> "failed";
					case ready, listening, responding -> "active".equals(item.gateState()) ? "reconnecting" : "ending";
					default -> "ending";
				};
				String sql = "failed".equals(target)
						? "UPDATE dh_session SET state = 'failed', error_code = 'dh_connect_timeout',"
								+ " cleanup_pending = true, state_entered_at = now(), version = version + 1,"
								+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND state = :expected"
						: "UPDATE dh_session SET state = '" + target + "', state_entered_at = now(),"
								+ " version = version + 1, updated_at = now()"
								+ " WHERE id = CAST(:id AS uuid) AND state = :expected";
				Mono<Integer> attempt = db.sql(sql).bind("id", item.id()).bind("expected", item.state()).fetch()
						.rowsUpdated().map(updated -> updated > 0 ? ("failed".equals(target) ? 2 : 1) : 0)
						.defaultIfEmpty(0);
				if ("reconnecting".equals(target)) {
					// 屏障拒绝（如并发冻结）时回落终态 ending。
					attempt = attempt.onErrorResume(error -> db
							.sql("UPDATE dh_session SET state = 'ending', state_entered_at = now(),"
									+ " version = version + 1, updated_at = now()"
									+ " WHERE id = CAST(:id AS uuid) AND state = :expected")
							.bind("id", item.id()).bind("expected", item.state()).fetch().rowsUpdated()
							.map(updated -> updated > 0 ? 1 : 0).defaultIfEmpty(0));
				}
				// 单项失败不拖垮整批（记 pending，下轮重扫）。
				return attempt.onErrorResume(error -> {
					org.slf4j.LoggerFactory.getLogger(DigitalHumanSessionReaper.class).debug(
							"reaper item skipped id={} target={} type={}", item.id(), target,
							error.getClass().getSimpleName());
					return Mono.just(0);
				});
			}).reduce(new int[]{0, 0}, (acc, outcome) -> {
				if (outcome == 1) {
					acc[0]++;
				} else if (outcome == 2) {
					acc[1]++;
				}
				return acc;
			}).map(counts -> new ReaperResult(claimed.size(), claimed.size(), counts[0], counts[1], 0));
		});
	}

	/**
	 * ending 安全收尾（任务书 105-fix-2 RULE-002 / REQ-001；§6 签名）。固定 {@code now/cutoff}
	 * 查询：只取 ending 且 {@code state_entered_at < now−grace}，每轮最多 {@code limit}（夹限
	 * 1～100）个、逐项串行。逐项网络结束/精确 404 辨识，确认后 CAS；单项失败继续下一项。返回本轮 ending→ended
	 * 的行数。网络调用不在任何 DB 事务/行锁内。
	 */
	public Mono<Integer> finalizeEnding(Instant now, int limit) {
		int bounded = Math.max(1, Math.min(100, limit));
		OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
		Mono<List<FinalizeCandidate>> scan = db.sql(FINALIZE_SQL).bind("cutoff", nowTs.minus(finalizeGrace))
				.bind("limit", bounded)
				.map((row, meta) -> new FinalizeCandidate(row.get("id", String.class),
						row.get("state_entered_at", OffsetDateTime.class), row.get("version", Integer.class),
						row.get("worker_id", String.class)))
				.all().collectList();
		return scan.flatMap(candidates -> Flux.fromIterable(candidates).concatMap(item -> finalizeOne(item, nowTs))
				.reduce(0, Integer::sum));
	}

	/** 单项收尾：runtime.end 在事务外、单项 5s 上限；确认停止才 CAS，失败/未知留 ending 不影响后续。 */
	private Mono<Integer> finalizeOne(FinalizeCandidate item, OffsetDateTime now) {
		return runtime.end(item.id(), "reaper_finalize").timeout(END_CALL_TIMEOUT).flatMap(state -> {
			if (("ended".equals(state.state()) || "failed".equals(state.state())) && !state.cleanupPending()) {
				return casEnded(item, now);
			}
			// 成功 HTTP 但 cleanupPending=true 或非终态回报：句柄未清，不冒充释放，下轮重试。
			log.debug("dh finalize deferred id={} state={} cleanupPending={}", item.id(), state.state(),
					state.cleanupPending());
			return Mono.just(0);
		}).onErrorResume(error -> {
			if (isSessionGone(error)) {
				if (item.workerId() != null && item.workerId().startsWith(PROMOTION_SENT_PREFIX)) {
					// §7.2 已发送未决晋升标记：404 只证明当前没查到，不能排除迟到 create——保留 ending
					// 直至查到并停止原实例或取得原派发明确拒绝结果。
					log.info("dh finalize deferred id={} reason=promotion_sent_marker_undecided", item.id());
					return Mono.just(0);
				}
				// runtime 已无此 session（精确 404 dh_not_found）→ 停止事实确认，安全释放。
				return casEnded(item, now);
			}
			// 503/超时/未知异常：记录类型并留 ending 下轮重试，不吞成成功、不影响后续行。
			log.info("dh finalize deferred id={} type={}", item.id(), error.getClass().getSimpleName());
			return Mono.just(0);
		});
	}

	/**
	 * 精确 404 辨识（任务书 105-fix-2 §11 C-02 步骤2）：真实 transport 把 runtime 4xx 映射为
	 * {@link IntelligenceException}（404 + dh_not_found）；fake/state 路径可能抛原始
	 * {@link WebClientResponseException}——两种形态都要求 status=404 且响应体
	 * code=dh_not_found， 不按任意 404 字符串/消息判断。
	 */
	private static boolean isSessionGone(Throwable error) {
		if (error instanceof IntelligenceException ie) {
			return ie.status() == 404 && "dh_not_found".equals(ie.code());
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
				// 非 JSON/无 code 的 404：不构成停止证明
				return false;
			}
		}
		return false;
	}

	private Mono<Integer> casEnded(FinalizeCandidate item, OffsetDateTime now) {
		return db.sql(CAS_ENDED_SQL).bind("now", now).bind("id", item.id()).bind("entered", item.enteredAt())
				.bind("version", item.version()).fetch().rowsUpdated().map(updated -> updated > 0 ? 1 : 0)
				.defaultIfEmpty(0).onErrorResume(error -> {
					// CAS/触发器拒绝（如并发冻结屏障）：留 ending，下轮按当时状态重新判定。
					log.info("dh finalize cas deferred id={} type={}", item.id(), error.getClass().getSimpleName());
					return Mono.just(0);
				});
	}

	private record Claimed(String id, String state, String gateState) {
	}

	/** finalize 候选快照：CAS 基准（entered/version）与晋升标记位。 */
	private record FinalizeCandidate(String id, OffsetDateTime enteredAt, Integer version, String workerId) {
	}
}
