package com.grassland.intelligence.homepage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.settings.HomepageSettingsService;
import com.grassland.intelligence.settings.UserSettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.grassland.intelligence.security.IntelligenceException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 首页热点聚合编排（GL: homepage 迁移）。复刻 legacy {@code loadHomepageHotItems}。
 *
 * <p>provider 由用户级 homepage settings 决定：
 * <ul>
 *   <li>{@code 60s}：三平台 groups，DB 缓存 2h TTL，上游失败降级到过期缓存。
 *   <li>{@code alapi}：扁平 items，进程内缓存 5min TTL（key=token）。
 * </ul>
 */
@Service
public class HomepageHotService {

    private static final Duration SIXTY_S_CACHE_TTL = Duration.ofHours(2);
    private static final Duration ALAPI_CACHE_TTL = Duration.ofMinutes(5);

    private final HomepageSettingsService homepageSettings;
    private final UserSettingsRepository settingsRepo;
    private final HotItems60sService sixtyS;
    private final HotItemsAlapiService alapi;
    private final HotTopicsCacheRepository cacheRepo;
    /** 服务内自持（本服务未注册 ObjectMapper bean）。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** ALAPI 进程内缓存：key = token。 */
    private final ConcurrentHashMap<String, AlapiCacheEntry> alapiCache = new ConcurrentHashMap<>();

    public HomepageHotService(
            HomepageSettingsService homepageSettings,
            UserSettingsRepository settingsRepo,
            HotItems60sService sixtyS,
            HotItemsAlapiService alapi,
            HotTopicsCacheRepository cacheRepo) {
        this.homepageSettings = homepageSettings;
        this.settingsRepo = settingsRepo;
        this.sixtyS = sixtyS;
        this.alapi = alapi;
        this.cacheRepo = cacheRepo;
    }

    /** accountId 可为 null（未登录）→ 用平台默认 settings（provider=60s）。 */
    public Mono<HotItemsResult> loadHotItems(String accountId) {
        return homepageSettings.getOrDefault(accountId)
                .map(HomepageHotService::providerOf)
                .flatMap(provider -> "alapi".equals(provider)
                        ? loadAlapi(accountId)
                        : load60s());
    }

    @SuppressWarnings("unchecked")
    private static String providerOf(Map<String, Object> settings) {
        Object hotItems = settings.get("hotItems");
        if (hotItems instanceof Map<?, ?> m && m.get("provider") instanceof String p) {
            return p;
        }
        return "60s";
    }

    // ---------- 60s ----------

    private Mono<HotItemsResult> load60s() {
        return cacheRepo.readLatest()
                .flatMap(entry -> {
                    List<HotItemGroup> cached = parseGroups(entry.itemsJson());
                    boolean fresh = entry.fetchedAt() != null
                            && Duration.between(entry.fetchedAt(), Instant.now()).compareTo(SIXTY_S_CACHE_TTL) < 0;
                    if (fresh && !cached.isEmpty()) {
                        return Mono.just(HotItemsResult.of60s(cached, entry.fetchedAt()));
                    }
                    // 过期：刷新，失败降级到旧缓存
                    return refresh60s()
                            .onErrorResume(e -> cached.isEmpty()
                                    ? Mono.error(e)
                                    : Mono.just(HotItemsResult.of60s(cached, entry.fetchedAt())));
                })
                .switchIfEmpty(Mono.defer(this::refresh60s));
    }

    private Mono<HotItemsResult> refresh60s() {
        Instant fetchedAt = Instant.now();
        return sixtyS.loadGroups()
                .flatMap(groups -> {
                    if (groups.isEmpty()) {
                        return Mono.error(new IntelligenceException(502, "获取全网热点失败，请稍后再试"));
                    }
                    return cacheRepo.persist(toJson(groups))
                            .onErrorResume(e -> Mono.empty()) // 缓存写失败不影响响应
                            .thenReturn(HotItemsResult.of60s(groups, fetchedAt));
                });
    }

    // ---------- ALAPI ----------

    private Mono<HotItemsResult> loadAlapi(String accountId) {
        return resolveAlapiToken(accountId)
                .flatMap(token -> {
                    AlapiCacheEntry cached = alapiCache.get(token);
                    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                        return Mono.just(HotItemsResult.ofAlapi(cached.items(), cached.fetchedAt()));
                    }
                    Instant fetchedAt = Instant.now();
                    return alapi.loadItems(token)
                            .flatMap(items -> {
                                if (items.isEmpty()) {
                                    return Mono.error(new IntelligenceException(502, "获取全网热点失败，请检查 ALAPI 配置"));
                                }
                                alapiCache.put(token,
                                        new AlapiCacheEntry(items, fetchedAt, fetchedAt.plus(ALAPI_CACHE_TTL)));
                                return Mono.just(HotItemsResult.ofAlapi(items, fetchedAt));
                            });
                });
    }

    /** 读未掩码 token（不能走 HomepageSettingsService.get，那会掩码）。 */
    @SuppressWarnings("unchecked")
    private Mono<String> resolveAlapiToken(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Mono.error(new IntelligenceException(400, "请先在设置中配置 ALAPI Token"));
        }
        return settingsRepo.findByAccountAndType(accountId, "homepage")
                .mapNotNull(json -> {
                    try {
                        Map<String, Object> settings =
                                mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                        Object hotItems = settings.get("hotItems");
                        if (hotItems instanceof Map<?, ?> m && m.get("alapiToken") instanceof String t
                                && !t.isBlank()) {
                            return t;
                        }
                    } catch (Exception ignored) {
                        // 解析失败视为未配置
                    }
                    return null;
                })
                .switchIfEmpty(Mono.error(new IntelligenceException(400, "请先在设置中配置 ALAPI Token")));
    }

    // ---------- JSON ----------

    private List<HotItemGroup> parseGroups(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<HotItemGroup>>() {});
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

    private record AlapiCacheEntry(List<HotItem> items, Instant fetchedAt, Instant expiresAt) {}
}
