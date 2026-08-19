package com.grassland.intelligence.homepage;

import com.grassland.http.ManagedWebClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 60s 热点 API 聚合（GL: homepage 迁移）。复刻 legacy {@code hot-topics-60s.service.ts}。
 *
 * <p>三平台并发（douyin/weibo/zhihu），部分容错（一平台失败不影响其它）。
 * 每平台取前 20 条。响应格式：groups[{platform, label, items}]。
 */
@Component
public class HotItems60sService {

    private static final List<PlatformConfig> PLATFORMS = List.of(
            new PlatformConfig("douyin", "抖音", "/v2/douyin"),
            new PlatformConfig("weibo", "微博", "/v2/weibo"),
            new PlatformConfig("zhihu", "知乎", "/v2/zhihu"));
    private static final int ITEMS_PER_PLATFORM = 20;

    private final WebClient webClient;
    private final String origin;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    public HotItems60sService(
            @Value("${hot-items.sixty-s.base-url:https://60s.viki.moe/v2/douyin}") String baseUrl,
            @Value("${hot-items.sixty-s.timeout-ms:8000}") long timeoutMs) {
        // baseUrl 形如 https://60s.viki.moe/v2/douyin，取 origin
        this.origin = URI.create(baseUrl).getScheme() + "://" + URI.create(baseUrl).getHost();
        this.webClient = ManagedWebClientFactory.create(HotItems60sService.class, this.origin);
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /**
     * 并发拉取三平台热点。返回 groups。部分容错：某平台失败不影响其它。
     */
    public Mono<List<HotItemGroup>> loadGroups() {
        return Flux.fromIterable(PLATFORMS)
                .flatMap(this::loadPlatform)
                .collectList()
                .map(groups -> groups.stream().filter(g -> !g.items().isEmpty()).toList());
    }

    private Mono<HotItemGroup> loadPlatform(PlatformConfig platform) {
        return webClient.get()
                .uri(builder -> builder.path(platform.path()).queryParam("encoding", "json").build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(body -> parsePlatform(body, platform))
                .onErrorResume(e -> Mono.just(new HotItemGroup(platform.key(), platform.label(), List.of())));
    }

    @SuppressWarnings("unchecked")
    private HotItemGroup parsePlatform(String body, PlatformConfig platform) {
        try {
            Map<String, Object> resp = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object data = resp.get("data");
            if (!(data instanceof List<?> list)) {
                return new HotItemGroup(platform.key(), platform.label(), List.of());
            }
            List<HotItem> items = new ArrayList<>();
            int rank = 1;
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && rank <= ITEMS_PER_PLATFORM) {
                    items.add(new HotItem(
                            rank++,
                            String.valueOf(m.get("title")),
                            m.get("hot_value") != null ? String.valueOf(m.get("hot_value")) : null,
                            m.get("link") != null ? String.valueOf(m.get("link")) : null,
                            m.get("cover") != null ? String.valueOf(m.get("cover")) : null,
                            platform.label()));
                }
            }
            return new HotItemGroup(platform.key(), platform.label(), items);
        } catch (Exception e) {
            return new HotItemGroup(platform.key(), platform.label(), List.of());
        }
    }

    private record PlatformConfig(String key, String label, String path) {}
}
