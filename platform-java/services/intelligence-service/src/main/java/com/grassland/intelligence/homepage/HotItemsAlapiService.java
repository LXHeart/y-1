package com.grassland.intelligence.homepage;

import com.grassland.http.ManagedWebClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * ALAPI 热点聚合（GL: homepage 迁移）。复刻 legacy {@code homepage-hot.service.ts} 的 ALAPI
 * 路径。
 *
 * <p>
 * 两步：POST /api/tophub/site 拿站点列表 → 过滤白名单 → 每站点 POST /api/tophub 拿详情。 Header
 * token: <alapiToken>。产出每站点一组的 groups（组内按站点榜单顺序重排 rank）； 实时响应的扁平化/全局重排/截断 100 由
 * {@link HomepageHotService} 做（live 契约不变）。
 */
@Component
public class HotItemsAlapiService {

	private static final Set<String> ALLOWED_SITE_IDS = Set.of("douyin", "weibo", "weixin", "xiaohongshu");

	private final WebClient webClient;
	private final Duration timeout;
	private final ObjectMapper mapper = new ObjectMapper();

	public HotItemsAlapiService(@Value("${hot-items.alapi.base-url:https://v3.alapi.cn}") String baseUrl,
			@Value("${hot-items.alapi.timeout-ms:8000}") long timeoutMs,
			com.grassland.intelligence.ai.DnsPinningResolver dnsPinning) {
		// 固定数据源域名钉扎（GL-P3-AI-001 尾巴覆盖扩展）
		this.webClient = com.grassland.intelligence.ai.PinnedOutboundClients.forFixedHost(HotItemsAlapiService.class,
				baseUrl, dnsPinning, Duration.ofMillis(Math.max(1, timeoutMs)), 2 * 1024 * 1024);
		this.timeout = Duration.ofMillis(timeoutMs);
	}

	/** 每站点一组的聚合形态：platform=白名单站点 id、label=站点显示名、组内按站点榜单顺序。 */
	public Mono<List<HotItemGroup>> loadGroups(String alapiToken) {
		return fetchSites(alapiToken).flatMap(sites -> {
			List<String> filtered = sites.stream().filter(ALLOWED_SITE_IDS::contains).toList();
			// concatMap 保序：与 legacy Promise.all + flat 的站点顺序一致
			return reactor.core.publisher.Flux.fromIterable(filtered)
					.concatMap(siteId -> fetchSiteItems(alapiToken, siteId))
					.map(site -> new HotItemGroup(site.siteId(), site.name(), renumber(site.items()))).collectList();
		});
	}

	/** 第一步：POST /api/tophub/site 拿站点 id 列表。 */
	@SuppressWarnings("unchecked")
	private Mono<List<String>> fetchSites(String token) {
		return webClient.post().uri("/api/tophub/site").header("token", token).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("page", 1, "page_size", 100)).retrieve().bodyToMono(String.class).timeout(timeout)
				.map(body -> {
					try {
						Map<String, Object> resp = mapper.readValue(body, new TypeReference<Map<String, Object>>() {
						});
						Object data = resp.get("data");
						if (data instanceof List<?> list) {
							return list.stream().filter(item -> item instanceof Map<?, ?>)
									.map(item -> String.valueOf(((Map<?, ?>) item).get("id"))).toList();
						}
					} catch (Exception ignored) {
					}
					return List.<String>of();
				});
	}

	/** 站点榜单（siteId 与站点名随行——历史归档分组需要稳定的 platform key，不能只留显示名）。 */
	private record SiteItems(String siteId, String name, List<HotItem> items) {
	}

	/** 第二步：POST /api/tophub 拿某站点热点。 */
	@SuppressWarnings("unchecked")
	private Mono<SiteItems> fetchSiteItems(String token, String siteId) {
		return webClient.post().uri("/api/tophub").header("token", token).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("id", siteId)).retrieve().bodyToMono(String.class).timeout(timeout).map(body -> {
					try {
						Map<String, Object> resp = mapper.readValue(body, new TypeReference<Map<String, Object>>() {
						});
						Object data = resp.get("data");
						if (data instanceof Map<?, ?> dataMap) {
							Object list = dataMap.get("list");
							String name = dataMap.get("name") instanceof String s && !s.isBlank() ? s : siteId;
							if (list instanceof List<?> items) {
								List<HotItem> result = new ArrayList<>();
								for (Object item : items) {
									if (item instanceof Map<?, ?> m) {
										String title = m.get("title") instanceof String t ? t.trim() : "";
										if (title.isEmpty()) {
											continue; // legacy: 无 title 丢弃
										}
										String link = normalizeUrl(asString(m.get("link")));
										String image = normalizeUrl(asString(m.get("image")));
										result.add(new HotItem(0, // renumber 后填充
												title, asString(m.get("other")), link, image, name));
									}
								}
								return new SiteItems(siteId, name, result);
							}
						}
					} catch (Exception ignored) {
					}
					return new SiteItems(siteId, siteId, List.of());
				}).onErrorResume(e -> Mono.just(new SiteItems(siteId, siteId, List.of())));
	}

	private List<HotItem> renumber(List<HotItem> items) {
		List<HotItem> result = new ArrayList<>();
		int rank = 1;
		for (HotItem item : items) {
			result.add(
					new HotItem(rank++, item.title(), item.hotValue(), item.url(), item.cover(), item.sourceLabel()));
		}
		return result;
	}

	/** null-safe 取字符串（数字也接受，对齐 legacy normalizeHotValue）。 */
	private static String asString(Object value) {
		if (value == null)
			return null;
		if (value instanceof String s)
			return s.isBlank() ? null : s.trim();
		if (value instanceof Number n)
			return n.toString();
		return null;
	}

	private static String normalizeUrl(String url) {
		if (url == null || url.isBlank())
			return null;
		if (url.startsWith("http://") || url.startsWith("https://"))
			return url;
		return null;
	}
}
