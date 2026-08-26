package com.grassland.intelligence.homepage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.hottopic.HotTopicClassifier;
import com.grassland.intelligence.hottopic.HotTopicFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import com.grassland.intelligence.security.IntelligenceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 首页热点聚合编排（GL: homepage 迁移）。复刻 legacy {@code loadHomepageHotItems}。
 *
 * <p>
 * provider 由<b>平台级</b>配置决定（任务书 #47 S7b / D18① / V50）：{@code homepage_hot_config} 单行表，
 * 管理后台经 {@code /api/admin/homepage/hot-config} 维护，无行时默认 60s。热点是匿名可访问的平台
 * 数据，不再读用户级 homepage settings——存量 user_settings 行自 S7b 起不生效（V50 C 方案保留数据）：
 * <ul>
 * <li>{@code 60s}：三平台 groups，DB 缓存 2h TTL，上游失败降级到过期缓存。
 * <li>{@code alapi}：扁平 items，进程内缓存 5min TTL（key=解密后的平台 token）。
 * </ul>
 */
@Service
public class HomepageHotService {

	private static final Duration SIXTY_S_CACHE_TTL = Duration.ofHours(2);
	private static final Duration ALAPI_CACHE_TTL = Duration.ofMinutes(5);
	/** alapi 实时扁平榜单截断（legacy 语义：站点序展平后取前 100，再全局重排）。 */
	private static final int ALAPI_MAX_ITEMS = 100;

	private final HomepageHotConfigRepository hotConfigRepo;
	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	private final HotItems60sService sixtyS;
	private final HotItemsAlapiService alapi;
	private final HotTopicsCacheRepository cacheRepo;
	private final HotTopicClassifier classifier;
	private final HotItemsHistoryService history;
	@Value("${homepage.hot-items.validity-hours.60s:24}")
	private long sixtySValidityHours = 24;
	@Value("${homepage.hot-items.validity-hours.alapi:6}")
	private long alapiValidityHours = 6;
	/** 服务内自持（本服务未注册 ObjectMapper bean）。 */
	private final ObjectMapper mapper = new ObjectMapper();

	/** ALAPI 进程内缓存：key = token。 */
	private final ConcurrentHashMap<String, AlapiCacheEntry> alapiCache = new ConcurrentHashMap<>();

	public HomepageHotService(HomepageHotConfigRepository hotConfigRepo,
			ObjectProvider<EnvelopeEncryption> encryptionProvider,
			HotItems60sService sixtyS, HotItemsAlapiService alapi, HotTopicsCacheRepository cacheRepo,
			HotTopicClassifier classifier, HotItemsHistoryService history) {
		this.hotConfigRepo = hotConfigRepo;
		this.encryptionProvider = encryptionProvider;
		this.sixtyS = sixtyS;
		this.alapi = alapi;
		this.cacheRepo = cacheRepo;
		this.classifier = classifier;
		this.history = history;
	}

	public Mono<HotItemsResult> loadHotItems() {
		return loadHotItems(HotTopicFilter.DEFAULT);
	}

	/** 平台热点 provider（60s/alapi）；未配置 = 默认 60s。历史端点据此分源查询。 */
	public Mono<String> provider() {
		return hotConfigRepo.findOrDefault().map(HomepageHotConfig::provider);
	}

	/** 筛选只作用于缓存结果：维度内 OR、跨维度 AND，且默认隐藏超过源级有效期的条目。 */
	public Mono<HotItemsResult> loadHotItems(HotTopicFilter filter) {
		HotTopicFilter effectiveFilter = filter == null ? HotTopicFilter.DEFAULT : filter;
		return hotConfigRepo.findOrDefault()
				.flatMap(config -> HomepageHotConfig.PROVIDER_ALAPI.equals(config.provider())
						? loadAlapi(config, effectiveFilter)
						: load60s(effectiveFilter));
	}

	// ---------- 60s ----------

	private Mono<HotItemsResult> load60s(HotTopicFilter filter) {
		return cacheRepo.readLatest().flatMap(entry -> {
			List<HotItemGroup> cached = parseGroups(entry.itemsJson());
			boolean fresh = entry.fetchedAt() != null
					&& Duration.between(entry.fetchedAt(), Instant.now()).compareTo(SIXTY_S_CACHE_TTL) < 0
					&& usesCurrentTaxonomy(cached);
			if (fresh && !cached.isEmpty()) {
				return Mono.just(build60s(cached, entry.fetchedAt(), filter));
			}
			// TTL 过期或 taxonomy 版本变化：刷新；失败仍降级旧缓存。
			return refresh60s(filter).onErrorResume(
					e -> cached.isEmpty() ? Mono.error(e) : Mono.just(build60s(cached, entry.fetchedAt(), filter)));
		}).switchIfEmpty(Mono.defer(() -> refresh60s(filter)));
	}

	private Mono<HotItemsResult> refresh60s(HotTopicFilter filter) {
		Instant fetchedAt = Instant.now();
		return sixtyS.loadGroups().flatMap(groups -> {
			if (groups.isEmpty()) {
				return Mono.error(new IntelligenceException(502, "获取全网热点失败，请稍后再试"));
			}
			List<HotItemGroup> classified = classifyGroups(groups);
			String groupsJson = toJson(classified);
			// 历史快照归档（缺口清偿之八）：失败只影响「今天/本周」，不影响实时响应。
			return cacheRepo.persist(groupsJson).onErrorResume(e -> Mono.empty()) // 缓存写失败不影响响应
					.then(history.archive(groupsJson, fetchedAt, "60s"))
					.thenReturn(build60s(classified, fetchedAt, filter));
		});
	}

	// ---------- ALAPI ----------

	private Mono<HotItemsResult> loadAlapi(HomepageHotConfig config, HotTopicFilter filter) {
		return resolveAlapiToken(config).flatMap(token -> {
			AlapiCacheEntry cached = alapiCache.get(token);
			if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
				return Mono.just(buildAlapi(cached.items(), cached.fetchedAt(), filter));
			}
			Instant fetchedAt = Instant.now();
			return alapi.loadGroups(token).flatMap(groups -> {
				// 实时契约保持扁平：站点序展平 → 截断 100 → 全局重排（legacy 语义）。
				List<HotItem> flat = renumberGlobally(classifyItems(
						groups.stream().flatMap(group -> group.items().stream()).limit(ALAPI_MAX_ITEMS).toList()));
				if (flat.isEmpty()) {
					return Mono.error(new IntelligenceException(502, "获取全网热点失败，请检查 ALAPI 配置"));
				}
				alapiCache.put(token, new AlapiCacheEntry(flat, fetchedAt, fetchedAt.plus(ALAPI_CACHE_TTL)));
				// 历史归档（之八遗留清偿）：按站点分组形态（组内 rank=站点榜单序），
				// 不截断——聚合去重本就按标题合并，多留长尾只增信息量。失败 advisory。
				List<HotItemGroup> classifiedGroups = classifyGroups(groups);
				return history.archive(toJson(classifiedGroups), fetchedAt, "alapi")
						.thenReturn(buildAlapi(flat, fetchedAt, filter));
			});
		});
	}

	/** alapi 实时响应的全局重排（legacy：截断后 1..n 覆盖站点内 rank）。 */
	private static List<HotItem> renumberGlobally(List<HotItem> items) {
		List<HotItem> result = new java.util.ArrayList<>(items.size());
		int rank = 1;
		for (HotItem item : items) {
			result.add(new HotItem(rank++, item.title(), item.hotValue(), item.url(), item.cover(), item.sourceLabel(),
					item.tags(), item.validUntil(), item.expired()));
		}
		return List.copyOf(result);
	}

	// ---------- classify / filter / validity ----------

	private List<HotItemGroup> classifyGroups(List<HotItemGroup> groups) {
		return groups.stream()
				.map(group -> new HotItemGroup(group.platform(), group.label(), classifyItems(group.items()))).toList();
	}

	private List<HotItem> classifyItems(List<HotItem> items) {
		return items.stream().map(item -> item.withTags(classifier.classify(item.title()))).toList();
	}

	private boolean usesCurrentTaxonomy(List<HotItemGroup> groups) {
		return groups.stream().flatMap(group -> group.items().stream()).allMatch(
				item -> item.tags() != null && classifier.taxonomy().version().equals(item.tags().taxonomyVersion()));
	}

	private HotItemsResult build60s(List<HotItemGroup> groups, Instant fetchedAt, HotTopicFilter filter) {
		Instant effectiveFetchedAt = fetchedAt == null ? Instant.now() : fetchedAt;
		Instant validUntil = effectiveFetchedAt.plus(Duration.ofHours(Math.max(1, sixtySValidityHours)));
		List<HotItemGroup> filtered = groups.stream()
				.map(group -> new HotItemGroup(group.platform(), group.label(),
						filterItems(group.items(), validUntil, filter)))
				.filter(group -> !group.items().isEmpty()).toList();
		return HotItemsResult.of60s(filtered, effectiveFetchedAt, classifier.taxonomy().metadata());
	}

	private HotItemsResult buildAlapi(List<HotItem> items, Instant fetchedAt, HotTopicFilter filter) {
		Instant effectiveFetchedAt = fetchedAt == null ? Instant.now() : fetchedAt;
		Instant validUntil = effectiveFetchedAt.plus(Duration.ofHours(Math.max(1, alapiValidityHours)));
		return HotItemsResult.ofAlapi(filterItems(items, validUntil, filter), effectiveFetchedAt,
				classifier.taxonomy().metadata());
	}

	private List<HotItem> filterItems(List<HotItem> items, Instant validUntil, HotTopicFilter filter) {
		boolean expired = !validUntil.isAfter(Instant.now());
		return items.stream().filter(item -> filter.matches(item.tags()))
				.filter(item -> filter.includeExpired() || !expired)
				.map(item -> item.withValidity(validUntil.toString(), expired)).toList();
	}

	/**
	 * 解密平台 ALAPI token。密文缺失 → 400（admin 选了 alapi 但没配 token）；
	 * KEK 未配 → 503 fail-closed（绝不退化，同 BYOK/平台凭据口径）。
	 */
	private Mono<String> resolveAlapiToken(HomepageHotConfig config) {
		if (!config.hasAlapiToken()) {
			return Mono.error(new IntelligenceException(400, "平台未配置 ALAPI Token，请联系管理员"));
		}
		return Mono.fromCallable(() -> {
			EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
			if (crypto == null) {
				throw new IntelligenceException(503, "ALAPI Token 解密不可用：未配置 CRYPTO_KEK_BASE64");
			}
			return crypto.decrypt(config.alapiTokenEncrypted());
		});
	}

	// ---------- JSON ----------

	private List<HotItemGroup> parseGroups(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return mapper.readValue(json, new TypeReference<List<HotItemGroup>>() {
			});
		} catch (Exception e) {
			return List.of();
		}
	}

	private String toJson(List<HotItemGroup> groups) {
		try {
			return mapper.writeValueAsString(groups);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to serialize hot topic groups", e);
		}
	}

	private record AlapiCacheEntry(List<HotItem> items, Instant fetchedAt, Instant expiresAt) {
	}
}
