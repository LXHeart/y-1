package com.grassland.intelligence.homepage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 热点历史聚合（缺口清偿之八，#35 D5 / PRD §4.3「按时间范围筛选——今天/本周」）：
 * 对 {@code hot_items_snapshot} 窗口内快照按平台聚合去重——同标题合并（best rank / 出现快照数 /
 * 首末出现时间），产出与实时 groups 同构的榜单。快照由 60s 刷新归档（TTL 2h → ≤12 行/天），
 * 无快照时返回空 groups + snapshotCount=0（前端回落实时榜）。
 */
@Service
public class HotItemsHistoryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 聚合输出条目上限（跨快照去重后仍按 best rank 截断，防长尾撑爆响应）。 */
    private static final int MAX_ITEMS_PER_PLATFORM = 50;

    private final HotItemsSnapshotRepository snapshots;
    /** 服务内自持（本服务未注册 ObjectMapper bean）。 */
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${homepage.hot-items.snapshot-retention-days:14}")
    private long retentionDays = 14;

    public HotItemsHistoryService(HotItemsSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    public record HistoryItem(
            int rank,
            String title,
            String hotValue,
            String url,
            String cover,
            String sourceLabel,
            Object tags,
            int occurrences,
            String firstSeen,
            String lastSeen) {}

    public record HistoryGroup(String platform, String label, List<HistoryItem> items) {}

    public record HistoryResult(String range, String since, long snapshotCount, List<HistoryGroup> groups) {}

    /** range ∈ today|week；today = 北京时间当日起（含今日已归档快照），week = 最近 7 天。 */
    public Mono<HistoryResult> history(String range) {
        boolean week = "week".equals(range);
        Instant since = week
                ? Instant.now().minus(Duration.ofDays(7))
                : LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        String normalizedRange = week ? "week" : "today";
        return snapshots.findSince(since).collectList().map(rows -> aggregate(normalizedRange, since, rows));
    }

    /** 归档钩子：60s 刷新成功后调用；顺带清理保留窗口外旧行。失败只影响历史（advisory）。 */
    public Mono<Void> archive(String groupsJson, Instant fetchedAt) {
        return snapshots.append(groupsJson, fetchedAt)
                .then(Mono.defer(() -> snapshots.deleteBefore(
                        Instant.now().minus(Duration.ofDays(Math.max(1, retentionDays))))))
                .onErrorResume(error -> Mono.empty());
    }

    private HistoryResult aggregate(String range, Instant since, List<HotItemsSnapshotRepository.SnapshotRow> rows) {
        // platform → (normalizedTitle → accumulator)；平台顺序按首次出现保持稳定。
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Map<String, Accumulator>> byPlatform = new LinkedHashMap<>();
        for (HotItemsSnapshotRepository.SnapshotRow row : rows) {
            for (HotItemGroup group : parseGroups(row.groupsJson())) {
                labels.putIfAbsent(group.platform(), group.label());
                Map<String, Accumulator> items = byPlatform.computeIfAbsent(group.platform(), k -> new LinkedHashMap<>());
                for (HotItem item : group.items()) {
                    if (item.title() == null || item.title().isBlank()) {
                        continue;
                    }
                    items.computeIfAbsent(item.title().trim(), title -> new Accumulator(title))
                            .merge(item, row.fetchedAt());
                }
            }
        }
        List<HistoryGroup> groups = new ArrayList<>();
        for (Map.Entry<String, Map<String, Accumulator>> entry : byPlatform.entrySet()) {
            List<HistoryItem> items = entry.getValue().values().stream()
                    .sorted(Comparator.comparingInt(a -> a.bestRank))
                    .limit(MAX_ITEMS_PER_PLATFORM)
                    .map(Accumulator::toItem)
                    .toList();
            if (!items.isEmpty()) {
                groups.add(new HistoryGroup(entry.getKey(), labels.get(entry.getKey()), items));
            }
        }
        return new HistoryResult(range, since.toString(), rows.size(), groups);
    }

    private List<HotItemGroup> parseGroups(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<HotItemGroup>>() {});
        } catch (Exception error) {
            return List.of();
        }
    }

    /** 同标题跨快照累积：best rank、出现快照数、首末时间、最新一条的字段（含分类 tags）。 */
    private static final class Accumulator {
        private final String title;
        private int bestRank = Integer.MAX_VALUE;
        private int occurrences;
        private Instant firstSeen;
        private Instant lastSeen;
        private HotItem latest;

        Accumulator(String title) {
            this.title = title;
        }

        void merge(HotItem item, Instant fetchedAt) {
            bestRank = Math.min(bestRank, item.rank());
            occurrences++;
            if (fetchedAt != null) {
                if (firstSeen == null || fetchedAt.isBefore(firstSeen)) {
                    firstSeen = fetchedAt;
                }
                if (lastSeen == null || fetchedAt.isAfter(lastSeen)) {
                    lastSeen = fetchedAt;
                    latest = item;
                }
            }
            if (latest == null) {
                latest = item;
            }
        }

        HistoryItem toItem() {
            HotItem item = latest == null ? new HotItem(bestRank, title, null, null, null, null) : latest;
            int rank = bestRank == Integer.MAX_VALUE ? item.rank() : bestRank;
            Instant first = firstSeen == null ? Instant.EPOCH : firstSeen;
            Instant last = lastSeen == null ? firstSeen : lastSeen;
            return new HistoryItem(
                    rank,
                    title,
                    item.hotValue(),
                    item.url(),
                    item.cover(),
                    item.sourceLabel(),
                    item.tags(),
                    occurrences,
                    first.toString(),
                    (last == null ? first : last).toString());
        }
    }
}
