package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.admin.PageEnvelope;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.store.StoreProfileRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * KYB 审核流程 HTTP 入口（Admin）。GL-P3-MERCHANT-001。
 *
 * <ul>
 * <li>GET — 列出待审核请求（pending/under_review），需 admin 角色。</li>
 * <li>POST /{id}/approve — 批准审核，需 admin 角色。</li>
 * <li>POST /{id}/reject — 拒绝审核，需 admin 角色。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/kyb-requests")
public class KybVerificationController {

	private final CurrentAccountResolver accounts;
	private final KybVerificationRequestRepository requests;
	private final MerchantProfileRepository merchantProfiles;
	private final WithdrawalAccountRepository withdrawalAccounts;
	private final StoreProfileRepository storeProfiles;
	private final KybReviewDetailService details;
	private final KybMediaRetentionCommandRepository retentionCommands;
	private final KybMediaRetentionProperties retentionProperties;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	public KybVerificationController(CurrentAccountResolver accounts, KybVerificationRequestRepository requests,
			MerchantProfileRepository merchantProfiles, WithdrawalAccountRepository withdrawalAccounts,
			StoreProfileRepository storeProfiles, KybReviewDetailService details,
			KybMediaRetentionCommandRepository retentionCommands, KybMediaRetentionProperties retentionProperties,
			OutboxRepository outbox, TransactionalOperator transactions) {
		this.accounts = accounts;
		this.requests = requests;
		this.merchantProfiles = merchantProfiles;
		this.withdrawalAccounts = withdrawalAccounts;
		this.storeProfiles = storeProfiles;
		this.details = details;
		this.retentionCommands = retentionCommands;
		this.retentionProperties = retentionProperties;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> listPending(
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
			ServerHttpRequest request) {
		int pageSize = PageEnvelope.limit(limit);
		int pageOffset = PageEnvelope.offset(offset);
		return accounts.requireAdmin(request)
				.flatMap(admin -> Mono.zip(requests.findPending(pageSize, pageOffset).collectList(),
						requests.countPending())
						.map(tuple -> ResponseEntity.ok(Map.of("success", true,
								"data", PageEnvelope.data(tuple.getT1().stream().map(this::toBody).toList(),
										tuple.getT2(), pageSize, pageOffset)))));
	}

	@GetMapping("/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> detail(@PathVariable String id, ServerHttpRequest request) {
		return accounts.requireAdmin(request)
				.flatMap(admin -> requests.findById(KybSubmissionService.parseUuid(id, "审核请求 ID"))
						.switchIfEmpty(Mono.error(new IdentityException(404, "审核请求不存在"))))
				.flatMap(req -> details.load(req).map(detail -> {
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("request", toBody(req));
					data.put("subject", detail.subject());
					data.put("attachments", detail.attachments());
					return ResponseEntity.ok(Map.of("success", true, "data", data));
				}));
	}

	@GetMapping("/{id}/attachments/{attachmentId}/download-url")
	public Mono<ResponseEntity<Map<String, Object>>> attachmentDownload(@PathVariable String id,
			@PathVariable String attachmentId, ServerHttpRequest request) {
		return accounts.requireAdmin(request)
				.flatMap(admin -> requests.findById(KybSubmissionService.parseUuid(id, "审核请求 ID"))
						.switchIfEmpty(Mono.error(new IdentityException(404, "审核请求不存在"))))
				.flatMap(req -> details.issueDownload(req, KybSubmissionService.parseUuid(attachmentId, "附件 ID"))
						.map(download -> ResponseEntity.ok(Map.of("success", true, "data", download))));
	}

	@PostMapping(path = "/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> approve(@PathVariable String id, @RequestBody ReviewRequest body,
			ServerHttpRequest request) {
		return review(id, KybRequestStatus.APPROVED, body, request);
	}

	@PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> reject(@PathVariable String id, @RequestBody ReviewRequest body,
			ServerHttpRequest request) {
		return review(id, KybRequestStatus.REJECTED, body, request);
	}

	/**
	 * 审核裁定。approve/reject 唯一差别是终态值，故合并到一处。
	 *
	 * <p>
	 * <b>鉴权</b>：`requireAdmin` 是这里唯一的门禁——identity-service 无全局 security filter，
	 * 此前这三个端点接了 {@code ServerHttpRequest} 却从不鉴权，等于任何人可批准自己的 KYB。
	 *
	 * <p>
	 * <b>reviewer</b>：用真实 {@code admin.id()}。此前硬编码字符串 {@code "admin"} 绑进
	 * {@code reviewer_account_id = CAST(:reviewer AS uuid)}，真跑必 SQL 报错。
	 */
	private Mono<ResponseEntity<Map<String, Object>>> review(String id, KybRequestStatus decision, ReviewRequest body,
			ServerHttpRequest request) {
		return accounts.requireAdmin(request).flatMap(admin -> {
			UUID requestId = KybSubmissionService.parseUuid(id, "审核请求 ID");
			String note = requireReviewNote(decision, body);
			return transactions.transactional(requests.findById(requestId)
					.switchIfEmpty(Mono.error(new IdentityException(404, "审核请求不存在")))
					.flatMap(req -> KybRequestStatus.fromDb(req.status()).isTerminal()
							? Mono.<KybVerificationRequest>error(new IdentityException(409, "审核请求已处理"))
							: Mono.just(req))
					.flatMap(req -> decision == KybRequestStatus.APPROVED
							? details.requireCurrentEvidence(req).thenReturn(req)
							: Mono.just(req))
					.flatMap(req -> requests.updateStatus(req.id(), decision.dbValue(), admin.id(), note)
							.switchIfEmpty(Mono.error(new IdentityException(409, "审核请求已处理")))
							.flatMap(updated -> updateTargetStatus(updated, decision.dbValue(), admin.id(), note)
									.then(emitReviewEvent(updated, decision.dbValue(), note))
									.then(retentionCommands
											.sealReference(updated.id(), updated.organizationId(),
													retentionProperties.terminalDeadline(decision.dbValue(),
															Instant.now()))
											.flatMap(sealed -> requireCompleteRetentionSeal(updated, sealed)))
									.thenReturn(updated))));
		}).map(req -> ResponseEntity.ok(Map.of("success", true, "data", toBody(req))));
	}

	private Mono<Void> requireCompleteRetentionSeal(KybVerificationRequest request, long sealed) {
		int expected = details.evidenceCount(request);
		if (KybVerificationType.fromDb(request.verificationType()) == KybVerificationType.MERCHANT_PROFILE
				&& (expected == 0 || sealed != expected)) {
			return Mono.error(new IdentityException(409, "审核材料留存状态不完整"));
		}
		return Mono.empty();
	}

	private String requireReviewNote(KybRequestStatus decision, ReviewRequest body) {
		String note = body == null || body.note() == null ? null : body.note().trim();
		if (decision == KybRequestStatus.REJECTED && (note == null || note.isBlank())) {
			throw new IdentityException(400, "拒绝审核时必须填写原因");
		}
		if (note != null && note.length() > 500) {
			throw new IdentityException(400, "审核备注不能超过 500 个字符");
		}
		return note == null || note.isBlank() ? null : note;
	}

	/**
	 * 根据审核类型更新目标表状态。
	 *
	 * <p>
	 * {@code submittedAt} 传原值而非 null——`updateStatus` 是全字段 SET， 传 null
	 * 会把提交时间抹掉（审核完成后就看不出这单是什么时候提交的了）。
	 */
	private Mono<Void> updateTargetStatus(KybVerificationRequest req, String status, String adminId, String note) {
		KybVerificationType type = KybVerificationType.fromDb(req.verificationType());
		return switch (type) {
			case MERCHANT_PROFILE ->
				merchantProfiles.reviewStatus(req.organizationId(), status, Instant.now(), adminId, note)
						.switchIfEmpty(Mono.error(new IdentityException(409, "商家资料状态已变化"))).then();
			case WITHDRAWAL_ACCOUNT -> withdrawalAccounts
					.reviewStatus(req.targetId(), req.organizationId(), status, Instant.now(), adminId, note)
					.switchIfEmpty(Mono.error(new IdentityException(409, "收款账户状态已变化"))).then();
			case STORE_PROFILE -> storeProfiles
					.review(req.organizationId(), req.targetId().toString(), status, Instant.now(), adminId, note)
					.switchIfEmpty(Mono.error(new IdentityException(409, "门店资料状态已变化"))).then();
		};
	}

	/** 发送审核事件。 */
	private Mono<Void> emitReviewEvent(KybVerificationRequest req, String decision, String note) {
		KybVerificationType type = KybVerificationType.fromDb(req.verificationType());
		String eventType = switch (type) {
			case MERCHANT_PROFILE ->
				decision.equals("approved") ? "MerchantProfileApproved" : "MerchantProfileRejected";
			case WITHDRAWAL_ACCOUNT ->
				decision.equals("approved") ? "WithdrawalAccountApproved" : "WithdrawalAccountRejected";
			case STORE_PROFILE -> decision.equals("approved") ? "StoreProfileApproved" : "StoreProfileRejected";
		};
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("requestId", req.id().toString());
		payload.put("organizationId", req.organizationId());
		payload.put("decision", decision);
		payload.put("note", note != null ? note : "");
		return outbox.append(new EventEnvelope(UUID.randomUUID().toString(), eventType, type.aggregateType(),
				req.targetId() != null ? req.targetId().toString() : req.organizationId(), 1, Instant.now(), null,
				payload));
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	private Map<String, Object> toBody(KybVerificationRequest req) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", req.id());
		m.put("organizationId", req.organizationId());
		m.put("requesterAccountId", req.requesterAccountId());
		m.put("verificationType", req.verificationType());
		m.put("targetId", req.targetId());
		m.put("materials", req.materials());
		m.put("status", req.status());
		m.put("reviewerAccountId", req.reviewerAccountId());
		m.put("reviewNote", req.reviewNote());
		m.put("reviewDeadline", req.reviewDeadline() == null ? null : req.reviewDeadline().toString());
		m.put("createdAt", req.createdAt() == null ? null : req.createdAt().toString());
		return m;
	}

	public record ReviewRequest(String note) {
	}
}
