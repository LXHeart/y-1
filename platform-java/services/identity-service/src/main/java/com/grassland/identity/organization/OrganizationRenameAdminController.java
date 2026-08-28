package com.grassland.identity.organization;

import com.grassland.identity.admin.PageEnvelope;
import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 商家主体更名的平台审核端点（治理台）。独立控制器：OrganizationController 有
 * 类级 {@code @RequestMapping("/api/organizations")} 前缀，绝对路径会被拼坏。
 */
@RestController
public class OrganizationRenameAdminController {

	private final OrganizationRenameRepository renames;
	private final OrganizationRepository organizations;
	private final CurrentAccountResolver accounts;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	public OrganizationRenameAdminController(OrganizationRenameRepository renames, OrganizationRepository organizations,
			CurrentAccountResolver accounts, OutboxRepository outbox, TransactionalOperator transactions) {
		this.renames = renames;
		this.organizations = organizations;
		this.accounts = accounts;
		this.outbox = outbox;
		this.transactions = transactions;
	}


	@GetMapping("/api/admin/org-rename-requests")
	public Mono<ResponseEntity<Map<String, Object>>> adminListPending(
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
			ServerHttpRequest request) {
		int pageSize = PageEnvelope.limit(limit);
		int pageOffset = PageEnvelope.offset(offset);
		return accounts.requireAdmin(request)
				.flatMap(admin -> Mono.zip(renames.findPendingAll(pageSize, pageOffset).collectList(),
						renames.countPendingAll())
						.map(tuple -> ResponseEntity.ok(Map.of("success", true, "data", PageEnvelope
								.data(tuple.getT1().stream().map(this::toRenameBody).toList(),
										tuple.getT2(), pageSize, pageOffset)))));
	}

	/** 审核：approve = 更名生效（同事务 UPDATE organization + 终态 + outbox）；reject = 驳回留痕。 */
	@PostMapping("/api/admin/org-rename-requests/{id}/review")
	public Mono<ResponseEntity<Map<String, Object>>> adminReview(@PathVariable String id,
			@RequestBody RenameReviewRequest body, ServerHttpRequest request) {
		if (body == null || body.decision() == null
				|| !(body.decision().equals("approve") || body.decision().equals("reject"))) {
			return Mono.just(ResponseEntity.badRequest().body(Map.of("success", false, "error", "decision 必须是 approve 或 reject")));
		}
		return accounts.requireAdmin(request)
				.flatMap(admin -> transactions.transactional(renames.findById(id)
						.switchIfEmpty(Mono.error(new IdentityException(404, "申请不存在")))
						.flatMap(req -> {
							if (!"pending".equals(req.status())) {
								return Mono.error(new IdentityException(409, "该申请已审核过"));
							}
							if (body.decision().equals("reject")) {
								return renames.review(id, "rejected", admin.id(), body.note());
							}
							return organizations.updateName(req.organizationId(), req.requestedName())
									.then(renames.review(id, "approved", admin.id(), body.note()))
									.flatMap(reviewed -> outbox.append(new EventEnvelope(
											UUID.randomUUID().toString(), "OrganizationRenamed",
											"Organization", req.organizationId(), 1, Instant.now(), null,
											Map.of("organizationId", req.organizationId(),
													"fromName", req.currentName(),
													"toName", req.requestedName(),
													"requestId", id)))
											.thenReturn(reviewed));
						})))
				.map(reviewed -> ResponseEntity.ok(Map.of("success", true, "data", toRenameBody(reviewed))));
	}

	@ExceptionHandler(IdentityException.class)
	ResponseEntity<Map<String, Object>> handle(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	private Map<String, Object> toRenameBody(OrganizationRenameRepository.RenameRequest req) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", req.id());
		body.put("organizationId", req.organizationId());
		body.put("currentName", req.currentName());
		body.put("requestedName", req.requestedName());
		body.put("status", req.status());
		body.put("requestedAt", req.requestedAt() == null ? null : req.requestedAt().toString());
		body.put("reviewedAt", req.reviewedAt() == null ? null : req.reviewedAt().toString());
		body.put("reviewNote", req.reviewNote());
		return body;
	}

	public record RenameReviewRequest(String decision, String note) {
	}
}
