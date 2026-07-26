package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.trust.TrustItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 审判官池入口端到端（解 Slice 6C 遗留缺口：judge 表原先无 HTTP 入口 → adjudicate 恒 503）。
 * 覆盖：推荐官报名（幂等 / 退池后复活）、商家不可报名、查本人状态、退池、未入池 404。
 */
class JudgeControllerIT extends TrustItSupport {

    @Test
    void recommenderEnrollsAndBecomesDraftable() {
        String acct = UUID.randomUUID().toString();
        client().post().uri("/api/trust/judges")
                .header("X-Grassland-Identity", sign(acct, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accountId").isEqualTo(acct)
                .jsonPath("$.data.active").isEqualTo(true)
                .jsonPath("$.data.eligibilityTier").isEqualTo(1);

        // 入池后即可被抽签（这正是此前 503 的根因）
        assertThat(activeJudgeCount(acct)).isEqualTo(1);
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
}
