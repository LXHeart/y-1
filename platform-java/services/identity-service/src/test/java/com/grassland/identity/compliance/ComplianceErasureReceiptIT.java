package com.grassland.identity.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.identity.IdentityItSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 注销清理回执 IT（任务书 #103 C103-09 / TC103-09-03/05/06 Identity 侧）：Intelligence erase
 * 回执必须 真实——erased=false（处理中/对象未清/needs_review）时保持 processing/retry（closure 落
 * failed+退避、 可被重新领取），绝不写 pii_erased；仅 erased=true 才本地清理+收口 completed；上游
 * 409/异常按失败退避。
 */
class ComplianceErasureReceiptIT extends IdentityItSupport {

	@MockitoBean
	private ComplianceDomainClient domains;

	@Autowired
	private ComplianceService service;

	@BeforeEach
	void stubDomains() {
		when(domains.eraseMarketplace(anyString())).thenReturn(Mono.empty());
		when(domains.eraseFinance(anyString())).thenReturn(Mono.empty());
		when(domains.eraseTrust(anyString())).thenReturn(Mono.empty());
	}

	private static ComplianceDomainClient.EraseReceipt receipt(boolean erased, String state) {
		return new ComplianceDomainClient.EraseReceipt(erased, state, UUID.randomUUID().toString(), 2L, 0L);
	}

	/** 待清理的 erasing 闭包请求行（模拟 claimDueClosures 领取后；真实流程软删先于清理）。 */
	private ComplianceModels.ClosureRequest seedErasingClosure(String accountId) {
		String id = UUID.randomUUID().toString();
		String token = UUID.randomUUID().toString();
		db.sql("UPDATE app_users SET status = 'deleted', deleted_at = now() WHERE id = CAST(:a AS uuid)")
				.bind("a", accountId).then().block();
		db.sql("INSERT INTO account_closure_request(id, account_id, status, claim_token, retention_until)"
				+ " VALUES (CAST(:id AS uuid), CAST(:a AS uuid), 'erasing', CAST(:t AS uuid), now())").bind("id", id)
				.bind("a", accountId).bind("t", token).then().block();
		return new ComplianceModels.ClosureRequest(id, accountId, "erasing", "[]", Instant.now(), 1, token,
				Instant.now(), null, null);
	}

	private String closureStatus(String requestId) {
		return db.sql("SELECT status FROM account_closure_request WHERE id = CAST(:r AS uuid)").bind("r", requestId)
				.map((r) -> r.get("status", String.class)).one().block();
	}

	@Test
	void pendingObjectsReceiptKeepsClosureRetryingWithoutPiiErased() {
		Seeded account = seedAccount("receipt-pending-" + UUID.randomUUID() + "@test.local");
		ComplianceModels.ClosureRequest request = seedErasingClosure(account.accountId());
		when(domains.eraseIntelligence(anyString(), anyString()))
				.thenReturn(Mono.just(receipt(false, "objects_pending")));

		service.eraseAccount(request).block();

		assertThat(closureStatus(request.id())).isEqualTo("failed");
		String errorCode = db.sql("SELECT error_code FROM account_closure_request WHERE id = CAST(:r AS uuid)")
				.bind("r", request.id()).map((r) -> r.get("error_code", String.class)).one().block();
		assertThat(errorCode).isEqualTo("erase_objects_pending");
		Long erasedAudits = db
				.sql("SELECT count(*)::bigint AS c FROM pii_lifecycle_audit"
						+ " WHERE account_id = CAST(:a AS uuid) AND action = 'pii_erased'")
				.bind("a", account.accountId()).map((r) -> r.get("c", Long.class)).one().block();
		assertThat(erasedAudits).isZero();
		// 未完成不执行本地清理：邮箱保持原值（未重写为 deleted+ 假名）。
		String email = db.sql("SELECT email FROM app_users WHERE id = CAST(:a AS uuid)").bind("a", account.accountId())
				.map((r) -> r.get("email", String.class)).one().block();
		assertThat(email).doesNotStartWith("deleted+");
	}

	@Test
	void verifiedCompletedReceiptPurgesLocalPiiAndCompletesClosure() {
		Seeded account = seedAccount("receipt-done-" + UUID.randomUUID() + "@test.local");
		ComplianceModels.ClosureRequest request = seedErasingClosure(account.accountId());
		when(domains.eraseIntelligence(anyString(), anyString())).thenReturn(Mono.just(receipt(true, "completed")));

		service.eraseAccount(request).block();

		assertThat(closureStatus(request.id())).isEqualTo("completed");
		Long erasedAudits = db
				.sql("SELECT count(*)::bigint AS c FROM pii_lifecycle_audit"
						+ " WHERE account_id = CAST(:a AS uuid) AND action = 'pii_erased'")
				.bind("a", account.accountId()).map((r) -> r.get("c", Long.class)).one().block();
		assertThat(erasedAudits).isEqualTo(1L);
		String email = db.sql("SELECT email FROM app_users WHERE id = CAST(:a AS uuid)").bind("a", account.accountId())
				.map((r) -> r.get("email", String.class)).one().block();
		assertThat(email).startsWith("deleted+");
	}

	@Test
	void upstreamRejectionFailsClosureForRetry() {
		Seeded account = seedAccount("receipt-error-" + UUID.randomUUID() + "@test.local");
		ComplianceModels.ClosureRequest request = seedErasingClosure(account.accountId());
		when(domains.eraseIntelligence(anyString(), anyString()))
				.thenReturn(Mono.error(new IllegalStateException("intelligence erase HTTP 409")));

		service.eraseAccount(request).block();

		assertThat(closureStatus(request.id())).isEqualTo("failed");
		Long erasedAudits = db
				.sql("SELECT count(*)::bigint AS c FROM pii_lifecycle_audit"
						+ " WHERE account_id = CAST(:a AS uuid) AND action = 'pii_erased'")
				.bind("a", account.accountId()).map((r) -> r.get("c", Long.class)).one().block();
		assertThat(erasedAudits).isZero();
	}
}
