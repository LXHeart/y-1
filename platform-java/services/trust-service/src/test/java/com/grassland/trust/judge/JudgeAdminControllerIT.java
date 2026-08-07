package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.trust.TrustItSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

class JudgeAdminControllerIT extends TrustItSupport {

    @Test
    void adminListSupportsBoundedCursorPaginationAndExactAccountSearch() {
        String first = enrollEligibleJudge();
        String second = enrollEligibleJudge();
        String assertion = signWithRole(UUID.randomUUID().toString(), "platform_admin", false);

        client().get().uri("/api/admin/trust/judges?limit=1")
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.nextCursor").isNotEmpty()
                .jsonPath("$.data.hasMore").isEqualTo(true);

        client().get().uri(uriBuilder -> uriBuilder.path("/api/admin/trust/judges")
                        .queryParam("accountId", first).build())
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].accountId").isEqualTo(first)
                .jsonPath("$.data.hasMore").isEqualTo(false);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void platformAdminListsViewsGrantsAndRevokesAdmissionWithAudit() {
        String accountId = enrollEligibleJudge();
        String admin = UUID.randomUUID().toString();
        String assertion = signWithRole(admin, "platform_admin", false);

        client().get().uri("/api/admin/trust/judges")
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items").isArray();

        client().get().uri("/api/admin/trust/judges/" + accountId)
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accountId").isEqualTo(accountId)
                .jsonPath("$.data.audit").isArray();

        updateAdmission(accountId, assertion, true, 0, "manual review").expectStatus().isOk().expectBody()
                .jsonPath("$.data.opsAdmitted").isEqualTo(true)
                .jsonPath("$.data.version").isEqualTo(1);
        assertThat(auditCount(accountId)).isEqualTo(1);
        updateAdmission(accountId, assertion, true, 0, "stale retry").expectStatus().isEqualTo(409);
        updateAdmission(accountId, assertion, true, 1, "same-version retry").expectStatus().isOk();
        assertThat(auditCount(accountId)).isEqualTo(1);

        updateAdmission(accountId, assertion, false, 1, "quality concern").expectStatus().isOk().expectBody()
                .jsonPath("$.data.opsAdmitted").isEqualTo(false)
                .jsonPath("$.data.version").isEqualTo(2);
        assertThat(auditCount(accountId)).isEqualTo(2);
        updateAdmission(accountId, assertion, false, 1, "stale retry").expectStatus().isEqualTo(409);
        updateAdmission(accountId, assertion, false, 2, "same-version retry").expectStatus().isOk();
        assertThat(auditCount(accountId)).isEqualTo(2);
    }

    @Test
    void adminEndpointsRejectAnonymousWrongRoleAndServicePrincipal() {
        client().get().uri("/api/admin/trust/judges").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/admin/trust/judges")
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "risk", false))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/admin/trust/judges")
                .header("X-Grassland-Identity", signWithRole("service:marketplace", "platform_admin", true))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void grantRevalidatesEligibilityButRevokeDoesNotDependOnMarketplace() {
        String accountId = enrollEligibleJudge();
        String assertion = signWithRole(UUID.randomUUID().toString(), "platform_admin", false);
        when(reputationClient.getLevel(accountId)).thenReturn(Mono.error(new RuntimeException("down")));
        updateAdmission(accountId, assertion, true, 0, "review")
                .expectStatus().isEqualTo(503);

        when(reputationClient.getLevel(accountId)).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(accountId, "Lv4", 4, false, 2L)));
        updateAdmission(accountId, assertion, true, 0, "review")
                .expectStatus().isEqualTo(409);

        when(reputationClient.getLevel(accountId)).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(accountId, "Lv5", 5, true, 3L)));
        updateAdmission(accountId, assertion, true, 0, "review").expectStatus().isOk();

        when(reputationClient.getLevel(accountId)).thenReturn(Mono.error(new RuntimeException("down")));
        updateAdmission(accountId, assertion, false, 1, "urgent revoke").expectStatus().isOk();
    }

    @Test
    void staleVersionInvalidInputAndOverlongReasonAreRejectedWithoutAudit() {
        String accountId = enrollEligibleJudge();
        String assertion = signWithRole(UUID.randomUUID().toString(), "platform_admin", false);

        updateAdmission(accountId, assertion, true, 99, "review").expectStatus().isEqualTo(409);
        updateAdmission(accountId, assertion, true, -1, "review").expectStatus().isBadRequest();
        updateAdmission(accountId, assertion, true, 0, "x".repeat(501)).expectStatus().isBadRequest();
        client().get().uri("/api/admin/trust/judges/not-a-uuid")
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isBadRequest();
        client().get().uri("/api/admin/trust/judges/" + UUID.randomUUID())
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isNotFound();
        assertThat(auditCount(accountId)).isZero();
    }

    @Test
    void admissionAuditCannotBeUpdatedOrDeleted() {
        String accountId = enrollEligibleJudge();
        String assertion = signWithRole(UUID.randomUUID().toString(), "platform_admin", false);
        updateAdmission(accountId, assertion, true, 0, "review").expectStatus().isOk();

        assertThatThrownBy(() -> db.sql("DELETE FROM judge_admission_audit WHERE judge_id=(SELECT id FROM judge"
                        + " WHERE account_id=CAST(:a AS uuid))")
                .bind("a", accountId).fetch().rowsUpdated().block())
                .isInstanceOf(RuntimeException.class);
        assertThat(auditCount(accountId)).isEqualTo(1);
    }

    private String enrollEligibleJudge() {
        String accountId = UUID.randomUUID().toString();
        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(accountId, "recommender", null, null))
                .exchange().expectStatus().isOk();
        return accountId;
    }

    private WebExchange updateAdmission(String accountId, String assertion, boolean admitted,
                                        long expectedVersion, String reason) {
        return new WebExchange(client().put().uri("/api/admin/trust/judges/" + accountId + "/admission")
                .header("X-Grassland-Identity", assertion)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("admitted", admitted, "expectedVersion", expectedVersion, "reason", reason))
                .exchange());
    }

    private long auditCount(String accountId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM judge_admission_audit a JOIN judge j ON j.id=a.judge_id"
                        + " WHERE j.account_id=CAST(:a AS uuid)")
                .bind("a", accountId).map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private String signWithRole(String accountId, String role, boolean service) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                accountId, null, service ? null : "sid-" + accountId, null, null,
                service ? "service" : "cookie-session", service ? "internal" : "level2", now, "r", "t",
                "grassland-internal", now, now.plusSeconds(60), service ? "service" : null,
                service ? "marketplace" : null, role));
    }

    private record WebExchange(org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec response) {
        org.springframework.test.web.reactive.server.StatusAssertions expectStatus() {
            return response.expectStatus();
        }
    }
}
