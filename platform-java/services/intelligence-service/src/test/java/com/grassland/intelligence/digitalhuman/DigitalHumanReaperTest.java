package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 回收器测试（任务书 #105C C105C-02 / TC105C-02-03 + 105-fix-2 TC-C02-001～004）。
 *
 * <p>
 * 固定时钟推进 + fake transport（网络隔断语义：runtime 不可达不阻塞回收）；不真实 sleep 45 秒（真实 kill 归
 * H）。断言：心跳失联先 reconnecting、窗口过后 ending；slot 释放（owner 活动唯一允许新会话）；费用/账务
 * 不在本卡（pending 独立，D 阶段结算）。
 *
 * <p>
 * 105-fix-2（W04 固定 Clock 范式 / §12.2 F-J）：finalizeEnding 用固定 UTC
 * {@code 2026-10-02T00:00:00Z} 与显式种子 state_entered_at 独立断言宽限/夹限/CAS；fake 只替换
 * RuntimeClient.Transport，按 sessionId 脚本化 end 回报并记录调用，屏障用 Sinks.One /
 * CountDownLatch（非阻塞，不用任意 sleep 碰运气）。TC-C02-001 的调度子用例经
 * {@code runScheduled()}（真实 @Scheduled 入口）驱动，不直调生产扫描方法；默认 5s 周期由
 * DigitalHumanWorkerSchedulingIT 反射守卫固定（RULE-009 ending 收尾 ≤25s = 15s 宽限 + 5s
 * 调度 + 5s 调用）。TC-C02-004 的跨晋升子场景（promotion create 在途时用户 end）归 C-03/V-003。
 */
class DigitalHumanReaperTest extends IntelligenceItSupport {

	/** §12.2 F-J 固定时刻。 */
	private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

	@Autowired
	private DatabaseClient db;

	@Autowired
	private ApplicationContext context;

	private final String account = "dh-reap-" + UUID.randomUUID();

	@BeforeEach
	void clearSharedSessions() {
		// 共享容器跨类自愈（基座对 ai_run 同款语义）：回收器扫描是全局的，别类残留的
		// dh_session/dh_operation 行会污染 claimed 计数。
		db.sql("DELETE FROM dh_operation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then()).block(Duration.ofSeconds(10));
	}

	// ---------- TC105C-02-03 既有回归（W18 兼容构造器面保持不变） ----------

	@Test
	void tc105c_02_03_browserLossReleasesWithinWindowWithoutRealSleep() {
		// ready 场：最后心跳 31s 前（>30s 失联阈值）→ 先 reconnecting（保留 30s 恢复窗）。
		String sessionId = seedSession("ready", java.time.Duration.ofSeconds(31));
		var reaper = reaper(Instant.now().plusSeconds(31));
		var first = reaper.scanExpired(Instant.now().plusSeconds(31), 100).block(Duration.ofSeconds(10));
		assertThat(first.claimed()).isGreaterThanOrEqualTo(1);
		assertThat(state(sessionId)).isEqualTo("reconnecting");

		// 再推 31s（窗口外）：reconnecting → ending；再推进后终态保持（ending 由 finalize 收口）。
		reaper(Instant.now().plusSeconds(62)).scanExpired(Instant.now().plusSeconds(62), 100)
				.block(Duration.ofSeconds(10));
		assertThat(state(sessionId)).isEqualTo("ending");

		// queued 60s 超时 → ending；connecting 90s 超时 → failed（cleanup_pending 置位）。
		// 每 seed 独立 owner（owner 活动唯一约束）。
		String queued = seedSession("queued", java.time.Duration.ofSeconds(120), account + "-q");
		reaper(Instant.now().plusSeconds(120)).scanExpired(Instant.now().plusSeconds(120), 100)
				.block(Duration.ofSeconds(10));
		assertThat(state(queued)).isEqualTo("ending");
		String connecting = seedSession("connecting", java.time.Duration.ofSeconds(150), account + "-c");
		reaper(Instant.now().plusSeconds(150)).scanExpired(Instant.now().plusSeconds(150), 100)
				.block(Duration.ofSeconds(10));
		assertThat(state(connecting)).isEqualTo("failed");
		assertThat(cleanupPending(connecting)).isTrue();

		// slot 释放：ending 仍占 owner 活动位（K05：非 ended/failed 均算活动）；只有推进到
		// ended 后同 owner 才可再建（failed 同理由 connecting 场展示）。
		assertThatThrownBy(() -> seedSession("ready", java.time.Duration.ZERO, account + "-q"))
				.hasMessageContaining("uq_dh_session_owner_active");
		db.sql("UPDATE dh_session SET state = 'ended', ended_at = now(), version = version + 1"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", queued).then().block(Duration.ofSeconds(5));
		String revived = seedSession("ready", java.time.Duration.ZERO, account + "-q");
		assertThat(state(revived)).isEqualTo("ready");
		cleanupAll();
	}

	// ---------- TC-C02-001：收尾与时间口径 ----------

	@Test
	void tcFix2_02_01_finalizeConfirmPersistsEndedOnceAndReleasesCapacity() {
		Instant now = NOW;
		String owner = account + "-f1";
		String ending = seedEnding(ts(now.minusSeconds(16)), ts(now.minusSeconds(16)), owner, null);

		// 对照：ending 仍占 owner 活动位——同 owner 不能再建（uq_dh_session_owner_active）。
		assertThatThrownBy(() -> seedAt("ready", ts(now), ts(now), owner, null))
				.hasMessageContaining("uq_dh_session_owner_active");
		assertThat(activeCount()).as("收尾前 active（create 容量口径）").isEqualTo(1);

		// 固定 clock 直调 finalizeEnding（真实 runtime.end 路径：fake 默认返回
		// ended/cleanupPending=false）。
		Integer finalized = reaper(now, new StubTransport()).finalizeEnding(now, 100).block(Duration.ofSeconds(10));
		assertThat(finalized).isEqualTo(1);

		Row after = row(ending);
		assertThat(after.state()).isEqualTo("ended");
		assertThat(after.endedAt()).as("ended_at 非空").isNotNull();
		assertThat(after.version()).as("version 仅推进一次（种子默认 1）").isEqualTo(2);
		assertThat(after.enteredAt().toInstant()).isEqualTo(now); // state_entered_at 同步推进
		assertThat(activeCount()).as("原 active 减 1").isZero();

		// 释放后同 owner 可再插入（新活动会话）。
		String revived = seedAt("ready", ts(now), ts(now), owner, null);
		assertThat(row(revived).state()).isEqualTo("ready");

		// 重复扫描不复活/不增终态版本。
		Instant later = now.plusSeconds(30);
		Integer again = reaper(later, new StubTransport()).finalizeEnding(later, 100).block(Duration.ofSeconds(10));
		assertThat(again).isZero();
		assertThat(row(ending).version()).as("终态版本不再推进").isEqualTo(2);
		assertThat(row(ending).endedAt()).isNotNull();
		cleanupAll();
	}

	@Test
	void tcFix2_02_01_schedulerEntryDrivesFinalizeWithoutDirectScanCall() throws Exception {
		// 独立调度子用例：真实墙钟种子（宽限 15s 已过 20s），只经 @Scheduled 入口 runScheduled()
		// 驱动（不直调 scanExpired/finalizeEnding）；轮询上限有界（非任意 sleep 碰运气）。
		String owner = account + "-sched";
		Instant seededAt = Instant.now();
		String ending = seedAt("ending", ts(seededAt.minusSeconds(20)), ts(seededAt.minusSeconds(20)), owner, null);
		StubTransport transport = new StubTransport();
		DigitalHumanSessionReaper scheduled = new DigitalHumanSessionReaper(db, transactions(), Clock.systemUTC(), true,
				runtime(transport), DigitalHumanSessionReaper.FINALIZE_GRACE_DEFAULT);
		scheduled.runScheduled();

		long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
		while (System.currentTimeMillis() < deadline) {
			Row current = row(ending);
			if ("ended".equals(current.state())) {
				assertThat(transport.endCalls.get(ending)).as("runtime.end 恰一次").isEqualTo(1);
				assertThat(current.endedAt()).isNotNull();
				assertThat(current.version()).isEqualTo(2);
				cleanupAll();
				return;
			}
			Thread.sleep(100);
		}
		fail("15s 内调度入口未收尾 ending 会话 id=" + ending + " state=" + row(ending).state());
	}

	// ---------- TC-C02-002：宽限、批量、配置边界 ----------

	@Test
	void tcFix2_02_02_graceBoundaryLimitClampAndConfigGuard() {
		Instant now = NOW;
		// 严格小于 cutoff（now−15s）：14999ms/15000ms 不可处理，15001ms 可处理；failed 终态不动。
		String r14999 = seedEnding(ts(now.minusMillis(14999)), ts(now.minusSeconds(60)), account + "-g1", null);
		String r15000 = seedEnding(ts(now.minusMillis(15000)), ts(now.minusSeconds(59)), account + "-g2", null);
		String r15001 = seedEnding(ts(now.minusMillis(15001)), ts(now.minusSeconds(58)), account + "-g3", null);
		String failedRow = seedAt("failed", ts(now.minusSeconds(60)), ts(now.minusSeconds(60)), account + "-g4", null);
		Row failedBefore = row(failedRow);

		Integer finalized = reaper(now, new StubTransport()).finalizeEnding(now, 100).block(Duration.ofSeconds(10));
		assertThat(finalized).as("仅 15001ms 行可处理").isEqualTo(1);

		Row a = row(r14999);
		Row b = row(r15000);
		assertThat(a.state()).isEqualTo("ending");
		assertThat(a.version()).as("未过期行零字段变化").isEqualTo(1);
		assertThat(a.endedAt()).isNull();
		assertThat(a.enteredAt().toInstant()).isEqualTo(now.minusMillis(14999));
		assertThat(b.state()).isEqualTo("ending");
		assertThat(b.version()).isEqualTo(1);
		assertThat(b.endedAt()).isNull();
		assertThat(b.enteredAt().toInstant()).isEqualTo(now.minusMillis(15000));
		Row c = row(r15001);
		assertThat(c.state()).isEqualTo("ended");
		assertThat(c.endedAt()).isNotNull();
		Row f = row(failedRow);
		assertThat(f.state()).as("terminal 不动").isEqualTo("failed");
		assertThat(f.version()).isEqualTo(failedBefore.version());
		assertThat(f.endedAt()).isNull();

		// 分组隔离：删去上组残留的 eligible ending 行，保证夹限组候选集合确定。
		db.sql("DELETE FROM dh_session WHERE id IN (CAST(:a AS uuid), CAST(:b AS uuid))").bind("a", r14999)
				.bind("b", r15000).then().block(Duration.ofSeconds(5));

		// limit 夹限：0→1（仅 created_at 更早行）、101→100（本轮候选全收）。
		String l1 = seedEnding(ts(now.minusSeconds(30)), ts(now.minusSeconds(30)), account + "-l1", null);
		String l2 = seedEnding(ts(now.minusSeconds(30)), ts(now.minusSeconds(29)), account + "-l2", null);
		Integer clamped = reaper(now, new StubTransport()).finalizeEnding(now, 0).block(Duration.ofSeconds(10));
		assertThat(clamped).as("limit=0 夹到 1").isEqualTo(1);
		assertThat(row(l1).state()).isEqualTo("ended");
		assertThat(row(l2).state()).isEqualTo("ending");

		// 再隔离：删去 l2（仍 ending 且 eligible），101 组候选仅 m1/m2。
		db.sql("DELETE FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", l2).then()
				.block(Duration.ofSeconds(5));

		String m1 = seedEnding(ts(now.minusSeconds(30)), ts(now.minusSeconds(28)), account + "-m1", null);
		String m2 = seedEnding(ts(now.minusSeconds(30)), ts(now.minusSeconds(27)), account + "-m2", null);
		Integer wide = reaper(now, new StubTransport()).finalizeEnding(now, 101).block(Duration.ofSeconds(10));
		assertThat(wide).as("limit=101 夹到 100，候选全收").isEqualTo(2);
		assertThat(row(m1).state()).isEqualTo("ended");
		assertThat(row(m2).state()).isEqualTo("ended");

		// grace 0/61 非法配置 → 构造即抛（Spring 启动同款失败路径），不能负宽限提前释放。
		assertThatThrownBy(() -> new DigitalHumanSessionReaper(db, transactions(), Clock.systemUTC(), true,
				runtime(new StubTransport()), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DigitalHumanSessionReaper(db, transactions(), Clock.systemUTC(), true,
				runtime(new StubTransport()), Duration.ofSeconds(61))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new DigitalHumanSessionReaper(db, transactions(), runtime(new StubTransport()), true, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new DigitalHumanSessionReaper(db, transactions(), runtime(new StubTransport()), true, 61))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(new DigitalHumanSessionReaper(db, transactions(), Clock.systemUTC(), true,
				runtime(new StubTransport()), Duration.ofSeconds(60))).as("边界内 60s 可构造").isNotNull();
		assertThat(new DigitalHumanSessionReaper(db, transactions(), Clock.systemUTC(), true,
				runtime(new StubTransport()), Duration.ofSeconds(1))).as("边界内 1s 可构造").isNotNull();
		// 生产装配：yml 默认 15s 注入 context bean（W20 登记 + 构造参数校验）。
		assertThat(fieldOf(context.getBean(DigitalHumanSessionReaper.class), "finalizeGrace"))
				.isEqualTo(Duration.ofSeconds(15));
		cleanupAll();
	}

	// ---------- TC-C02-003：停止未知不释放、单项故障隔离 ----------

	@Test
	void tcFix2_02_03_unknownStopKeepsEndingAndItemFailuresIsolate() {
		Instant now = NOW;
		StubTransport transport = new StubTransport();
		// created_at 严格递增 → finalize 逐项顺序 a→b→c→d→e→g→h→i（单项失败不饿死后续行）。
		String a = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(60)), account + "-x1", null);
		String b = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(59)), account + "-x2", null);
		String c = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(58)), account + "-x3", null);
		String d = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(57)), account + "-x4", null);
		String e = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(56)), account + "-x5", null);
		String g = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(55)), account + "-x6",
				DigitalHumanSessionReaper.PROMOTION_SENT_PREFIX + UUID.randomUUID());
		String h = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(54)), account + "-x7", null);
		String i = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(53)), account + "-x8", null);

		transport.endScript.put(a,
				() -> Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "注入 503")));
		transport.endScript.put(b, () -> Mono.error(new IntelligenceException(404, "dh_not_found", "会话不存在。")));
		// d：成功 HTTP 但 cleanupPending=true——句柄未清，不冒充释放。
		transport.endScript.put(d, () -> Mono
				.just(new DigitalHumanRuntimeClient.RuntimeState(d, "stub", 1, 1, "ended", null, null, false, true)));
		transport.endScript.put(e, Mono::never); // 未知超时（单项 5s 上限内无回报）
		// g：精确 404 但带 promotion:sent: 未决标记——404 只证明当前没查到，不能排除迟到 create。
		transport.endScript.put(g, () -> Mono.error(new IntelligenceException(404, "dh_not_found", "会话不存在。")));
		// h：原始 WebClientResponseException 404 但响应体无 dh_not_found code——不按任意 404 判断。
		transport.endScript.put(h, () -> Mono.error(raw404("{\"error\":\"not found\"}")));
		// i：原始 WebClientResponseException 404 且响应体 code=dh_not_found——构成停止证明。
		transport.endScript.put(i, () -> Mono.error(raw404("{\"code\":\"dh_not_found\",\"error\":\"会话不存在。\"}")));

		long startedAt = System.currentTimeMillis();
		Integer finalized = reaper(now, transport).finalizeEnding(now, 100).block(Duration.ofSeconds(30));
		long elapsedMillis = System.currentTimeMillis() - startedAt;

		assertThat(finalized).as("B/C/I 确认停止收尾").isEqualTo(3);
		// e 的 Mono.never 消耗真实 5s 超时上限；批处理仍收口其余行（不饿死）且单项上限生效。
		assertThat(elapsedMillis).as("Mono.never 单项受 5s 上限约束").isLessThan(15000L);
		assertThat(transport.endCalls.get(e)).as("超时项确实发起过 end").isEqualTo(1);

		assertThat(row(a).state()).as("503 未知：保持 ending 占槽").isEqualTo("ending");
		assertThat(row(a).version()).isEqualTo(1);
		assertThat(row(d).state()).as("cleanupPending=true：不释放").isEqualTo("ending");
		assertThat(row(e).state()).as("未知超时：保持 ending").isEqualTo("ending");
		assertThat(row(g).state()).as("晋升 sent 标记未决：404 不构成停止证明").isEqualTo("ending");
		assertThat(row(h).state()).as("非 dh_not_found 的 404：不构成停止证明").isEqualTo("ending");
		assertThat(row(b).state()).isEqualTo("ended");
		assertThat(row(b).endedAt()).isNotNull();
		assertThat(row(c).state()).isEqualTo("ended");
		assertThat(row(i).state()).isEqualTo("ended");

		// 恢复 A 为已停止后再一轮：终态确认 + end 幂等重发（e 改快错避免二次真实 5s 等待）。
		transport.endScript.remove(a);
		transport.endScript.put(e,
				() -> Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "注入 503")));
		Instant later = now.plusSeconds(30);
		Integer second = reaper(later, transport).finalizeEnding(later, 100).block(Duration.ofSeconds(10));
		assertThat(second).as("第二轮仅 a 确认收尾").isEqualTo(1);
		assertThat(row(a).state()).isEqualTo("ended");
		assertThat(row(a).endedAt()).isNotNull();
		assertThat(transport.endCalls.get(a)).as("可重发幂等 end").isEqualTo(2);
		assertThat(row(d).state()).isEqualTo("ending");
		assertThat(row(g).state()).isEqualTo("ending");
		cleanupAll();
	}

	// ---------- TC-C02-004：并发收尾（跨晋升子场景归 C-03/V-003） ----------

	@Test
	void tcFix2_02_04_concurrentFinalizeTerminalAdvancesExactlyOnce() throws Exception {
		Instant now = NOW;
		String owner = account + "-cc";
		String target = seedEnding(ts(now.minusSeconds(60)), ts(now.minusSeconds(60)), owner, null);

		// 两个 reaper 实例（各自 fake runtime，共享同一真实 PG 行）重叠收尾；屏障证明重叠，
		// 不是“单实例连续两次调用”。
		StubTransport firstTransport = new StubTransport();
		firstTransport.holdEnd = true; // end 到达 transport 即倒计数并挂起回报（非阻塞屏障）
		DigitalHumanSessionReaper first = reaper(now, firstTransport);
		StubTransport secondTransport = new StubTransport();
		DigitalHumanSessionReaper second = reaper(now, secondTransport);

		Sinks.One<Integer> firstDone = Sinks.one();
		Disposable subscription = first.finalizeEnding(now, 100).doOnNext(firstDone::tryEmitValue).subscribe();
		assertThat(firstTransport.endReached.await(10, TimeUnit.SECONDS)).as("屏障：第一个实例的 end 已到达 fake transport 且挂起")
				.isTrue();

		Integer secondCount = second.finalizeEnding(now, 100).block(Duration.ofSeconds(10));
		assertThat(secondCount).as("第二个实例先提交成功").isEqualTo(1);
		Row committed = row(target);
		assertThat(committed.state()).isEqualTo("ended");
		assertThat(committed.version()).isEqualTo(2);

		// 释放第一个实例挂起的成功回报：CAS 落败（state/version 已变），DB 终态不二次推进、不复活。
		firstTransport.heldAnswer.tryEmitValue(
				new DigitalHumanRuntimeClient.RuntimeState(target, "stub", 1, 1, "ended", null, null, false, false));
		Integer firstCount = firstDone.asMono().block(Duration.ofSeconds(10));
		assertThat(firstCount).as("落败实例零推进").isZero();
		Row after = row(target);
		assertThat(after.state()).isEqualTo("ended");
		assertThat(after.version()).as("终态 DB 只推进一次").isEqualTo(2);
		assertThat(after.endedAt()).isNotNull();
		assertThat(firstTransport.endCalls.get(target)).as("网络 end 可重发（两实例各一次）").isEqualTo(1);
		assertThat(secondTransport.endCalls.get(target)).isEqualTo(1);
		subscription.dispose();
		cleanupAll();
	}

	// ---------- fixture（§12.2 F-J） ----------

	/**
	 * fake transport（§12.2 F-J：只替换 RuntimeClient.Transport）：按 sessionId 脚本化 end 回报并
	 * 记录调用次数；默认回报 ended/cleanupPending=false。state/create 不应被 finalize 调用（503 即
	 * 显式失败，不假成功）。
	 */
	private static final class StubTransport implements DigitalHumanRuntimeClient.Transport {

		final Map<String, Integer> endCalls = new ConcurrentHashMap<>();
		final Map<String, Supplier<Mono<DigitalHumanRuntimeClient.RuntimeState>>> endScript = new ConcurrentHashMap<>();
		/** TC-C02-004 屏障：end 到达 transport 即倒计数并挂起回报（非阻塞，不占事件循环）。 */
		final CountDownLatch endReached = new CountDownLatch(1);
		final Sinks.One<DigitalHumanRuntimeClient.RuntimeState> heldAnswer = Sinks.one();
		volatile boolean holdEnd = false;

		@Override
		public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
				UUID commandId) {
			return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "stub：finalize 不应 create"));
		}

		@Override
		public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
			return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "stub：finalize 不应 state"));
		}

		@Override
		public Mono<DigitalHumanRuntimeClient.RuntimeState> end(String sessionId, UUID commandId, String reasonCode) {
			endCalls.merge(sessionId, 1, Integer::sum);
			if (holdEnd) {
				endReached.countDown();
				return heldAnswer.asMono();
			}
			Supplier<Mono<DigitalHumanRuntimeClient.RuntimeState>> script = endScript.get(sessionId);
			if (script != null) {
				return script.get();
			}
			return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "stub", 1, 1, "ended", null, null,
					false, false));
		}
	}

	/** 原始 WebClientResponseException 404（响应体任意）：精确辨识归 isSessionGone。 */
	private static WebClientResponseException raw404(String body) {
		return WebClientResponseException.create(404, "Not Found", null, body.getBytes(StandardCharsets.UTF_8),
				StandardCharsets.UTF_8);
	}

	private static DigitalHumanRuntimeClient runtime(DigitalHumanRuntimeClient.Transport transport) {
		return new DigitalHumanRuntimeClient(transport, new DigitalHumanRuntimeClient.Recorder() {
			@Override
			public void onCreate(String sessionId) {
			}

			@Override
			public void onEnd(String sessionId) {
			}
		});
	}

	private org.springframework.transaction.reactive.TransactionalOperator transactions() {
		return context.getBean(org.springframework.transaction.reactive.TransactionalOperator.class);
	}

	private static OffsetDateTime ts(Instant at) {
		return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
	}

	private Boolean cleanupPending(String sessionId) {
		return db.sql("SELECT cleanup_pending FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("cleanup_pending", Boolean.class)).one().block(Duration.ofSeconds(5));
	}

	private String state(String sessionId) {
		return db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("state", String.class)).one().block(Duration.ofSeconds(5));
	}

	/** 行读取：CAS 断言用（state/version/state_entered_at/ended_at）。 */
	private record Row(String state, Integer version, OffsetDateTime enteredAt, OffsetDateTime endedAt) {
	}

	private Row row(String sessionId) {
		return db.sql("SELECT state, version, state_entered_at, ended_at FROM dh_session WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId)
				.map(r -> new Row(r.get("state", String.class), r.get("version", Integer.class),
						r.get("state_entered_at", OffsetDateTime.class), r.get("ended_at", OffsetDateTime.class)))
				.one().block(Duration.ofSeconds(5));
	}

	/** create 容量同款 active 口径（§2.5 / SessionService）：非 queued 且非 ended/failed。 */
	private long activeCount() {
		Long active = db
				.sql("SELECT count(*) FILTER (WHERE state <> 'queued'"
						+ " AND state NOT IN ('ended','failed')) AS active FROM dh_session")
				.map((r, meta) -> r.get("active", Long.class)).one().block(Duration.ofSeconds(5));
		return active == null ? -1 : active;
	}

	private String seedSession(String state, java.time.Duration heartbeatAge) {
		return seedSession(state, heartbeatAge, account);
	}

	private String seedSession(String state, java.time.Duration heartbeatAge, String owner) {
		String id = UUID.randomUUID().toString();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
				+ " profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot, state,"
				+ " state_entered_at, lease_epoch, media_epoch, lease_expires_at, last_browser_heartbeat_at)"
				+ " VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock', gen_random_uuid(),"
				+ " gen_random_uuid(), '{}'::jsonb, :state, :entered, 1, 1,"
				+ " :heartbeat + interval '30 seconds', :heartbeat)").bind("id", id).bind("o", owner)
				.bind("state", state).bind("entered", java.time.OffsetDateTime.now().minusSeconds(60))
				.bind("heartbeat", java.time.OffsetDateTime.now().minus(heartbeatAge)).then()
				.block(Duration.ofSeconds(5));
		return id;
	}

	/** ending 快捷种子（entered/created 显式给定，供固定时钟断言）。 */
	private String seedEnding(OffsetDateTime enteredAt, OffsetDateTime createdAt, String owner, String workerId) {
		return seedAt("ending", enteredAt, createdAt, owner, workerId);
	}

	/** 显式时间种子：state_entered_at/created_at/worker_id 可控（固定时钟断言 + 晋升标记注入）。 */
	private String seedAt(String state, OffsetDateTime enteredAt, OffsetDateTime createdAt, String owner,
			String workerId) {
		String id = UUID.randomUUID().toString();
		var spec = db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, created_at,
				    lease_epoch, media_epoch, lease_expires_at, last_browser_heartbeat_at, worker_id)
				VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'stub', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, :state, :entered, :created, 1, 1,
				    :entered + interval '30 seconds', :entered, CAST(:worker AS text))
				""").bind("id", id).bind("o", owner).bind("state", state).bind("entered", enteredAt).bind("created",
				createdAt);
		// R2DBC 不接受 bind(name, null)——worker_id 可空须 bindNull。
		spec = workerId == null ? spec.bindNull("worker", String.class) : spec.bind("worker", workerId);
		spec.then().block(Duration.ofSeconds(5));
		return id;
	}

	private DigitalHumanSessionReaper reaper(Instant at) {
		// 既有 3 参兼容构造器（W18 兼容面）：scan-only 用例走未配置 fail-closed runtime。
		return new DigitalHumanSessionReaper(db, transactions(), Clock.fixed(at, ZoneOffset.UTC));
	}

	private DigitalHumanSessionReaper reaper(Instant at, StubTransport transport) {
		return new DigitalHumanSessionReaper(db, transactions(), Clock.fixed(at, ZoneOffset.UTC), true,
				runtime(transport), DigitalHumanSessionReaper.FINALIZE_GRACE_DEFAULT);
	}

	private static Object fieldOf(Object bean, String name) {
		// 匿名/测试子类实例的字段声明在父类——沿继承链向上找（同 W18 范式）。
		for (Class<?> type = bean.getClass(); type != null; type = type.getSuperclass()) {
			try {
				var field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(bean);
			} catch (NoSuchFieldException ignored) {
				// 继续向父类找
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		throw new IllegalStateException(name + " not found on " + bean.getClass().getName());
	}

	/** 本运行作用域清理（随机前缀 LIKE；只清本测试种下的行）。 */
	private void cleanupAll() {
		db.sql("DELETE FROM dh_session WHERE owner_account_id LIKE :p").bind("p", account + "%").then()
				.then(db.sql("DELETE FROM dh_operation WHERE owner_account_id LIKE :p").bind("p", account + "%").then())
				.block(Duration.ofSeconds(5));
	}
}
