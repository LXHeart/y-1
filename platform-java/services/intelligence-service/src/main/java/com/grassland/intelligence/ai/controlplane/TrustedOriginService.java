package com.grassland.intelligence.ai.controlplane;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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
 * <b>单实例语义</b>：多实例部署时其它副本感知不到本副本的写事件，各自有最长「到重启为止」的 陈旧窗口。当前 intelligence
 * 单实例部署；扩副本前需改为共享存储订阅（如 outbox/notify）——TODO(#58)。
 */
@Service
public class TrustedOriginService implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(TrustedOriginService.class);
	private static final Duration WARMUP_TIMEOUT = Duration.ofSeconds(10);

	private final PlatformTrustedOriginRepository repository;
	private final PlatformModelConfigRepository modelConfigs;
	private final ApplicationEventPublisher events;
	private final AtomicReference<Set<String>> enabledOrigins = new AtomicReference<>();

	public TrustedOriginService(PlatformTrustedOriginRepository repository, PlatformModelConfigRepository modelConfigs,
			ApplicationEventPublisher events) {
		this.repository = repository;
		this.modelConfigs = modelConfigs;
		this.events = events;
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

	/** 重拉启用中的 origin 集（启动预热与写后失效共用），并归一为 scheme://host:port 形态。 */
	public Mono<Void> refresh() {
		return repository.listEnabledOrigins().map(TrustedOriginService::normalize).collectList()
				.doOnNext(origins -> enabledOrigins.set(Set.copyOf(origins)))
				.onErrorMap(error -> new IllegalStateException("受信 origin 缓存刷新失败", error)).then();
	}

	/** 表行可能是「无显式端口」写法（V56 种子即如此）；与校验值同归一化后比较才不漏。 */
	private static String normalize(String raw) {
		return PlatformProviderPolicy.originOf(java.net.URI.create(raw.trim()));
	}

	/** 当前启用中的 origin 集（策略校验读这个）。未预热成功 → 空集 = fail-closed。 */
	public Set<String> enabledOrigins() {
		Set<String> snapshot = enabledOrigins.get();
		return snapshot == null ? Set.of() : snapshot;
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
