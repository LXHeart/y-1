package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * HTTP binding and caller resolution only; business responsibilities live in
 * application services.
 */
@RestController
public class TaskController {
	private final MarketplaceCallerResolver callers;
	private final TaskCancellationService taskCancellationService;
	private final TaskDraftService taskDraftService;
	private final TaskPromotionService taskPromotionService;
	private final TaskPublicationService taskPublicationService;
	private final TaskRevisionService taskRevisionService;
	public TaskController(MarketplaceCallerResolver callers, TaskCancellationService taskCancellationService,
			TaskDraftService taskDraftService, TaskPromotionService taskPromotionService,
			TaskPublicationService taskPublicationService, TaskRevisionService taskRevisionService) {
		this.callers = callers;
		this.taskCancellationService = taskCancellationService;
		this.taskDraftService = taskDraftService;
		this.taskPromotionService = taskPromotionService;
		this.taskPublicationService = taskPublicationService;
		this.taskRevisionService = taskRevisionService;
	}
	@PostMapping(value = "/api/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateTaskRequest body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskPublicationService.create(body, caller)).map(
				value -> ResponseEntity.status(201).body(Map.of("success", true, "data", TaskBodies.toBody(value))));
	}

	@PostMapping(value = "/api/tasks/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> createDraft(@RequestBody CreateDraftRequest body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskDraftService.createDraft(body, caller)).map(
				value -> ResponseEntity.status(201).body(Map.of("success", true, "data", TaskBodies.toBody(value))));
	}

	@PutMapping(value = "/api/tasks/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String id,
			@RequestBody UpdateTaskRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskDraftService.update(id, body, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", TaskBodies.toBody(value))));
	}

	@PostMapping(value = "/api/tasks/{id}/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> publish(@PathVariable String id,
			@RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskPublicationService.publish(id, body, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", TaskBodies.toBody(value))));
	}

	@PostMapping("/api/tasks/{id}/close")
	public Mono<ResponseEntity<Map<String, Object>>> close(@PathVariable String id,
			@RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskPublicationService.close(id, body, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", TaskBodies.toBody(value))));
	}

	@PostMapping("/api/tasks/{id}/end-promotion")
	public Mono<ResponseEntity<Map<String, Object>>> endPromotion(@PathVariable String id,
			@RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskPromotionService.endPromotion(id, body, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", TaskBodies.toBody(value))));
	}

	@PostMapping("/api/tasks/{id}/cancel")
	public Mono<ResponseEntity<Map<String, Object>>> cancel(@PathVariable String id,
			@RequestBody TaskLifecycleRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskCancellationService.cancel(id, body, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", TaskBodies.cancelBody(value))));
	}

	@PostMapping(value = "/api/tasks/{id}/revise", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> revise(@PathVariable String id,
			@RequestBody ReviseTaskRequest body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> taskRevisionService.revise(id, body, caller))
				.map(value -> ResponseEntity.ok(Map.of("success", true, "data", TaskBodies.toBody(value))));
	}

}
