package com.grassland.intelligence.homepage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.hottopic.HotTopicClassifier;
import com.grassland.intelligence.hottopic.HotTopicFilter;
import com.grassland.intelligence.hottopic.HotTopicTaxonomy;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.settings.HomepageSettingsService;
import com.grassland.intelligence.settings.UserSettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 首页热点编排回归（GL: homepage 迁移）：provider 分发 + 60s 缓存 TTL + 上游失败降级到过期缓存。
 */
class HomepageHotServiceTest {

    private static final String ACCOUNT = "11111111-1111-1111-1111-111111111111";

    private HomepageSettingsService homepageSettings;
    private UserSettingsRepository settingsRepo;
    private HotItems60sService sixtyS;
    private HotItemsAlapiService alapi;
    private HotTopicsCacheRepository cacheRepo;
    private HotTopicClassifier classifier;
    private HomepageHotService service;
    private HotItemsHistoryService history;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        homepageSettings = mock(HomepageSettingsService.class);
        settingsRepo = mock(UserSettingsRepository.class);
        sixtyS = mock(HotItems60sService.class);
        alapi = mock(HotItemsAlapiService.class);
        cacheRepo = mock(HotTopicsCacheRepository.class);
        classifier = new HotTopicClassifier(new HotTopicTaxonomy());
        history = org.mockito.Mockito.mock(HotItemsHistoryService.class);
        org.mockito.Mockito.when(history.archive(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(java.time.Instant.class)))
                .thenReturn(reactor.core.publisher.Mono.empty());
        service = new HomepageHotService(homepageSettings, settingsRepo, sixtyS, alapi, cacheRepo, classifier,
                history);
    }

    @Test
    void freshCacheSkipsUpstreamCall() throws Exception {
        stubProvider("60s");
        when(cacheRepo.readLatest()).thenReturn(Mono.just(new HotTopicsCacheRepository.CachedEntry(
                groupsJson(), Instant.now().minus(10, ChronoUnit.MINUTES))));

        StepVerifier.create(service.loadHotItems(ACCOUNT))
                .assertNext(result -> {
                    assertThat(result.provider()).isEqualTo("60s");
                    assertThat(result.groups()).hasSize(1);
                    assertThat(result.items()).isEmpty();
                    assertThat(result.taxonomy().version()).isEqualTo("hot-taxonomy-v1");
                    assertThat(result.groups().getFirst().items().getFirst().validUntil()).isNotBlank();
                    assertThat(result.groups().getFirst().items().getFirst().expired()).isFalse();
                })
                .verifyComplete();

        verify(sixtyS, never()).loadGroups();
    }

    @Test
    void staleCacheTriggersRefreshAndPersists() throws Exception {
        stubProvider("60s");
        when(cacheRepo.readLatest()).thenReturn(Mono.just(new HotTopicsCacheRepository.CachedEntry(
                groupsJson(), Instant.now().minus(3, ChronoUnit.HOURS))));
        when(sixtyS.loadGroups()).thenReturn(Mono.just(List.of(
                new HotItemGroup("weibo", "微博", List.of(new HotItem(1, "新热点", null, null, null, "微博"))))));
        when(cacheRepo.persist(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.loadHotItems(ACCOUNT))
                .assertNext(result -> {
                    assertThat(result.groups().getFirst().platform()).isEqualTo("weibo");
                    assertThat(result.groups().getFirst().items().getFirst().tags().taxonomyVersion())
                            .isEqualTo("hot-taxonomy-v1");
                })
                .verifyComplete();

        verify(cacheRepo).persist(anyString());
    }

    @Test
    void upstreamFailureFallsBackToStaleCache() throws Exception {
        stubProvider("60s");
        Instant staleAt = Instant.now().minus(3, ChronoUnit.HOURS);
        when(cacheRepo.readLatest()).thenReturn(Mono.just(
                new HotTopicsCacheRepository.CachedEntry(groupsJson(), staleAt)));
        when(sixtyS.loadGroups()).thenReturn(Mono.error(new IllegalStateException("upstream down")));

        StepVerifier.create(service.loadHotItems(ACCOUNT))
                .assertNext(result -> {
                    assertThat(result.groups().getFirst().platform()).isEqualTo("douyin");
                    assertThat(result.fetchedAt()).isEqualTo(staleAt.toString());
                })
                .verifyComplete();
    }

    @Test
    void emptyUpstreamWithoutCacheIsBadGateway() {
        stubProvider("60s");
        when(cacheRepo.readLatest()).thenReturn(Mono.empty());
        when(sixtyS.loadGroups()).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.loadHotItems(ACCOUNT))
                .expectErrorSatisfies(error -> assertThat(((IntelligenceException) error).status()).isEqualTo(502))
                .verify();
    }

    @Test
    void alapiWithoutTokenIsBadRequest() {
        stubProvider("alapi");
        when(settingsRepo.findByAccountAndType(ACCOUNT, "homepage")).thenReturn(Mono.empty());

        StepVerifier.create(service.loadHotItems(ACCOUNT))
                .expectErrorSatisfies(error -> assertThat(((IntelligenceException) error).status()).isEqualTo(400))
                .verify();

        verify(alapi, never()).loadItems(anyString());
    }

    @Test
    void alapiUsesUnmaskedStoredToken() {
        stubProvider("alapi");
        when(settingsRepo.findByAccountAndType(ACCOUNT, "homepage"))
                .thenReturn(Mono.just("{\"hotItems\":{\"provider\":\"alapi\",\"alapiToken\":\"tok-real-1234\"}}"));
        when(alapi.loadItems("tok-real-1234")).thenReturn(Mono.just(
                List.of(new HotItem(1, "热点", null, null, null, "抖音"))));

        StepVerifier.create(service.loadHotItems(ACCOUNT))
                .assertNext(result -> {
                    assertThat(result.provider()).isEqualTo("alapi");
                    assertThat(result.items()).hasSize(1);
                    assertThat(result.groups()).isNull();
                    HotItem item = result.items().getFirst();
                    assertThat(Duration.between(
                            Instant.parse(result.fetchedAt()), Instant.parse(item.validUntil())))
                            .isEqualTo(Duration.ofHours(6));
                    assertThat(item.expired()).isFalse();
                    assertThat(item.tags().taxonomyVersion()).isEqualTo("hot-taxonomy-v1");
                })
                .verifyComplete();
    }

    /** 未登录（accountId=null）走平台默认 60s，不该因缺 token 报错。 */
    @Test
    void anonymousFallsBackTo60s() throws Exception {
        when(homepageSettings.getOrDefault(null))
                .thenReturn(Mono.just(Map.of("hotItems", Map.of("provider", "60s"))));
        when(cacheRepo.readLatest()).thenReturn(Mono.just(new HotTopicsCacheRepository.CachedEntry(
                groupsJson(), Instant.now())));

        StepVerifier.create(service.loadHotItems(null))
                .assertNext(result -> assertThat(result.provider()).isEqualTo("60s"))
                .verifyComplete();
    }

    @Test
    void filterUsesOrWithinDimensionAndAndAcrossDimensions() throws Exception {
        stubProvider("60s");
        List<HotItem> tagged = List.of(
                tagged(1, "上海AI火锅发布会"),
                tagged(2, "上海美妆明星活动"),
                tagged(3, "北京AI火锅发布会"));
        when(cacheRepo.readLatest()).thenReturn(Mono.just(new HotTopicsCacheRepository.CachedEntry(
                mapper.writeValueAsString(List.of(new HotItemGroup("douyin", "抖音", tagged))), Instant.now())));

        HotTopicFilter filter = new HotTopicFilter(
                Set.of("catering", "retail"), Set.of("上海"), Set.of("tech"), false);
        StepVerifier.create(service.loadHotItems(ACCOUNT, filter))
                .assertNext(result -> assertThat(result.groups().getFirst().items())
                        .extracting(HotItem::title)
                        .containsExactly("上海AI火锅发布会"))
                .verifyComplete();

        verify(sixtyS, never()).loadGroups();
    }

    @Test
    void expiredCacheIsHiddenByDefaultAndIncludedOnDemand() throws Exception {
        stubProvider("60s");
        Instant fetchedAt = Instant.now().minus(25, ChronoUnit.HOURS);
        when(cacheRepo.readLatest()).thenReturn(Mono.just(new HotTopicsCacheRepository.CachedEntry(
                groupsJson(), fetchedAt)));
        when(sixtyS.loadGroups()).thenReturn(Mono.error(new IllegalStateException("upstream down")));

        StepVerifier.create(service.loadHotItems(ACCOUNT))
                .assertNext(result -> assertThat(result.groups()).isEmpty())
                .verifyComplete();

        StepVerifier.create(service.loadHotItems(
                        ACCOUNT, new HotTopicFilter(Set.of(), Set.of(), Set.of(), true)))
                .assertNext(result -> {
                    HotItem item = result.groups().getFirst().items().getFirst();
                    assertThat(item.expired()).isTrue();
                    assertThat(item.validUntil()).isEqualTo(fetchedAt.plus(24, ChronoUnit.HOURS).toString());
                })
                .verifyComplete();
    }

    @Test
    void cacheWithoutTaxonomyTagsRefreshesEvenInsideTtl() throws Exception {
        stubProvider("60s");
        String legacyJson = mapper.writeValueAsString(List.of(new HotItemGroup(
                "douyin", "抖音", List.of(new HotItem(1, "旧热点", null, null, null, "抖音")))));
        when(cacheRepo.readLatest()).thenReturn(Mono.just(new HotTopicsCacheRepository.CachedEntry(
                legacyJson, Instant.now().minus(10, ChronoUnit.MINUTES))));
        when(sixtyS.loadGroups()).thenReturn(Mono.just(List.of(new HotItemGroup(
                "douyin", "抖音", List.of(new HotItem(1, "上海火锅", null, null, null, "抖音"))))));
        when(cacheRepo.persist(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.loadHotItems(ACCOUNT))
                .assertNext(result -> assertThat(result.groups().getFirst().items().getFirst().tags().city())
                        .isEqualTo("上海"))
                .verifyComplete();

        verify(sixtyS).loadGroups();
        verify(cacheRepo).persist(anyString());
    }

    private void stubProvider(String provider) {
        when(homepageSettings.getOrDefault(any()))
                .thenReturn(Mono.just(Map.of("hotItems", Map.of("provider", provider))));
    }

    private String groupsJson() throws Exception {
        return mapper.writeValueAsString(List.of(new HotItemGroup(
                "douyin", "抖音", List.of(tagged(1, "旧热点")))));
    }

    private HotItem tagged(int rank, String title) {
        return new HotItem(rank, title, "100万", null, null, "抖音")
                .withTags(classifier.classify(title));
    }
}
