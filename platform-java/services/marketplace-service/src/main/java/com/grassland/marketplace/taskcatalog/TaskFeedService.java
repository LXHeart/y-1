package com.grassland.marketplace.taskcatalog;

import java.time.Instant;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/** Task feed filtering, stable keyset pagination and page-local enrichment. */
@Component
public class TaskFeedService {
	private final TaskRepository tasks;
	private final IdentityStoreAuthorizationClient identityStores;
	private final TaskStoreEnrichment storeEnrichment;
	private final TaskReadAccess taskReadAccess;
	private final TaskReadEnrichment taskReadEnrichment;

	public TaskFeedService(TaskRepository tasks, IdentityStoreAuthorizationClient identityStores,
			TaskStoreEnrichment storeEnrichment, TaskReadAccess taskReadAccess, TaskReadEnrichment taskReadEnrichment) {
		this.tasks = tasks;
		this.identityStores = identityStores;
		this.storeEnrichment = storeEnrichment;
		this.taskReadAccess = taskReadAccess;
		this.taskReadEnrichment = taskReadEnrichment;
	}

	public Mono<Map<String, Object>> feed(String platform, String contentForm, Long minBountyCents, Double latitude,
			Double longitude, Double maxDistanceKm, String q, String cursor, int limit, Caller caller) {
		String query = TaskBodies.searchQuery(q);

		int safeLimit = Math.max(1, Math.min(limit, 50));
		FeedCursor decoded = FeedCursor.decode(cursor);
		boolean anyDistance = latitude != null || longitude != null || maxDistanceKm != null;
		if (anyDistance && !validDistanceQuery(latitude, longitude, maxDistanceKm)) {
			return Mono.error(new MarketplaceException(400, "距离筛选需提供有效经纬度，范围须在 0.1 至 200 公里之间"));
		}
		Mono<List<IdentityStoreAuthorizationClient.NearbyStore>> nearby = anyDistance
				? identityStores.nearby(latitude, longitude, maxDistanceKm)
				: Mono.just(List.of());
		return Mono.zip(taskReadAccess.visibleRecommenderLevel(caller), nearby).flatMap(tuple -> {
			List<IdentityStoreAuthorizationClient.NearbyStore> nearbyStores = tuple.getT2();
			if (anyDistance && nearbyStores.isEmpty()) {
				return Mono.just(feedBody(List.of(), safeLimit, Map.of(), Map.of()));
			}
			List<String> storeIds = anyDistance
					? nearbyStores.stream().map(IdentityStoreAuthorizationClient.NearbyStore::storeId).toList()
					: null;
			Map<String, Double> distances = nearbyStores.stream()
					.collect(Collectors.toMap(IdentityStoreAuthorizationClient.NearbyStore::storeId,
							IdentityStoreAuthorizationClient.NearbyStore::distanceKm, Math::min));
			TaskRepository.FeedFilter filter = new TaskRepository.FeedFilter(TaskBodies.blankToNull(platform),
					TaskBodies.blankToNull(contentForm),
					(minBountyCents == null || minBountyCents < 0) ? null : minBountyCents, tuple.getT1(), storeIds,
					query);
			return tasks.findFeed(filter, decoded == null ? null : decoded.ts(), decoded == null ? null : decoded.id(),
					safeLimit + 1).collectList().flatMap(rows -> enrichFeed(rows, safeLimit, distances));
		});
	}

	private Mono<Map<String, Object>> enrichFeed(List<Task> rows, int limit, Map<String, Double> distances) {
		boolean hasMore = rows.size() > limit;
		List<Task> page = hasMore ? rows.subList(0, limit) : rows;
		List<String> pageStoreIds = page.stream().map(Task::storeId).filter(java.util.Objects::nonNull).distinct()
				.toList();
		return storeEnrichment.loadStoreBlocks(pageStoreIds).map(stores -> feedBody(rows, limit, distances, stores))
				.flatMap(data -> taskReadEnrichment.withCommerceSummaries(feedItems(data)).map(enriched -> {
					data.put("items", enriched);
					return data;
				}))
				// 任务书 #98 C98-04：商家信用摘要内嵌 + 同分软排序（良好=0/正常与样本不足=1/关注=2；
				// 仅同一 created_at 组内重排——分页游标仍按原始页边界编码，展示序不影响翻页语义）。
				.flatMap(data -> taskReadEnrichment.withMerchantCredits(feedItems(data)).map(credits -> {
					softSortByMerchantCredit(data, credits);
					return data;
				}));
	}

	private static void softSortByMerchantCredit(Map<String, Object> feedData,
			Map<String, com.grassland.marketplace.reputation.MerchantCreditService.MerchantCredit> credits) {
		List<Map<String, Object>> items = feedItems(feedData);
		// feedBody 的 items 是 Stream.toList() 不可变列表——拷贝重排后整键替换。
		List<Map<String, Object>> sorted = new java.util.ArrayList<>(items);
		for (int start = 0; start < sorted.size();) {
			Object createdAt = sorted.get(start).get("createdAt");
			int end = start + 1;
			while (end < sorted.size() && java.util.Objects.equals(sorted.get(end).get("createdAt"), createdAt)) {
				end++;
			}
			if (end - start > 1) {
				List<Map<String, Object>> group = new java.util.ArrayList<>(sorted.subList(start, end));
				group.sort(java.util.Comparator.comparingInt(item -> ordinalOf(item, credits)));
				for (int offset = 0; offset < group.size(); offset++) {
					sorted.set(start + offset, group.get(offset));
				}
			}
			start = end;
		}
		feedData.put("items", sorted);
	}

	private static int ordinalOf(Map<String, Object> item,
			Map<String, com.grassland.marketplace.reputation.MerchantCreditService.MerchantCredit> credits) {
		var credit = credits.get((String) item.get("organizationId"));
		return credit == null ? 1 : credit.sortOrdinal();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> feedItems(Map<String, Object> feedData) {
		return (List<Map<String, Object>>) feedData.get("items");
	}

	private Map<String, Object> feedBody(List<Task> rows, int limit, Map<String, Double> distances,
			Map<String, Map<String, Object>> stores) {
		boolean hasMore = rows.size() > limit;
		List<Task> page = hasMore ? rows.subList(0, limit) : rows;
		String nextCursor = hasMore && !page.isEmpty() ? FeedCursor.encode(page.get(page.size() - 1)) : null;
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("items", page.stream().map(task -> {
			Map<String, Object> body = TaskBodies.toBody(task);
			if (task.storeId() != null && distances.containsKey(task.storeId())) {
				body.put("distanceKm", Math.round(distances.get(task.storeId()) * 10d) / 10d);
			}
			if (task.storeId() != null && stores.containsKey(task.storeId())) {
				body.put("store", stores.get(task.storeId()));
			}
			return body;
		}).toList());
		data.put("nextCursor", nextCursor);
		data.put("hasMore", hasMore);
		return data;
	}

	private static boolean validDistanceQuery(Double latitude, Double longitude, Double radiusKm) {
		return latitude != null && longitude != null && radiusKm != null && Double.isFinite(latitude) && latitude >= -90
				&& latitude <= 90 && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
				&& Double.isFinite(radiusKm) && radiusKm >= 0.1 && radiusKm <= 200;
	}
	record FeedCursor(Instant ts, String id) {
		static String encode(Task task) {
			String raw = task.createdAt().toString() + "|" + task.id();
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		static FeedCursor decode(String cursor) {
			if (cursor == null || cursor.isBlank()) {
				return null;
			}
			try {
				String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				int sep = raw.lastIndexOf('|');
				if (sep <= 0 || sep == raw.length() - 1) {
					return null;
				}
				return new FeedCursor(Instant.parse(raw.substring(0, sep)), raw.substring(sep + 1));
			} catch (Exception error) {
				return null;
			}
		}
	}
}
