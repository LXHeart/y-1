package com.grassland.intelligence.homepage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@link HotItemsHistoryService} 聚合与归档节流单测（缺口清偿之八 + provider 遗留清偿）。 */
class HotItemsHistoryServiceTest {

	@Test
	void aggregatesAcrossSnapshotsDedupingByTitle() {
		HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
		when(repo.findSince(any(Instant.class), eq("60s"))).thenReturn(Flux.just(snapshot("2026-08-20T01:00:00Z", """
				[{"platform":"douyin","label":"抖音","items":[
				  {"rank":1,"title":"夏日饮品测评","hotValue":"100"},
				  {"rank":2,"title":"CityWalk 路线","hotValue":"90"}]},
				 {"platform":"weibo","label":"微博","items":[
				  {"rank":1,"title":"微博热搜一","hotValue":"80"}]}]
				"""), snapshot("2026-08-20T05:00:00Z", """
				[{"platform":"douyin","label":"抖音","items":[
				  {"rank":3,"title":"夏日饮品测评","hotValue":"120"},
				  {"rank":1,"title":"新上榜热点","hotValue":"70"}]}]
				""")));
		HotItemsHistoryService service = withInterval(new HotItemsHistoryService(repo), 300);

		HotItemsHistoryService.HistoryResult result = service.history("today", "60s").block();

		assertThat(result).isNotNull();
		assertThat(result.range()).isEqualTo("today");
		assertThat(result.provider()).isEqualTo("60s");
		assertThat(result.snapshotCount()).isEqualTo(2);
		assertThat(result.groups()).extracting(HotItemsHistoryService.HistoryGroup::platform).containsExactly("douyin",
				"weibo");
		HotItemsHistoryService.HistoryGroup douyin = result.groups().get(0);
		// 同标题合并：best rank=1（首快照）、出现 2 次、字段取最新快照（hotValue 120）
		HotItemsHistoryService.HistoryItem drink = douyin.items().get(0);
		assertThat(drink.title()).isEqualTo("夏日饮品测评");
		assertThat(drink.rank()).isEqualTo(1);
		assertThat(drink.occurrences()).isEqualTo(2);
		assertThat(drink.hotValue()).isEqualTo("120");
		assertThat(drink.firstSeen()).isEqualTo("2026-08-20T01:00:00Z");
		assertThat(drink.lastSeen()).isEqualTo("2026-08-20T05:00:00Z");
		// 按 best rank 排序：新上榜（rank1）在 CityWalk（rank2）前
		assertThat(douyin.items()).extracting(HotItemsHistoryService.HistoryItem::title).containsExactly("夏日饮品测评",
				"新上榜热点", "CityWalk 路线");
	}

	@Test
	void noSnapshotsReturnsEmptyGroupsWithZeroCount() {
		HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
		when(repo.findSince(any(Instant.class), eq("alapi"))).thenReturn(Flux.empty());
		HotItemsHistoryService service = withInterval(new HotItemsHistoryService(repo), 300);

		HotItemsHistoryService.HistoryResult result = service.history("week", "alapi").block();

		assertThat(result).isNotNull();
		assertThat(result.range()).isEqualTo("week");
		assertThat(result.provider()).isEqualTo("alapi");
		assertThat(result.snapshotCount()).isZero();
		assertThat(result.groups()).isEmpty();
	}

	/** alapi 站点分组归档形态与 60s groups 同构——聚合直接复用（platform=站点 id）。 */
	@Test
	void aggregatesAlapiSiteGroups() {
		HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
		when(repo.findSince(any(Instant.class), eq("alapi"))).thenReturn(Flux.just(snapshot("2026-08-20T08:00:00Z", """
				[{"platform":"xiaohongshu","label":"小红书","items":[
				  {"rank":1,"title":"夏日穿搭","hotValue":"1.2万","sourceLabel":"小红书"}]},
				 {"platform":"douyin","label":"抖音","items":[
				  {"rank":1,"title":"抖音热点","hotValue":"500万","sourceLabel":"抖音"}]}]
				""")));
		HotItemsHistoryService service = withInterval(new HotItemsHistoryService(repo), 300);

		HotItemsHistoryService.HistoryResult result = service.history("today", "alapi").block();

		assertThat(result.groups()).extracting(HotItemsHistoryService.HistoryGroup::platform)
				.containsExactly("xiaohongshu", "douyin");
		assertThat(result.groups().getFirst().label()).isEqualTo("小红书");
	}

	/** 归档节流：同 provider 窗口内的第二次归档被跳过（alapi 多用户刷新防 occurrences 放大）。 */
	@Test
	void archiveThrottlesWithinMinIntervalPerProvider() {
		HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
		when(repo.latestFetchedAt("alapi")).thenReturn(Mono.just(Instant.now().minusSeconds(60)));
		HotItemsHistoryService service = withInterval(new HotItemsHistoryService(repo), 300);

		service.archive("[]", Instant.now(), "alapi").block();

		verify(repo, never()).append(anyString(), any(Instant.class), anyString());
	}

	@Test
	void archiveOutsideIntervalOrFirstTimeAppends() {
		HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
		when(repo.latestFetchedAt("alapi")).thenReturn(Mono.empty());
		when(repo.append(anyString(), any(Instant.class), eq("alapi"))).thenReturn(Mono.empty());
		when(repo.deleteBefore(any(Instant.class))).thenReturn(Mono.empty());
		HotItemsHistoryService service = withInterval(new HotItemsHistoryService(repo), 300);

		service.archive("[]", Instant.now(), "alapi").block();

		verify(repo).append(anyString(), any(Instant.class), eq("alapi"));
		verify(repo).deleteBefore(any(Instant.class));
	}

	@Test
	void archiveFailureIsAdvisory() {
		HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
		when(repo.latestFetchedAt("60s")).thenReturn(Mono.empty());
		when(repo.append(anyString(), any(Instant.class), anyString()))
				.thenReturn(Mono.error(new IllegalStateException("db down")));
		when(repo.deleteBefore(any(Instant.class))).thenReturn(Mono.empty());
		HotItemsHistoryService service = withInterval(new HotItemsHistoryService(repo), 300);

		// 归档失败被吞——不影响实时链路
		service.archive("[]", Instant.now(), "60s").block();
	}

	private static HotItemsHistoryService withInterval(HotItemsHistoryService service, long seconds) {
		ReflectionTestUtils.setField(service, "minIntervalSeconds", seconds);
		return service;
	}

	private static HotItemsSnapshotRepository.SnapshotRow snapshot(String fetchedAt, String groupsJson) {
		return new HotItemsSnapshotRepository.SnapshotRow(UUID.randomUUID(), Instant.parse(fetchedAt), groupsJson);
	}
}
