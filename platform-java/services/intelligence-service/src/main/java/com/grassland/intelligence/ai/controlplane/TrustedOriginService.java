package com.grassland.intelligence.ai.controlplane;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 受信 origin 控制面服务（任务书 #58 S1.2）：CRUD 编排 + 进程内策略缓存（写后失效）。
 *
 * <p>
 * <b>缓存语义</b>：{@link PlatformProviderPolicy#validate} 是同步调用（WebFlux 事件循环上也有），
 * R2DBC 重拉无法在调用线程上 block——故失效事件触发<b>异步重拉</b>替换快照，而非清空后同步读
 * （语义等价且更稳：重拉失败保留旧快照而不是变成「全拒绝」）。启动期 ApplicationRunner 阻塞预热
 * 一次，保证服务开始接流量前快照就位；预热失败 fail-closed（空集，平台 base-url 校验全拒）。
 *
 * <p>
 * <b>单实例语义</b>：多实例部署时其它副本感知不到本副本的写事件——任务书 #103 C103-20：
 * {@link TrustedOriginRefreshWorker} 每 5 秒定时全量小表刷新（写后事件仍即时刷新）， 快照带加载时点，age ≥
 * maxAge（默认 30s）时 {@link #enabledOrigins()} 拒绝新受信调用 （503
 * policy_unavailable），跨副本撤销 ≤30s 封闭；失败保留尚有效快照但绝不无限续期。
 */
@Service
public class TrustedOriginService implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(TrustedOriginService.class);
	private static final Duration WARMUP_TIMEOUT = Duration.ofSeconds(10);

	/** 带加载时点的快照：null=从未成功加载（fail-closed）。 */
	record OriginSnapshot(Set<String> origins, Instant loadedAt) {
	}

	private final PlatformTrustedOriginRepository repository;
	private final PlatformModelConfigRepository modelConfigs;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final Duration maxAge;
	private final AtomicReference<OriginSnapshot> snapshot = new AtomicReference<>();
	/** 单飞：单实例同时至多一个在途刷新（重复触发直接沿用进行中的那一次）。 */
	private final AtomicReference<Mono<Void>> inFlight = new AtomicReference<>();
	private final AtomicBoolean metricsRegistered = new AtomicBoolean(false);
	private volatile Counter refreshFailures;
	private volatile Counter staleRejected;
	private final MeterRegistry meterRegistry;

	@org.springframework.beans.factory.annotation.Autowired
	public TrustedOriginService(PlatformTrustedOriginRepository repository, PlatformModelConfigRepository modelConfigs,
			ApplicationEventPublisher events, MeterRegistry meterRegistry,
			@Value("${intelligence.trusted-origin.max-age-seconds:30}") long maxAgeSeconds) {
		this(repository, modelConfigs, events, Clock.systemUTC(), meterRegistry, maxAgeSeconds);
	}

	/** 测试构造器：注入可控 Clock（时间推进不真实等待 maxAge）。 */
	TrustedOriginService(PlatformTrustedOriginRepository repository, PlatformModelConfigRepository modelConfigs,
			ApplicationEventPublisher events, Clock clock, MeterRegistry meterRegistry, long maxAgeSeconds) {
		this.repository = repository;
		this.modelConfigs = modelConfigs;
		this.events = events;
		this.clock = clock;
		this.meterRegistry = meterRegistry;
		this.maxAge = Duration.ofSeconds(maxAgeSeconds);
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			refresh().block(WARMUP_TIMEOUT);
		} catch (Exception error) {
			// 预热失败不阻断启动（照 Seeder 姿态）：缓存保持空 = fail-closed（平台 base-url 校验全拒），
			// 而非带着不确定的旧数据放行。DB 真不可达时 Flyway 会更早失败，这里只兜瞬断。
			logger.warn("Trusted origin cache warmup failed (fail-closed until next write/restart): {}",
					error.getMessage());
		}
	}

	/** 事件驱动的异步重拉（写后失效）：失败保留旧快照并告警，绝不把缓存打成空集。 */
	@EventListener
	public void onOriginsChanged(TrustedOriginsChangedEvent event) {
		refresh().subscribe(null, error -> logger
				.warn("Trusted origin cache reload failed; keeping previous snapshot: {}", error.getMessage()));
	}

	/**
	 * 重拉启用中的 origin 集（定时/写后/启动预热共用，单飞）。记录查询起点/完成点： 完成点相对起点超过 maxAge
	 * 的结果<b>不安装</b>（慢查询不得把旧策略重新激活为新快照）， 保留旧快照待下一轮。空集是有效撤销结果（正常安装）。
	 */
	public Mono<Void> refresh() {
		Mono<Void> fresh = Mono.defer(() -> {
			Instant startedAt = clock.instant();
			return repository.listEnabledOrigins().map(TrustedOriginService::normalize).collectList()
					.publishOn(Schedulers.boundedElastic()).doOnNext(origins -> {
						Instant completedAt = clock.instant();
						if (Duration.between(startedAt, completedAt).compareTo(maxAge) > 0) {
							logger.warn(
									"Trusted origin refresh took longer than maxAge ({}ms); keeping previous snapshot",
									Duration.between(startedAt, completedAt).toMillis());
							failureCounter().increment();
							return;
						}
						snapshot.set(new OriginSnapshot(Set.copyOf(origins), completedAt));
					}).then().doOnError(error -> {
						logger.warn("Trusted origin cache refresh failed; keeping previous snapshot: {}",
								error.getMessage());
						failureCounter().increment();
					}).onErrorMap(error -> new IllegalStateException("受信 origin 缓存刷新失败", error))
					.doFinally(ignored -> inFlight.set(null));
		});
		return inFlight.updateAndGet(current -> current != null ? current : fresh);
	}

	private Counter failureCounter() {
		if (metricsRegistered.compareAndSet(false, true)) {
			refreshFailures = meterRegistry.counter("intelligence.trusted-origin.refresh", "outcome", "failure");
			staleRejected = meterRegistry.counter("intelligence.trusted-origin.checks", "outcome", "stale_rejected");
		}
		return refreshFailures;
	}

	/** 表行可能是「无显式端口」写法（V56 种子即如此）；与校验值同归一化后比较才不漏。 */
	private static String normalize(String raw) {
		return PlatformProviderPolicy.originOf(java.net.URI.create(raw.trim()));
	}

	/**
	 * 当前启用中的 origin 集（策略校验读这个）。任务书 #103 C103-20： 快照不存在或 age ≥ maxAge → 503
	 * policy_unavailable（可解释失败，不静默放行也不全拒绝无解释）； 快照新鲜而 origin 不在集合 →
	 * 调用方沿用既有「不受信」错误（明确移除=撤销生效）。
	 */
	public Set<String> enabledOrigins() {
		OriginSnapshot current = snapshot.get();
		if (current == null) {
			if (staleRejected != null) {
				staleRejected.increment();
			}
			throw new com.grassland.intelligence.security.IntelligenceException(503, "平台端点策略暂不可用（受信列表未加载），请稍后重试");
		}
		if (Duration.between(current.loadedAt(), clock.instant()).compareTo(maxAge) >= 0) {
			if (staleRejected != null) {
				staleRejected.increment();
			}
			throw new com.grassland.intelligence.security.IntelligenceException(503,
					"平台端点策略已过期（超过 " + maxAge.toSeconds() + " 秒未刷新），请稍后重试");
		}
		return current.origins();
	}

	/** 测试/观测：当前快照年龄（秒）；无快照返回 -1。 */
	public long snapshotAgeSeconds() {
		OriginSnapshot current = snapshot.get();
		return current == null ? -1 : Duration.between(current.loadedAt(), clock.instant()).toSeconds();
	}

	public reactor.core.publisher.Flux<PlatformTrustedOrigin> listAll() {
		return repository.listAll();
	}

	/**
	 * 新增。语义重复（归一化后与既有行相同，含种子行的无端口写法）→ 409； raw 字符串重复由唯一索引兜底 → 同样 409。
	 */
	public Mono<PlatformTrustedOrigin> create(String origin, String label, String adminId) {
		return assertOriginFree(normalize(origin), null)
				.then(Mono.defer(() -> repository.create(origin, label, adminId))).doOnNext(saved -> publish(saved))
				.onErrorMap(DataIntegrityViolationException.class,
						error -> new com.grassland.intelligence.security.IntelligenceException(409, "该端点已在受信列表中"));
	}

	/** 乐观锁修订。expectedVersion 不匹配且行仍存在 → 409；改 origin 撞既有行（归一化语义）→ 409。 */
	public Mono<PlatformTrustedOrigin> update(UUID id, String origin, String label, boolean enabled,
			int expectedVersion, String adminId) {
		return assertOriginFree(normalize(origin), id)
				.then(Mono.defer(() -> repository.update(id, origin, label, enabled, expectedVersion, adminId)))
				.switchIfEmpty(Mono.defer(() -> repository.findById(id).<PlatformTrustedOrigin>flatMap(
						existing -> Mono.error(new com.grassland.intelligence.security.IntelligenceException(409,
								"该端点已被他人修改（版本冲突），请刷新后重试")))
						.switchIfEmpty(Mono.error(
								new com.grassland.intelligence.security.IntelligenceException(404, "未找到受信端点: " + id)))))
				.doOnNext(saved -> publish(saved));
	}

	/**
	 * 删除（硬删）。仍被<b>启用中</b>的平台模型引用 → 409 点名引用方（2026-09-02 分镜静默 502 实录： 运行中误删 origin
	 * 靠进程内缓存尚可跑，重启按表重建后全拒且无留痕）。停用引用不拦—— 先停用模型再删 origin
	 * 是合法下线路径；未被模型引用的孤凭据也不拦（将来引用它配模型时， 保存路径的受信校验会 422 引导）。
	 */
	public Mono<Boolean> delete(UUID id) {
		return repository.findById(id).flatMap(
				row -> assertOriginUnused(normalize(row.origin())).then(Mono.defer(() -> repository.delete(id))))
				.doOnNext(deleted -> {
					if (deleted) {
						events.publishEvent(new TrustedOriginsChangedEvent());
					}
				}).defaultIfEmpty(false);
	}

	/**
	 * 删除闸门：启用中的平台模型（sandbox 除外——走内置地址不查本表）解析出的 base_url origin 与待删行归一化相等 →
	 * 409。base_url 沿用「凭据优先、配置列兜底」同一真相源 （{@link PlatformModelConfigRepository} 的
	 * COALESCE），与运行时校验看到的地址一致。
	 */
	private Mono<Void> assertOriginUnused(String normalizedOrigin) {
		return modelConfigs.findAllCurrent()
				.filter(config -> !PlatformProviderNames.SANDBOX.equalsIgnoreCase(config.provider()))
				.filter(config -> config.baseUrl() != null
						&& normalizedOrigin.equals(normalize(config.baseUrl())))
				.collectList()
				.flatMap(refs -> refs.isEmpty()
						? Mono.empty()
						: Mono.error(new com.grassland.intelligence.security.IntelligenceException(409,
								"该端点仍被启用中的平台模型引用（"
										+ refs.stream().map(config -> config.capability() + "/" + config.model())
												.collect(java.util.stream.Collectors.joining("、"))
										+ "），请先停用或改配这些模型")));
	}

	/**
	 * 归一化语义查重：表行可能是「无显式端口」写法（V56 种子即如此），唯一索引拦不住
	 * 与种子行语义相同的新行——建/改前先按归一化形态比对（excludeId 非空时跳过自身）。
	 */
	private Mono<Void> assertOriginFree(String normalizedOrigin, UUID excludeId) {
		return repository.listAll().filter(row -> !row.id().equals(excludeId)).map(row -> normalize(row.origin()))
				.collectList()
				.flatMap(existing -> existing.contains(normalizedOrigin)
						? Mono.error(new com.grassland.intelligence.security.IntelligenceException(409, "该端点已在受信列表中"))
						: Mono.empty());
	}

	private void publish(PlatformTrustedOrigin saved) {
		events.publishEvent(new TrustedOriginsChangedEvent());
	}
}
