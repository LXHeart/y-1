package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 合作（engagement）提交物 HTTP 入口（任务书 #103 C103-21 自 TaskController 拆出）：
 * 发布前审稿草稿送审。端点自 {@code TaskController} <b>原样搬移</b>（路径/守卫/响应不变——
 * 路由只保留这一处映射）；领域编排仍在 {@link EngagementSubmissionService}。
 */
@RestController
public class EngagementSubmissionController {

	private final MarketplaceCallerResolver callers;
	private final TaskRepository tasks;
	private final TaskApplicationRepository apps;
	private final EngagementSubmissionService submissionService;

	public EngagementSubmissionController(MarketplaceCallerResolver callers, TaskRepository tasks,
			TaskApplicationRepository apps, EngagementSubmissionService submissionService) {
		this.callers = callers;
		this.tasks = tasks;
		this.apps = apps;
		this.submissionService = submissionService;
	}

	// ---------- 任务书 #96 C96-04：草稿送审（§6 /submissions/draft；发布前审稿） ----------

	/**
	 * 草稿送审（附件形态，不要求公开链接——TC96-015）。仅审稿合同任务可送审；同报名同时一份待审； 退改限次/补交期限守卫见
	 * {@link EngagementSubmissionService#submitDraft}。
	 */
	@PostMapping(value = "/api/tasks/{id}/applications/{appId}/submissions/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> submitDraft(@PathVariable String id, @PathVariable String appId,
			@RequestBody(required = false) DraftSubmissionRequest body, ServerHttpRequest request) {
		return callers.requireRecommender(request)
				.flatMap(caller -> apps.findById(appId).switchIfEmpty(fail(404, "报名不存在"))
						.filter(app -> app.taskId().equals(id)).switchIfEmpty(fail(404, "报名不存在"))
						.filter(app -> app.recommenderAccountId().equals(caller.accountId()))
						.switchIfEmpty(fail(403, "只能提交自己的草稿"))
						.flatMap(app -> tasks.findById(id).switchIfEmpty(fail(404, "任务不存在"))
								.filter(task -> !task.isCommercePromotion())
								.switchIfEmpty(fail(409, "套餐推广按订单结算，无需提交草稿")).flatMap(task -> {
									List<UUID> mediaIds = body == null || body.mediaIds() == null
											? List.of()
											: body.mediaIds();
									return submissionService
											.validateAttachments(task.organizationId(), caller.accountId(), appId,
													mediaIds)
											.flatMap(atts -> submissionService.submitDraft(task, app, caller,
													body == null ? null : body.note(), atts));
								}))
						.map(created -> ResponseEntity.status(201)
								.body(Map.of("success", true, "data", ApplicationBodies.toBody(created)))));
	}

	private static <T> Mono<T> fail(int status, String message) {
		return Mono.error(new MarketplaceException(status, message));
	}
}
