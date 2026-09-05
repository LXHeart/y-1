package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 推荐官「我的报名」历史列表（任务书 #29+#30 Stage 2 / D5；#77 卡 D 扩展）。
 *
 * <p>
 * {@code GET /api/tasks/my-applications?status=&settled=&cursor=&limit=} —
 * 跨任务列出当前推荐官的报名， join task 取标题/状态/赏金/平台。self-scoped：recommenderAccountId 烧进 SQL
 * WHERE（HLD 7.4）， 任何人只能看到自己的报名。keyset
 * 游标分页（{@code created_at DESC, id DESC}），形状同 feed {@code {items, nextCursor,
 * hasMore}}。
 *
 * <p>
 * #77 卡 D 扩展（「我的履约」改造为「我的任务」全量主列表）：{@code status} 支持逗号分隔多值 （向后兼容单值；任一非法值仍
 * 400）；新增 {@code settled} 布尔过滤——非空时按 LATERAL join 出的 结算事件有无过滤（「完成」=
 * settled=true；报名成功在途 = accepted + settled=false）； itemBody 补 platform /
 * storeName / city（门店块经 {@link TaskStoreEnrichment} 按 identity 批量增强）。
 *
 * <p>
 * 路由挂在既有 {@code /api/tasks/**} 前缀下（edge-bff 已注册，不新增公网前缀，D5）； 字面量段
 * {@code my-applications} 在 PathPattern 优先于 {@code {id}} 模板，不会被任务详情抢走。
 */
@RestController
public class MyApplicationsController {

	private final MarketplaceCallerResolver callers;
	private final TaskApplicationRepository apps;
	private final TaskStoreEnrichment storeEnrichment;

	public MyApplicationsController(MarketplaceCallerResolver callers, TaskApplicationRepository apps,
			TaskStoreEnrichment storeEnrichment) {
		this.callers = callers;
		this.apps = apps;
		this.storeEnrichment = storeEnrichment;
	}

	@GetMapping("/api/tasks/my-applications")
	public Mono<ResponseEntity<Map<String, Object>>> myApplications(@RequestParam(required = false) String status,
			@RequestParam(required = false) Boolean settled, @RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "20") int limit, ServerHttpRequest request) {
		final List<String> statusFilter = parseStatuses(status);
		int safeLimit = Math.max(1, Math.min(limit, 50));
		MyCursor decoded = MyCursor.decode(cursor);
		return callers.requireRecommender(request)
				.flatMap(rec -> apps.findMyApplications(rec.accountId(), statusFilter, settled,
						decoded == null ? null : decoded.ts(), decoded == null ? null : decoded.id(), safeLimit + 1)
						.collectList()
						// 门店块按页内 storeId 去重批量拉（identity 不可用时降级空 map，不阻断列表）
						.flatMap(rows -> storeEnrichment
								.loadStoreBlocks(rows.stream().map(TaskApplicationRepository.MyApplicationRow::storeId)
										.filter(Objects::nonNull).collect(Collectors.toSet()))
								.map(blocks -> body(rows, blocks, safeLimit))));
	}

	/** 组装分页体：取 limit+1 判 hasMore，nextCursor 为本页最后一行的 (created_at, id)。 */
	private ResponseEntity<Map<String, Object>> body(List<TaskApplicationRepository.MyApplicationRow> rows,
			Map<String, Map<String, Object>> storeBlocks, int limit) {
		boolean hasMore = rows.size() > limit;
		List<TaskApplicationRepository.MyApplicationRow> page = hasMore ? rows.subList(0, limit) : rows;
		String nextCursor = hasMore && !page.isEmpty() ? MyCursor.encode(page.get(page.size() - 1)) : null;
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("items", page.stream().map(row -> itemBody(row, storeBlocks)).toList());
		data.put("nextCursor", nextCursor);
		data.put("hasMore", hasMore);
		return ResponseEntity.ok(Map.of("success", true, "data", data));
	}

	private static Map<String, Object> itemBody(TaskApplicationRepository.MyApplicationRow row,
			Map<String, Map<String, Object>> storeBlocks) {
		Map<String, Object> store = row.storeId() == null ? null : storeBlocks.get(row.storeId());
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("applicationId", row.applicationId());
		map.put("taskId", row.taskId());
		map.put("taskTitle", row.taskTitle());
		map.put("taskStatus", row.taskStatus());
		map.put("applicationStatus", row.applicationStatus());
		map.put("bountyCents", row.bountyCents());
		map.put("appliedAt", row.appliedAt() == null ? null : row.appliedAt().toString());
		map.put("settledAt", row.settledAt() == null ? null : row.settledAt().toString());
		map.put("platform", row.platform());
		map.put("storeName", store == null ? null : store.get("storeName"));
		map.put("city", store == null ? null : store.get("city"));
		return map;
	}

	/**
	 * #77 卡 D：status 参数 → 校验过的多值列表。逗号分隔（单值天然兼容）；空串/全空白归 null（不过滤）； 任一非法值
	 * 400（单值时代的语义不变）。
	 */
	private static List<String> parseStatuses(String status) {
		String normalized = blankToNull(status);
		if (normalized == null) {
			return List.of();
		}
		return Arrays.stream(normalized.split(",")).map(String::trim).filter(token -> !token.isEmpty()).map(token -> {
			try {
				return ApplicationStatus.fromDb(token).dbValue();
			} catch (IllegalArgumentException e) {
				throw new MarketplaceException(400, "不支持的报名状态筛选：" + token);
			}
		}).toList();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/**
	 * keyset 游标：opaque base64url 编码 {@code createdAt|id}（镜像 feed
	 * FeedCursor；坏游标当首页）。
	 */
	record MyCursor(Instant ts, String id) {
		static String encode(TaskApplicationRepository.MyApplicationRow row) {
			String raw = row.appliedAt().toString() + "|" + row.applicationId();
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		static MyCursor decode(String cursor) {
			if (cursor == null || cursor.isBlank()) {
				return null;
			}
			try {
				String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				int sep = raw.lastIndexOf('|');
				if (sep <= 0 || sep == raw.length() - 1) {
					return null;
				}
				return new MyCursor(Instant.parse(raw.substring(0, sep)), raw.substring(sep + 1));
			} catch (Exception error) {
				return null;
			}
		}
	}
}
