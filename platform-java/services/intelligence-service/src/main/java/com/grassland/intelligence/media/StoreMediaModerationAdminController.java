package com.grassland.intelligence.media;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.intelligence.admin.PageEnvelope;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 门店媒体审核人工复核队列（缺口清偿之五遗留）：自动审核记 {@code review} 的媒体
 * 由内容审核员承接裁决——approve→pass（恢复公开展示）/ reject→blocked（公开端点过滤）。
 *
 * <p>
 * <b>为什么独立成类</b>：方法级/类级 {@code /api/admin} 绝对路径不能挂在带其它类级
 * {@code @RequestMapping} 的类上（Spring 是拼接，见 {@code ContentAssetAdminController}
 * 的生产事故注释），admin 端点必须独立成类。
 *
 * <p>
 * 鉴权对齐 {@code /api/admin/content-assets}：全部
 * {@code requireRole(CONTENT_REVIEWER)} （PLATFORM_ADMIN 超集）。乐观锁=moderatedAt
 * 期望值（裁决者看到的版本）， 不匹配 409（对齐 content-assets expectedVersion 口径）。人工裁决保留自动审核的
 * findings/model/run_id 证据列；无 outbox 事件——公开读侧是 JOIN 实时过滤， 裁决即刻生效，无下游消费者。
 */
@RestController
@RequestMapping("/api/admin/store-media-moderation")
public class StoreMediaModerationAdminController {

	private static final Set<String> STATUSES = Set.of("pass", "review", "blocked");
	/** 队列预览 URL TTL：短时即可，复核页刷新重取。 */
	private static final long PREVIEW_TTL_SECONDS = 300;

	/** 裁决请求体：approve 可不带备注，reject 必填（≤500，对齐 content-assets 口径）。 */
	public record ReviewRequest(String decision, String note, Instant expectedModeratedAt) {
	}

	private final IntelligenceCallerResolver callers;
	private final StoreMediaModerationRepository moderation;
	private final org.springframework.beans.factory.ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storage;

	public StoreMediaModerationAdminController(IntelligenceCallerResolver callers,
			StoreMediaModerationRepository moderation,
			org.springframework.beans.factory.ObjectProvider<com.grassland.storage.ObjectStorageAdapter> storage) {
		this.callers = callers;
		this.moderation = moderation;
		this.storage = storage;
	}

	/** 复核队列（默认 review；status 可选 pass/blocked 复查人工裁决史）：统一分页信封（保留 status 字段）。 */
	@GetMapping
	public Mono<Map<String, Object>> queue(@RequestParam(name = "status", required = false) String status,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
			ServerWebExchange exchange) {
		String target = status == null || status.isBlank() ? "review" : status;
		if (!STATUSES.contains(target)) {
			return Mono.error(new IntelligenceException(400, "status 仅支持 pass/review/blocked"));
		}
		int pageSize = PageEnvelope.limit(limit);
		int pageOffset = PageEnvelope.offset(offset);
		return callers.requireRole(exchange.getRequest(), BackendRole.CONTENT_REVIEWER)
				.flatMap(caller -> Mono.zip(moderation.listQueue(target, pageSize, pageOffset).collectList(),
						moderation.countQueue(target))
						.map(tuple -> {
						Map<String, Object> data = PageEnvelope.data(
								tuple.getT1().stream().map(item -> toQueueView(item, previewUrl(item))).toList(),
								tuple.getT2(), pageSize, pageOffset);
						data.put("status", target);
						return Map.of("success", true, "data", data);
					}));
	}

	/** 人工裁决：approve→pass（恢复展示）/ reject→blocked（拦截展示）。 */
	@PostMapping("/{mediaId}/review")
	public Mono<Map<String, Object>> review(@PathVariable String mediaId, @RequestBody ReviewRequest body,
			ServerWebExchange exchange) {
		if (body == null || body.expectedModeratedAt() == null) {
			return Mono.error(new IntelligenceException(400, "expectedModeratedAt 不能为空"));
		}
		boolean approve;
		if ("approve".equals(body.decision())) {
			approve = true;
		} else if ("reject".equals(body.decision())) {
			approve = false;
		} else {
			return Mono.error(new IntelligenceException(400, "decision 仅支持 approve/reject"));
		}
		String note = body.note() == null ? "" : body.note().trim();
		if (note.length() > 500) {
			return Mono.error(new IntelligenceException(400, "备注不能超过 500 字"));
		}
		if (!approve && note.isEmpty()) {
			return Mono.error(new IntelligenceException(400, "驳回门店媒体必须填写原因"));
		}
		UUID id = parseUuid(mediaId);
		return callers.requireRole(exchange.getRequest(), BackendRole.CONTENT_REVIEWER).flatMap(caller -> moderation
				.find(id).switchIfEmpty(Mono.error(new IntelligenceException(404, "该媒体无审核记录")))
				.flatMap(existing -> moderation.decide(id, approve ? "pass" : "blocked", body.expectedModeratedAt(),
						caller.accountId(), note.isEmpty() ? null : note, Instant.now()))
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "审核状态已变化，请刷新后重试"))).map(row -> {
					Map<String, Object> data = new java.util.LinkedHashMap<>();
					data.put("mediaId", id.toString());
					data.put("status", row.status());
					data.put("moderatedAt", row.moderatedAt());
					data.put("reviewedBy", caller.accountId());
					data.put("reviewedAt", Instant.now());
					data.put("reviewNote", note.isEmpty() ? null : note);
					return Map.of("success", true, "data", data);
				}));
	}

	/** 队列视图：object_key 不外泄，换成短时预览 URL（图片/视频均内联，复核页直看）。 */
	private Map<String, Object> toQueueView(StoreMediaModerationRepository.QueueRow row, String downloadUrl) {
		// LinkedHashMap 容忍 null（reviewed_*/预览 URL 均可空），Map.of 会 NPE
		Map<String, Object> view = new java.util.LinkedHashMap<>();
		view.put("mediaId", row.mediaReferenceId().toString());
		view.put("status", row.status());
		view.put("findings", findingsBody(row.findingsJson()));
		view.put("model", row.model());
		view.put("runId", row.runId());
		view.put("moderatedAt", row.moderatedAt());
		view.put("reviewedBy", row.reviewedBy());
		view.put("reviewedAt", row.reviewedAt());
		view.put("reviewNote", row.reviewNote());
		view.put("mimeType", row.mimeType());
		view.put("sizeBytes", row.sizeBytes());
		view.put("organizationId", row.organizationId());
		view.put("storeId", row.domainId());
		view.put("createdAt", row.mediaCreatedAt());
		view.put("downloadUrl", downloadUrl);
		return view;
	}

	/**
	 * findings JSON → 纯 List<Map>（响应体不塞 Jackson2 JsonNode——序列化行为不稳定，对齐
	 * contentsafety 口径）。
	 */
	private static List<Map<String, Object>> findingsBody(String findingsJson) {
		try {
			return MAPPER.readValue(findingsJson == null ? "[]" : findingsJson,
					new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
					});
		} catch (Exception error) {
			return List.of();
		}
	}

	/**
	 * 预览 URL：对象存储可用（object-storage.enabled）时短时签发；bean 不装配（本地无 MinIO 的上下文）或对象缺失时返回
	 * null——预览是复核的便利项，不阻塞队列（元数据与 findings 仍可裁）。
	 */
	private String previewUrl(StoreMediaModerationRepository.QueueRow row) {
		com.grassland.storage.ObjectStorageAdapter adapter = storage.getIfAvailable();
		if (adapter == null) {
			return null;
		}
		try {
			return adapter.presignDownload(row.objectKey(), PREVIEW_TTL_SECONDS).toString();
		} catch (Exception error) {
			return null;
		}
	}

	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw == null ? "" : raw.trim());
		} catch (Exception error) {
			throw new IntelligenceException(400, "mediaId 格式非法");
		}
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
}
