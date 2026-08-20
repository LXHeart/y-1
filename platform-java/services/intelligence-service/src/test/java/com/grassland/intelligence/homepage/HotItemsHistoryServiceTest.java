package com.grassland.intelligence.homepage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** {@link HotItemsHistoryService} 聚合单测（缺口清偿之八）。 */
class HotItemsHistoryServiceTest {

    @Test
    void aggregatesAcrossSnapshotsDedupingByTitle() {
        HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
        when(repo.findSince(any(Instant.class))).thenReturn(Flux.just(
                snapshot("2026-08-20T01:00:00Z", """
                        [{"platform":"douyin","label":"抖音","items":[
                          {"rank":1,"title":"夏日饮品测评","hotValue":"100"},
                          {"rank":2,"title":"CityWalk 路线","hotValue":"90"}]},
                         {"platform":"weibo","label":"微博","items":[
                          {"rank":1,"title":"微博热搜一","hotValue":"80"}]}]
                        """),
                snapshot("2026-08-20T05:00:00Z", """
                        [{"platform":"douyin","label":"抖音","items":[
                          {"rank":3,"title":"夏日饮品测评","hotValue":"120"},
                          {"rank":1,"title":"新上榜热点","hotValue":"70"}]}]
                        """)));
        HotItemsHistoryService service = new HotItemsHistoryService(repo);

        HotItemsHistoryService.HistoryResult result = service.history("today").block();

        assertThat(result).isNotNull();
        assertThat(result.range()).isEqualTo("today");
        assertThat(result.snapshotCount()).isEqualTo(2);
        assertThat(result.groups()).extracting(HotItemsHistoryService.HistoryGroup::platform)
                .containsExactly("douyin", "weibo");
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
        assertThat(douyin.items()).extracting(HotItemsHistoryService.HistoryItem::title)
                .containsExactly("夏日饮品测评", "新上榜热点", "CityWalk 路线");
    }

    @Test
    void noSnapshotsReturnsEmptyGroupsWithZeroCount() {
        HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
        when(repo.findSince(any(Instant.class))).thenReturn(Flux.empty());
        HotItemsHistoryService service = new HotItemsHistoryService(repo);

        HotItemsHistoryService.HistoryResult result = service.history("week").block();

        assertThat(result).isNotNull();
        assertThat(result.range()).isEqualTo("week");
        assertThat(result.snapshotCount()).isZero();
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void archiveFailureIsAdvisory() {
        HotItemsSnapshotRepository repo = mock(HotItemsSnapshotRepository.class);
        when(repo.append(anyString(), any(Instant.class)))
                .thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("db down")));
        when(repo.deleteBefore(any(Instant.class))).thenReturn(reactor.core.publisher.Mono.empty());
        HotItemsHistoryService service = new HotItemsHistoryService(repo);

        // 归档失败被吞——不影响 60s 实时链路
        service.archive("[]", Instant.now()).block();
    }

    private static HotItemsSnapshotRepository.SnapshotRow snapshot(String fetchedAt, String groupsJson) {
        return new HotItemsSnapshotRepository.SnapshotRow(
                UUID.randomUUID(), Instant.parse(fetchedAt), groupsJson);
    }
}
