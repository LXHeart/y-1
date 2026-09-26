package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
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
 */
@Component
public class DigitalHumanSessionReaper {

	private static final Logger log = LoggerFactory.getLogger(DigitalHumanSessionReaper.class);

	/** K01 固定窗口。 */
	static final Duration LEASE_TTL = Duration.ofSeconds(30);
	static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(60);
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(90);
	static final Duration IDLE_TIMEOUT = Duration.ofSeconds(120);

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

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final Clock clock;
	private final boolean enabled;
	private final AtomicBoolean running = new AtomicBoolean();

	@org.springframework.beans.factory.annotation.Autowired
	public DigitalHumanSessionReaper(DatabaseClient db, TransactionalOperator transactions,
			@Value("${digital-human.reaper.enabled:true}") boolean enabled) {
		this(db, transactions, Clock.systemUTC(), enabled);
	}

	/** 测试直构入口（固定 Clock；enabled 恒 true——测试要么直调 {@link #scanExpired}，要么走真调度）。 */
	DigitalHumanSessionReaper(DatabaseClient db, TransactionalOperator transactions, Clock clock) {
		this(db, transactions, clock, true);
	}

	DigitalHumanSessionReaper(DatabaseClient db, TransactionalOperator transactions, Clock clock, boolean enabled) {
		this.db = db;
		this.transactions = transactions;
		this.clock = clock;
		this.enabled = enabled;
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

	Mono<ReaperResult> runOnce() {
		return scanExpired(clock.instant(), 100);
	}

	/** 扫描并回收一批失效会话；CAS 单向推进（不复活终态）。 */
	public Mono<ReaperResult> scanExpired(Instant now, int limit) {
		int bounded = Math.max(1, Math.min(100, limit));
		Mono<List<Claimed>> claim = db.sql(CLAIM_SQL)
				.bind("cutoff", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
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
			return reactor.core.publisher.Flux.fromIterable(claimed).concatMap(item -> {
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

	private record Claimed(String id, String state, String gateState) {
	}
}
