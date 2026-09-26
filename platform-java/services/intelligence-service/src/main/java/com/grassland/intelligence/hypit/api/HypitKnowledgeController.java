package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hypit 知识/模板/导入端点（任务书 #107-1 §6.2 / C107-03 声明；C107-14/17/20 接实现）。
 */
@RestController
public class HypitKnowledgeController {

	private final IntelligenceCallerResolver callers;
	private final HypitAccessService access;
	private final HypitProperties properties;

	private final HypitSidecarClient sidecar;
	private final com.grassland.intelligence.hypit.agent.HypitKnowledgeService knowledgeService;
	private final com.grassland.intelligence.hypit.template.HypitTemplateService templateService;
	private final com.grassland.intelligence.hypit.template.HypitProjectPackageService packageService;

	public HypitKnowledgeController(IntelligenceCallerResolver callers, HypitAccessService access,
			HypitProperties properties, HypitSidecarClient sidecar,
			com.grassland.intelligence.hypit.agent.HypitKnowledgeService knowledgeService,
			com.grassland.intelligence.hypit.template.HypitTemplateService templateService,
			com.grassland.intelligence.hypit.template.HypitProjectPackageService packageService) {
		this.callers = callers;
		this.access = access;
		this.properties = properties;
		this.sidecar = sidecar;
		this.knowledgeService = knowledgeService;
		this.templateService = templateService;
		this.packageService = packageService;
	}

	private <T> Mono<T> pending(String what) {
		return Mono.error(properties.enabled() ? HypitAccessService.unavailable(what) : HypitAccessService.disabled());
	}

	/**
	 * C107-14：知识检索——q/topic 检索或 path 按需读取原文（逐字节 sha256 校验）。 登录可读；prompt
	 * 组装只注入命中文档（14.2 不塞全仓库）。
	 */
	@GetMapping("/api/hypit/knowledge")
	public Mono<ResponseEntity<Map<String, Object>>> knowledge(
			@org.springframework.web.bind.annotation.RequestParam(required = false) String q,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String topic,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String path,
			@org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int limit,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> {
			if (path != null && !path.isBlank()) {
				return knowledgeService.read(path).map(text -> Map.<String, Object>of("path", path, "document", text,
						"sourceCommit", String.valueOf(knowledgeService.sourceCommit())));
			}
			return knowledgeService.search(topic, q, limit)
					.map(hits -> Map.<String, Object>of("hits", hits.stream().map(hit -> {
						Map<String, Object> dto = new java.util.LinkedHashMap<>();
						dto.put("path", hit.doc().path());
						dto.put("title", hit.doc().title());
						dto.put("topic", hit.doc().topic());
						if (hit.snippet() != null) {
							dto.put("snippet", hit.snippet());
						}
						return dto;
					}).toList(), "documentCount", knowledgeService.documentCount(), "sourceCommit",
							String.valueOf(knowledgeService.sourceCommit())));
		}).map(body -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
				.body(HypitDtos.success(body)));
	}

	/**
	 * C107-08：词汇表——真实 distribution 的 provider/capability/model/program 目录 （卡步骤
	 * 9：数据来自实际 distribution 与当前项目，不扫描未选外部包）。 C107-17 接管契约归属： 可选 projectId
	 * 随载荷下发（引擎侧词汇来自 distribution 全局目录；工程已安装包的 facet 明细由 GET
	 * /api/hypit/runtime/packages 的 packages.status 提供）。
	 */
	@GetMapping("/api/hypit/vocabulary")
	public Mono<ResponseEntity<Map<String, Object>>> vocabulary(
			@org.springframework.web.bind.annotation.RequestParam(required = false) String projectId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> {
			if (!properties.enabled()) {
				return Mono.error(HypitAccessService.disabled());
			}
			Map<String, Object> payload = projectId == null || projectId.isBlank()
					? Map.of()
					: Map.of("projectId", projectId);
			return sidecar.commandAsync("java-vocabulary-" + UUID.randomUUID(), "vocabulary", payload).map(command -> {
				if (command.result() == null) {
					throw new com.grassland.intelligence.security.IntelligenceException(
							org.springframework.http.HttpStatus.BAD_GATEWAY.value(), "hypit_engine_error",
							"vocabulary 命令失败");
				}
				return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
						.body(HypitDtos.success(HypitJson.mapValue(command.result())));
			});
		});
	}

	/** C107-20：模板目录——sidecar templates.list 读 broker 侧 catalog（不复制第二份）。 */
	@GetMapping("/api/hypit/templates")
	public Mono<ResponseEntity<Map<String, Object>>> templates(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> templateService.list())
				.map(items -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
						.body(HypitDtos.success(Map.of("items", items))));
	}

	/** C107-20：模板详情（materialState 如实透传）。 */
	@GetMapping("/api/hypit/templates/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> template(@PathVariable String id, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> templateService.detail(id))
				.map(detail -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
						.body(HypitDtos.success(detail)));
	}

	/**
	 * C107-20：工程导入——sidecar project-package.import（manifest/hash 门禁在 broker
	 * 真值）；被拒导入不产生 ready 工程。operator 专属（全局资源操作）。
	 */
	@PostMapping("/api/hypit/imports")
	public Mono<ResponseEntity<Map<String, Object>>> importProject(
			@org.springframework.web.bind.annotation.RequestBody(required = false) ImportRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(access::requireOperator)
				.flatMap(caller -> packageService.import_(caller.accountId(),
						body == null || body.requestId() == null ? UUID.randomUUID() : body.requestId(),
						body == null ? "" : body.artifactRoot()))
				.map(result -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.cacheControl(org.springframework.http.CacheControl.noStore()).body(HypitDtos.success(result)));
	}

	public record ImportRequest(UUID requestId, String artifactRoot) {
	}

	private static <T> ResponseEntity<Map<String, Object>> neverMap(T ignored) {
		throw new AssertionError("unreachable: pending() always errors");
	}
}
