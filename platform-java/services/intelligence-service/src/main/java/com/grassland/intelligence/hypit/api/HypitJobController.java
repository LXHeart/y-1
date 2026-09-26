package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.job.HypitJobService;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hypit 统一任务端点（任务书 #107-1 §6.2 / K03）：全局路径校验 operator 且提交账号匹配； 项目别名由
 * HypitProjectController 承接。C107-03 只冻结协议，C107-04/14 接持久任务。
 */
@RestController
public class HypitJobController {

	private final IntelligenceCallerResolver callers;
	private final HypitAccessService access;
	private final HypitProperties properties;
	private final HypitJobService jobService;

	public HypitJobController(IntelligenceCallerResolver callers, HypitAccessService access, HypitProperties properties,
			HypitJobService jobService, com.grassland.intelligence.hypit.job.HypitJobActionRepository jobActions) {
		this.callers = callers;
		this.access = access;
		this.properties = properties;
		this.jobService = jobService;
		this.jobActions = jobActions;
	}

	private <T> Mono<T> pending(String what) {
		return Mono.error(properties.enabled() ? HypitAccessService.unavailable(what) : HypitAccessService.disabled());
	}

	// GET /api/hypit/jobs/{jobId}（全局任务查询）已在 C107-04 由 HypitProjectController 实现，
	// 此处不再重复映射（同路径双映射会让 WebFlux 启动即 Ambiguous mapping 失败）。

	/**
	 * C107-09：全局 job SSE（Build 详情页与任务页共用）。属主校验复用 jobById（owner 本人或 operator）；游标恢复按
	 * K09.3——Last-Event-ID 之后的事件续播，终帧即停。
	 */
	@GetMapping("/api/hypit/jobs/{jobId}/events")
	public reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> events(
			@PathVariable String jobId, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMapMany(caller -> jobService.jobById(caller, access, UUID.fromString(jobId)).flatMapMany(
						job -> jobService.tailing(job.accountId(), job.projectId(), job.id(), lastEventId)));
	}

	/** C107-14：任务动作日志——agent 每次工具调用的持久 action 行（本人/operator）。 */
	@GetMapping("/api/hypit/jobs/{jobId}/actions")
	public Mono<ResponseEntity<Map<String, Object>>> actions(@PathVariable String jobId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> jobService.jobById(caller, access, UUID.fromString(jobId))
						.flatMapMany(job -> jobActions.findByJob(job.id()))
						.map(action -> Map.<String, Object>of("stepIndex", action.stepIndex(), "kind", action.kind(),
								"state", action.state(), "inputHash", action.inputHash()))
						.collectList())
				.map(rows -> ResponseEntity.ok(HypitDtos.success(Map.of("actions", rows))));
	}

	private final com.grassland.intelligence.hypit.job.HypitJobActionRepository jobActions;

	@PostMapping("/api/hypit/jobs/{jobId}/actions")
	public Mono<ResponseEntity<Map<String, Object>>> submitAction(@PathVariable String jobId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> pending("任务动作提交"))
				.map(HypitJobController::neverMap);
	}

	private static <T> ResponseEntity<Map<String, Object>> neverMap(T ignored) {
		throw new AssertionError("unreachable: pending() always errors");
	}
}
