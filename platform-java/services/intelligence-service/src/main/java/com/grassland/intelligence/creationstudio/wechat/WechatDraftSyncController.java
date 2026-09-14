package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.intelligence.creationstudio.StudioRequestValidator;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository.SyncRow;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-26~31（C101-21）：公众号草稿同步 HTTP wire。 字段白名单封闭；payload 快照永不外泄；
 * 创建首次/进行中 202、终态重放 200；候选/核实/取消均为显式动作。
 */
@RestController
public class WechatDraftSyncController {

	private static final Set<String> CREATE_FIELDS = Set.of("requestId", "accountId", "expectedAccountVersion",
			"draftId", "draftVersion", "exportId", "author", "contentSourceUrl", "needOpenComment",
			"onlyFansCanComment");
	private static final Set<String> RECONCILE_FIELDS = Set.of("requestId", "expectedVersion", "externalDraftMediaId");
	private static final Set<String> CANCEL_FIELDS = Set.of("requestId", "expectedVersion");

	private final IntelligenceCallerResolver callers;
	private final WechatDraftSyncService syncs;

	public WechatDraftSyncController(IntelligenceCallerResolver callers, WechatDraftSyncService syncs) {
		this.callers = callers;
		this.syncs = syncs;
	}

	@PostMapping("/api/creation-channels/wechat/draft-syncs")
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, CREATE_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		UUID accountId = StudioRequestValidator.requireUuid(body, "accountId");
		int expectedAccountVersion = StudioRequestValidator.requirePositiveInt(body, "expectedAccountVersion");
		UUID draftId = StudioRequestValidator.requireUuid(body, "draftId");
		int draftVersion = StudioRequestValidator.requirePositiveInt(body, "draftVersion");
		UUID exportId = StudioRequestValidator.requireUuid(body, "exportId");
		String author = StudioRequestValidator.optionalString(body, "author", 64);
		String contentSourceUrl = optionalUrl(body);
		int needOpenComment = commentFlag(body, "needOpenComment");
		int onlyFansCanComment = commentFlag(body, "onlyFansCanComment");
		var command = new WechatDraftSyncService.CreateCommand(requestId, accountId, expectedAccountVersion, draftId,
				draftVersion, exportId, author, contentSourceUrl, needOpenComment, onlyFansCanComment);
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> syncs.create(caller, command))
				.map(row -> ResponseEntity.status(statusOf(row)).body(CreationWechatBodies.success(syncs.toBody(row))));
	}

	@GetMapping("/api/creation-channels/wechat/draft-syncs/{id}")
	public Mono<Map<String, Object>> get(@PathVariable String id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> syncs.get(caller, parseId(id)))
				.map(row -> CreationWechatBodies.success(syncs.toBody(row)));
	}

	@GetMapping("/api/creation-channels/wechat/draft-syncs")
	public Mono<Map<String, Object>> list(@RequestParam("draftId") String draftId,
			@RequestParam(value = "limit", defaultValue = "20") int limit,
			@RequestParam(value = "cursor", required = false) String cursor, ServerWebExchange exchange) {
		if (limit < 1 || limit > 50) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "limit 范围 1~50");
		}
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> syncs.list(caller, parseId(draftId), limit, cursor))
				.map(CreationWechatBodies::success);
	}

	@GetMapping("/api/creation-channels/wechat/draft-syncs/{id}/candidates")
	public Mono<Map<String, Object>> candidates(@PathVariable String id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> syncs.candidates(caller, parseId(id)))
				.map(result -> CreationWechatBodies.success(Map.of("items", result.items(), "searchedCount",
						result.searchedCount(), "hasMore", result.hasMore())));
	}

	@PostMapping("/api/creation-channels/wechat/draft-syncs/{id}/reconcile")
	public Mono<Map<String, Object>> reconcile(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, RECONCILE_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		int expectedVersion = StudioRequestValidator.requirePositiveInt(body, "expectedVersion");
		String externalDraftMediaId = StudioRequestValidator.requireString(body, "externalDraftMediaId", 256);
		if (externalDraftMediaId.isBlank()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "externalDraftMediaId 不能为空");
		}
		return callers.requireUser(exchange.getRequest()).flatMap(
				caller -> syncs.reconcile(caller, parseId(id), requestId, expectedVersion, externalDraftMediaId))
				.map(row -> CreationWechatBodies.success(syncs.toBody(row)));
	}

	@PostMapping("/api/creation-channels/wechat/draft-syncs/{id}/cancel")
	public Mono<Map<String, Object>> cancel(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, CANCEL_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		int expectedVersion = StudioRequestValidator.requirePositiveInt(body, "expectedVersion");
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> syncs.cancel(caller, parseId(id), requestId, expectedVersion))
				.map(row -> CreationWechatBodies.success(syncs.toBody(row)));
	}

	private static HttpStatus statusOf(SyncRow row) {
		return switch (row.state()) {
			case "succeeded", "failed", "unknown", "cancelled" -> HttpStatus.OK;
			default -> HttpStatus.ACCEPTED;
		};
	}

	private static String optionalUrl(Map<String, Object> body) {
		String url = StudioRequestValidator.optionalString(body, "contentSourceUrl", 512);
		if (url == null || url.isBlank()) {
			return null;
		}
		if (!url.startsWith("https://") && !url.startsWith("http://")) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "contentSourceUrl 须为 http(s) 链接");
		}
		return url;
	}

	/** §6.4：评论两个字段明确传 0/1（缺省 0；其他值 400）。 */
	private static int commentFlag(Map<String, Object> body, String field) {
		Integer value = StudioRequestValidator.optionalInt(body, field);
		if (value == null) {
			return 0;
		}
		if (value != 0 && value != 1) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", field + " 只允许 0 或 1");
		}
		return value;
	}

	private static UUID parseId(String id) {
		try {
			return UUID.fromString(id);
		} catch (Exception error) {
			throw new IntelligenceException(404, "STUDIO_NOT_FOUND", "同步不存在");
		}
	}
}
