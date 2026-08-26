package com.grassland.intelligence.homepage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.hottopic.HotTopicFilter;
import com.grassland.intelligence.hottopic.HotTopicTaxonomy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class HomepageControllerTest {

	@Test
	void anonymousRequestBindsRepeatedAndCommaSeparatedFilters() {
		HomepageHotService service = mock(HomepageHotService.class);
		when(service.loadHotItems(any(HotTopicFilter.class))).thenReturn(Mono.just(HotItemsResult
				.of60s(List.of(), Instant.parse("2026-08-17T00:00:00Z"), new HotTopicTaxonomy().metadata())));

		WebTestClient.bindToController(new HomepageController(service, mock(HotItemsHistoryService.class)))
				.build().get()
				.uri(builder -> builder.path("/api/homepage/hot-items").queryParam("industry", "catering,retail")
						.queryParam("industry", "beauty").queryParam("city", "上海").queryParam("contentType", "tech")
						.queryParam("includeExpired", "true").build())
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.provider").isEqualTo("60s").jsonPath("$.data.taxonomy.version")
				.isEqualTo("hot-taxonomy-v1");

		ArgumentCaptor<HotTopicFilter> filter = ArgumentCaptor.forClass(HotTopicFilter.class);
		verify(service).loadHotItems(filter.capture());
		assertThat(filter.getValue().industries()).containsExactlyInAnyOrder("catering", "retail", "beauty");
		assertThat(filter.getValue().cities()).containsExactly("上海");
		assertThat(filter.getValue().contentTypes()).containsExactly("tech");
		assertThat(filter.getValue().includeExpired()).isTrue();
	}

	// ---------- 缺口清偿之八：热点历史（今天/本周；V40 后 provider 分源） ----------

	@Test
	void historyRejectsUnknownRange() {
		HomepageHotService service = mock(HomepageHotService.class);
		HotItemsHistoryService history = mock(HotItemsHistoryService.class);
		when(history.history(any(), any())).thenReturn(Mono
				.just(new HotItemsHistoryService.HistoryResult("today", "60s", "2026-08-20T00:00:00Z", 0, List.of())));

		WebTestClient.bindToController(new HomepageController(service, history)).build().get()
				.uri("/api/homepage/hot-items/history?range=month").exchange().expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error").isEqualTo("range 仅支持 today/week");
	}

	@Test
	void historyReturnsAggregatedResult() {
		HomepageHotService service = mock(HomepageHotService.class);
		HotItemsHistoryService history = mock(HotItemsHistoryService.class);
		when(service.provider()).thenReturn(Mono.just("60s"));
		when(history.history("today", "60s"))
				.thenReturn(
						Mono.just(new HotItemsHistoryService.HistoryResult("today", "60s", "2026-08-20T00:00:00Z", 3,
								List.of(new HotItemsHistoryService.HistoryGroup("douyin", "抖音",
										List.of(new HotItemsHistoryService.HistoryItem(1, "夏日饮品", "12345", null, null,
												"60sAPI", null, 3, "2026-08-20T01:00:00Z",
												"2026-08-20T05:00:00Z")))))));

		WebTestClient.bindToController(new HomepageController(service, history)).build().get()
				.uri("/api/homepage/hot-items/history?range=today").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.range").isEqualTo("today").jsonPath("$.data.provider").isEqualTo("60s")
				.jsonPath("$.data.snapshotCount").isEqualTo(3).jsonPath("$.data.groups[0].platform").isEqualTo("douyin")
				.jsonPath("$.data.groups[0].items[0].occurrences").isEqualTo(3);
	}

	/** 平台配置 alapi：历史查询按平台 provider 分源（S7b 起与调用者无关），不与 60s 混并。 */
	@Test
	void historyFollowsPlatformProvider() {
		HomepageHotService service = mock(HomepageHotService.class);
		HotItemsHistoryService history = mock(HotItemsHistoryService.class);
		when(service.provider()).thenReturn(Mono.just("alapi"));
		when(history.history("week", "alapi")).thenReturn(Mono
				.just(new HotItemsHistoryService.HistoryResult("week", "alapi", "2026-08-14T00:00:00Z", 1, List.of())));

		WebTestClient.bindToController(new HomepageController(service, history)).build().get()
				.uri("/api/homepage/hot-items/history?range=week").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.provider").isEqualTo("alapi").jsonPath("$.data.snapshotCount").isEqualTo(1);

		verify(history).history("week", "alapi");
	}
}
