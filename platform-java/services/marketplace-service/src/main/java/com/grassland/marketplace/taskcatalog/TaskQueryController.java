package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * HTTP binding and caller resolution only; business responsibilities live in
 * application services.
 */
@RestController
public class TaskQueryController {
	private final MarketplaceCallerResolver callers;
	private final TaskFeedService taskFeedService;
	private final TaskQueryService taskQueryService;
	public TaskQueryController(MarketplaceCallerResolver callers, TaskFeedService taskFeedService,
			TaskQueryService taskQueryService) {
		this.callers = callers;
		this.taskFeedService = taskFeedService;
		this.taskQueryService = taskQueryService;
	}
	@GetMapping("/api/tasks")
	public Mono<ResponseEntity<Map<String, Object>>> list(@RequestParam String organizationId,
			@RequestParam(required = false, defaultValue = "published") String status,
			@RequestParam(required = false) String storeId, @RequestParam(required = false) String q,
			ServerHttpRequest request) {
		return callers.resolve(request)
				.flatMap(caller -> taskQueryService.list(organizationId, status, storeId, q, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
	}

	@GetMapping("/api/tasks/analytics")
	public Mono<ResponseEntity<Map<String, Object>>> analytics(@RequestParam String organizationId,
			@RequestParam(required = false) String storeId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> taskQueryService.analytics(organizationId, storeId, from, to, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
	}

	@GetMapping("/api/tasks/feed")
	public Mono<ResponseEntity<Map<String, Object>>> feed(@RequestParam(required = false) String platform,
			@RequestParam(required = false) String contentForm, @RequestParam(required = false) Long minBountyCents,
			@RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude,
			@RequestParam(required = false) Double maxDistanceKm, @RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "20") int limit, ServerHttpRequest request) {
		return callers.resolve(request)
				.flatMap(caller -> taskFeedService.feed(platform, contentForm, minBountyCents, latitude, longitude,
						maxDistanceKm, q, cursor, limit, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
	}

	@GetMapping("/api/tasks/usage")
	public Mono<ResponseEntity<Map<String, Object>>> usage(@RequestParam String organizationId,
			ServerHttpRequest request) {
		return callers.requireMerchant(request).flatMap(caller -> taskQueryService.usage(organizationId, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
	}

	@GetMapping("/api/tasks/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerHttpRequest request) {
		return callers.resolve(request).flatMap(caller -> taskQueryService.get(id, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
	}

	@GetMapping("/api/tasks/{id}/preview")
	public Mono<ResponseEntity<Map<String, Object>>> preview(@PathVariable String id, ServerHttpRequest request) {
		return callers.resolve(request).flatMap(caller -> taskQueryService.preview(id, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
	}

}
