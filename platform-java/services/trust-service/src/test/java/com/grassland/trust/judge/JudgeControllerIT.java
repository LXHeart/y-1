package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.trust.TrustItSupport;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 审判官池入口端到端（解 Slice 6C 遗留缺口：judge 表原先无 HTTP 入口 → adjudicate 恒 503）。
 * 覆盖：推荐官报名（幂等 / 退池后复活）、商家不可报名、查本人状态、退池、未入池 404。
 */
class JudgeControllerIT extends TrustItSupport {

    @Test
    void eligibleLv5EnrollsPendingOperationsAdmission() {
        String acct = UUID.randomUUID().toString();
        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accountId").isEqualTo(acct)
                .jsonPath("$.data.active").isEqualTo(true)
                .jsonPath("$.data.eligibilityTier").isEqualTo(5)
                .jsonPath("$.data.opsAdmitted").isEqualTo(false)
                .jsonPath("$.data.version").isEqualTo(0);

        // 报名只创建候选人；运营准入前不可被抽签。
        assertThat(activeJudgeCount(acct)).isEqualTo(1);
        assertThat(admittedJudgeCount(acct)).isZero();
    }

    @Test
    void nonEligibleLevelCannotEnroll() {
        String acct = UUID.randomUUID().toString();
        when(reputationClient.getLevel(acct)).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(acct, "Lv4", 4, false, 3L)));

        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isForbidden();
        assertThat(allJudgeCount(acct)).isZero();
    }

    @Test
    void reputationFailureFailsClosedWithoutCreatingJudge() {
        String acct = UUID.randomUUID().toString();
        when(reputationClient.getLevel(acct)).thenReturn(Mono.error(
                new MarketplaceReputationClient.ReputationException("upstream detail must not leak")));

        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.error").isEqualTo("声誉服务暂时不可用");
        assertThat(allJudgeCount(acct)).isZero();
    }

    @Test
    void realRecommenderAssertionUsesCompleteIdentityMembershipsInsteadOfNullActiveOrg() {
        String acct = UUID.randomUUID().toString();
        String firstOrg = UUID.randomUUID().toString();
        String secondOrg = UUID.randomUUID().toString();
        when(identityMemberships.organizationIds(acct)).thenReturn(Mono.just(Set.of(firstOrg, secondOrg)));

        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isOk();

        assertThat(storedOrganizationId(acct)).isNull();
    }

    @Test
    void identityUnavailableFailsClosedWithoutCreatingJudge() {
        String acct = UUID.randomUUID().toString();
        when(identityMemberships.organizationIds(acct)).thenReturn(Mono.error(
                new IdentityOrganizationMembershipClient.MembershipException("upstream detail must not leak")));

        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.error").isEqualTo("身份服务暂时不可用");
        assertThat(allJudgeCount(acct)).isZero();
    }

    @Test
    void enrollIsIdempotent() {
        String acct = UUID.randomUUID().toString();
        enroll(acct);
        enroll(acct);  // 重复报名不报错、不产生第二行
        assertThat(activeJudgeCount(acct)).isEqualTo(1);
    }

    @Test
    void merchantCannotEnroll() {
        // 商家不得自任审判官（既当运动员又当裁判）
        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant",
                        UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void meReturnsEnrollmentStatus() {
        String acct = UUID.randomUUID().toString();
        enroll(acct);
        client().get().uri("/api/trust/judges/me")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accountId").isEqualTo(acct)
                .jsonPath("$.data.active").isEqualTo(true);
    }

    @Test
    void meNotFoundWhenNeverEnrolled() {
        client().get().uri("/api/trust/judges/me")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender", null, null))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void leavePoolThenReEnrollRevives() {
        String acct = UUID.randomUUID().toString();
        enroll(acct);

        client().delete().uri("/api/trust/judges/me")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.active").isEqualTo(false);
        assertThat(activeJudgeCount(acct)).isZero();  // 退池后不再被抽签

        enroll(acct);  // 再报名复活同一行（不违反 UNIQUE(account_id)）
        assertThat(activeJudgeCount(acct)).isEqualTo(1);
    }

    @Test
    void leaveAndReEnrollPreservesOperationsAdmission() {
        String acct = UUID.randomUUID().toString();
        enroll(acct);
        db.sql("UPDATE judge SET ops_admitted=true, ops_admitted_at=now(),"
                        + " ops_admitted_by=CAST(:admin AS uuid), version=version+1"
                        + " WHERE account_id=CAST(:acct AS uuid)")
                .bind("admin", UUID.randomUUID().toString()).bind("acct", acct).then().block();

        client().delete().uri("/api/trust/judges/me")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isOk();
        enroll(acct);

        assertThat(activeJudgeCount(acct)).isEqualTo(1);
        assertThat(admittedJudgeCount(acct)).isEqualTo(1);
    }

    @Test
    void leaveWhenNotEnrolledNotFound() {
        client().delete().uri("/api/trust/judges/me")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender", null, null))
                .exchange().expectStatus().isNotFound();
    }

    // ---------- helpers ----------

    private void enroll(String accountId) {
        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(accountId, "recommender", null, null))
                .exchange().expectStatus().isOk();
    }

    private long activeJudgeCount(String accountId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM judge WHERE account_id = CAST(:a AS uuid) AND active = true")
                .bind("a", accountId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private long admittedJudgeCount(String accountId) {
        return count("SELECT COUNT(*)::int AS c FROM judge WHERE account_id = CAST(:a AS uuid) AND ops_admitted = true", accountId);
    }

    private long allJudgeCount(String accountId) {
        return count("SELECT COUNT(*)::int AS c FROM judge WHERE account_id = CAST(:a AS uuid)", accountId);
    }

    private String storedOrganizationId(String accountId) {
        return db.sql("SELECT organization_id::text AS organization_id FROM judge WHERE account_id = CAST(:a AS uuid)")
                .bind("a", accountId)
                .map(r -> java.util.Optional.ofNullable(r.get("organization_id", String.class)))
                .one().blockOptional().flatMap(value -> value).orElse(null);
    }

    private long count(String sql, String accountId) {
        return db.sql(sql).bind("a", accountId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }
}
