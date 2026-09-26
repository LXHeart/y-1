package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.asset.HypitAssetService;
import com.grassland.intelligence.hypit.asset.HypitResourceService;
import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hypit 素材/工具面端点（任务书 #107-1 §6.2；C107-03 声明，C107-05 落地：列表/导入/上传/ 内容 Range
 * 代理/URL 抓取/media.* 工具）。后续卡的端点维持禁用态如实 503。
 */
@RestController
public class HypitAssetController {

	private final IntelligenceCallerResolver callers;
	private final HypitAccessService access;
	private final HypitProperties properties;
	private final HypitAssetService assets;
	private final HypitResourceService resources;

	public HypitAssetController(IntelligenceCallerResolver callers, HypitAccessService access,
			HypitProperties properties, HypitAssetService assets, HypitResourceService resources) {
		this.callers = callers;
		this.access = access;
		this.properties = properties;
		this.assets = assets;
		this.resources = resources;
	}

	private <T> Mono<T> pending(String what) {
		return Mono.error(properties.enabled() ? HypitAccessService.unavailable(what) : HypitAccessService.disabled());
	}

	private static UUID requireUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException error) {
			throw new com.grassland.intelligence.security.IntelligenceException(400, "hypit_invalid_input", "id 格式非法。");
		}
	}

	@GetMapping("/api/hypit/projects/{projectId}/assets")
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String projectId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(assets.list(caller.accountId(), requireUuid(projectId))))
				.map(items -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
						.body(HypitDtos.success(Map.of("items", items))));
	}

	/** multipart 上传（K07：multipart 用 requestId 字段，不要求 Idempotency-Key header）。 */
	@PostMapping(value = "/api/hypit/projects/{projectId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> upload(@PathVariable String projectId,
			@RequestPart("file") FilePart file, @RequestPart("role") String role,
			@RequestPart("requestId") String requestId, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId).then(
						assets.upload(caller.accountId(), requireUuid(projectId), requireUuid(requestId), file, role)))
				.map(asset -> ResponseEntity.status(HttpStatus.ACCEPTED).body(HypitDtos.success(asset)));
	}

	/** JSON mediaId 导入（Content-Type 区分，§6.2）。 */
	@PostMapping(value = "/api/hypit/projects/{projectId}/assets", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> importMedia(@PathVariable String projectId,
			@RequestBody ImportMediaRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(assets.importMedia(caller.accountId(), requireUuid(projectId),
								requireUuid(body.requestId()), body.mediaId(), body.role())))
				.map(asset -> ResponseEntity.status(HttpStatus.ACCEPTED).body(HypitDtos.success(asset)));
	}

	public record ImportMediaRequest(String requestId, UUID mediaId, String role) {
	}

	@GetMapping("/api/hypit/projects/{projectId}/assets/{assetId}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String projectId, @PathVariable String assetId,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(assets.get(caller.accountId(), requireUuid(projectId), requireUuid(assetId))))
				.map(asset -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HypitDtos.success(asset)));
	}

	@DeleteMapping("/api/hypit/projects/{projectId}/assets/{assetId}")
	public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String projectId,
			@PathVariable String assetId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(assets.delete(caller.accountId(), requireUuid(projectId), requireUuid(assetId),
								idempotencyKey)))
				.map(ignored -> ResponseEntity.ok().body(HypitDtos.success(Map.of("deleted", true))));
	}

	/**
	 * 内容 Range 代理（§6.2：200/206/416；由 Java 鉴权后流式代理）。状态与 Content-Range 由 sidecar
	 * 资源层判定，本端点只做归属校验与字节透传，不复制判定。
	 */
	@GetMapping("/api/hypit/projects/{projectId}/assets/{assetId}/content")
	public Mono<Void> content(@PathVariable String projectId, @PathVariable String assetId,
			@RequestHeader(value = "Range", required = false) String range,
			@RequestHeader(value = "If-Range", required = false) String ifRange, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(assets.content(caller.accountId(), requireUuid(projectId), requireUuid(assetId), range)))
				.flatMap(response -> {
					exchange.getResponse().setStatusCode(HttpStatus.valueOf(response.status()));
					org.springframework.http.HttpHeaders headers = exchange.getResponse().getHeaders();
					headers.putAll(response.headers());
					headers.remove(org.springframework.http.HttpHeaders.TRANSFER_ENCODING);
					return exchange.getResponse().writeWith(response.body());
				});
	}

	@PostMapping("/api/hypit/projects/{projectId}/import-url")
	public Mono<ResponseEntity<Map<String, Object>>> importUrl(@PathVariable String projectId,
			@RequestBody ImportUrlRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(assets.importUrl(caller.accountId(), requireUuid(projectId),
								requireUuid(body.requestId()), body.url(), body.role())))
				.map(asset -> ResponseEntity.status(HttpStatus.ACCEPTED).body(HypitDtos.success(asset)));
	}

	public record ImportUrlRequest(String requestId, String url, String role) {
	}

	/** media.* 工具（C05 白名单在 service；未登记/未开放 400 unsupported_action）。 */
	@PostMapping("/api/hypit/projects/{projectId}/tools/{tool}")
	public Mono<ResponseEntity<Map<String, Object>>> tool(@PathVariable String projectId, @PathVariable String tool,
			@RequestBody ToolRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(assets.runTool(caller.accountId(), requireUuid(projectId), tool,
								requireUuid(body.requestId()), body.input())))
				.map(result -> ResponseEntity.status(HttpStatus.ACCEPTED).body(HypitDtos.success(result)));
	}

	public record ToolRequest(String requestId, Map<String, Object> input) {
	}
}
