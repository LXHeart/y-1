package com.grassland.intelligence.contentlibrary;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.intelligence.admin.PageEnvelope;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Instant;

/**
 * 公共素材库审核 API（草场 PRD §4.8 / Slice 14，GL-P2-ADMIN-003 同款全审政策）。
 *
 * <p>
 * <b>为什么独立成类</b>：这三个端点原先挂在 {@link ContentAssetController} 上，方法级写的是绝对路径
 * {@code /api/admin/content-assets/...}，但那个类有类级
 * {@code @RequestMapping("/api/content-assets")}。 Spring 是拼接而非覆盖，实际注册成了
 * {@code /api/content-assets/api/admin/content-assets/...} ——
 * 整条审核链路在生产上不可达（edge 的 {@code EDGE_ROUTE_ADMIN_CONTENT_ASSETS_INTELLIGENCE}
 * 转发过来只会 404）， 公共库素材永远卡在 pending_review。类级前缀独立是唯一能让这两条 edge 路由都成立的结构。
 *
 * <p>
 * 鉴权：intelligence 没有全局 SecurityWebFilterChain，逐 controller 约定。三个端点全部
 * {@code requireRole(CONTENT_REVIEWER)}（PLATFORM_ADMIN 是超集）。
 */
@RestController
@RequestMapping("/api/admin/content-assets")
public class ContentAssetAdminController {

	/** 审核请求体：乐观锁版本 + 驳回理由（approve 可空，reject 必填）。 */
	public record ReviewRequest(Integer expectedVersion, String note) {
	}

	private final IntelligenceCallerResolver callers;
	private final ContentAssetRepository assets;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final com.grassland.intelligence.embedding.ContentAssetIndexingHooks indexingHooks;
	private final PublicAssetBatchGenerationService batchGeneration;

	public ContentAssetAdminController(IntelligenceCallerResolver callers, ContentAssetRepository assets,
			OutboxRepository outbox, TransactionalOperator transactions,
			com.grassland.intelligence.embedding.ContentAssetIndexingHooks indexingHooks,
			PublicAssetBatchGenerationService batchGeneration) {
		this.callers = callers;
		this.assets = assets;
		this.outbox = outbox;
		this.transactions = transactions;
		this.indexingHooks = indexingHooks;
		this.batchGeneration = batchGeneration;
	}

	public record BatchGenerateRequest(String kind, String theme, String style, Integer count, Instant validUntil) {
	}

	/**
	 * AI batch generation is content-reviewer scoped and intentionally returns
	 * partial success.
	 */
	@PostMapping("/batch-generate")
	public Mono<ResponseEntity<Map<String, Object>>> batchGenerate(@RequestBody BatchGenerateRequest body,
			ServerWebExchange exchange) {
		return callers.requireRole(exchange.getRequest(), BackendRole.CONTENT_REVIEWER).flatMap(caller -> {
			if (body == null || body.theme() == null || body.theme().isBlank() || body.theme().trim().length() > 100) {
				return Mono.error(new IntelligenceException(400, "主题长度需为 1-100 字符"));
			}
			String style = body.style() == null || body.style().isBlank() ? null : body.style().trim();
			if (style != null && style.length() > 100) {
				return Mono.error(new IntelligenceException(400, "风格描述不能超过 100 字符"));
			}
			int count = body.count() == null ? 0 : body.count();
			if (count < 1 || count > 12) {
				return Mono.error(new IntelligenceException(400, "生成数量需为 1-12"));
			}
			Instant now = Instant.now();
			if (body.validUntil() == null || !body.validUntil().isAfter(now)
					|| body.validUntil().isAfter(now.plus(java.time.Duration.ofDays(90)))) {
				return Mono.error(new IntelligenceException(400, "有效期必须在未来 90 天内"));
			}
			PublicAssetBatchGenerationService.Command command = new PublicAssetBatchGenerationService.Command(
					PublicAssetBatchGenerationService.Kind.parse(body.kind()), body.theme().trim(), style, count,
					body.validUntil());
			return batchGeneration.generate(caller.accountId(), command);
		}).map(result -> {
			Map<String, Object> data = new java.util.LinkedHashMap<>();
			data.put("items", result.items().stream().map(item -> {
				Map<String, Object> value = new java.util.LinkedHashMap<>();
				value.put("index", item.index());
				value.put("ok", item.ok());
				value.put("assetId", item.assetId());
				value.put("errorReason", item.errorReason());
				return value;
			}).toList());
			data.put("okCount", result.okCount());
			return ContentAssetController.success(data);
		});
	}

	/** 列待审核公共素材（内容审核员队列）：统一分页信封。 */
	@GetMapping("/review")
	public Mono<ResponseEntity<Map<String, Object>>> reviewQueue(@RequestParam(required = false) String q,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
			ServerWebExchange exchange) {
		String query = ContentAssetController.searchQuery(q);
		int pageSize = PageEnvelope.limit(limit);
		int pageOffset = PageEnvelope.offset(offset);
		return callers.requireRole(exchange.getRequest(), BackendRole.CONTENT_REVIEWER)
				.flatMap(caller -> Mono.zip(
						assets.listPendingReview(pageSize, pageOffset, query).collectList(),
						assets.countPendingReview(query))
						.map(tuple -> ContentAssetController.success(PageEnvelope.data(
								tuple.getT1().stream().map(ContentAssetController::toResponse).toList(),
								tuple.getT2(), pageSize, pageOffset))));
	}

	/** 审核通过（pending_review→active）。乐观锁 + 同事务 outbox。 */
	@PostMapping("/{id}/review/approve")
	public Mono<ResponseEntity<Map<String, Object>>> reviewApprove(@PathVariable String id,
			@RequestBody ReviewRequest body, ServerWebExchange exchange) {
		return callers.requireRole(exchange.getRequest(), BackendRole.CONTENT_REVIEWER)
				.flatMap(caller -> approvePublic(id, caller, body)).map(ContentAssetController::success);
	}

	/** 审核驳回（pending_review→rejected，必填 note）。乐观锁 + 同事务 outbox。 */
	@PostMapping("/{id}/review/reject")
	public Mono<ResponseEntity<Map<String, Object>>> reviewReject(@PathVariable String id,
			@RequestBody ReviewRequest body, ServerWebExchange exchange) {
		return callers.requireRole(exchange.getRequest(), BackendRole.CONTENT_REVIEWER)
				.flatMap(caller -> rejectPublic(id, caller, body)).map(ContentAssetController::success);
	}

	private Mono<Map<String, Object>> approvePublic(String id, Caller caller, ReviewRequest body) {
		if (body == null || body.expectedVersion() == null) {
			return Mono.error(new IntelligenceException(400, "expectedVersion 不能为空"));
		}
		UUID assetId = ContentAssetController.parseUuid(id, "id");
		return assets.reviewApprove(assetId, body.expectedVersion(), caller.accountId())
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "素材状态已变化，请刷新后重试")))
				.flatMap(approved -> outbox
						.append(ContentAssetController.assetEvent("ContentAssetPublished", approved, caller.accountId(),
								null, caller.accountId()))
						.then(indexingHooks.onActiveAsset(approved)).thenReturn(approved))
				.as(transactions::transactional).map(ContentAssetController::toResponse);
	}

	private Mono<Map<String, Object>> rejectPublic(String id, Caller caller, ReviewRequest body) {
		if (body == null || body.expectedVersion() == null) {
			return Mono.error(new IntelligenceException(400, "expectedVersion 不能为空"));
		}
		String note = ContentAssetController.requireNonBlank(body.note(), "note");
		UUID assetId = ContentAssetController.parseUuid(id, "id");
		return assets.reviewReject(assetId, body.expectedVersion(), caller.accountId(), note)
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "素材状态已变化，请刷新后重试")))
				.flatMap(rejected -> outbox
						.append(ContentAssetController.assetEvent("ContentAssetRejected", rejected, caller.accountId(),
								note, caller.accountId()))
						.then(indexingHooks.onRemovedAsset(rejected.id())).thenReturn(rejected))
				.as(transactions::transactional).map(ContentAssetController::toResponse);
	}
}
