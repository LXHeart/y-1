package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.taskcatalog.EngagementVerificationRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「待判定」核验只读窗（GL-P1-OPS-001 Stage 3）。
 *
 * <p>锁住的核心口径：{@code inconclusive} 不进处置队列（{@code /api/ops/cases}），只在这里出现；
 * 商家一旦 confirm/reject（submission 离开 {@code submitted}）就退出视野。
 */
class OpsPendingVerificationIT extends MarketplaceItSupport {

    private static final String OPS = "11111111-1111-4111-8111-111111111111";

    @Autowired
    private OpsPendingVerificationRepository pending;

    @Autowired
    private EngagementVerificationRepository verifications;

    @Test
    @DisplayName("inconclusive + submitted 才出现；passed/failed 与已决交付物都不出现")
    void listsOnlyInconclusivePendingSubmissions() {
        Seed inconclusive = seed("submitted", "待判定的任务");
        verifications.upsert(inconclusive.submissionId, "inconclusive",
                "[{\"type\":\"ai_visual\",\"status\":\"inconclusive\"}]").block();

        Seed passed = seed("submitted", "已通过");
        verifications.upsert(passed.submissionId, "passed", "[]").block();

        Seed failed = seed("submitted", "已失败");
        verifications.upsert(failed.submissionId, "failed", "[]").block();

        // 商家已 confirm：人工判定已发生，即使核验仍 inconclusive 也不该再占运营视野
        Seed decided = seed("accepted", "商家已确认");
        verifications.upsert(decided.submissionId, "inconclusive", "[]").block();

        var rows = pending.list(50).collectList().block();
        assertThat(rows).extracting(OpsPendingVerification::submissionId)
                .contains(inconclusive.submissionId)
                .doesNotContain(passed.submissionId, failed.submissionId, decided.submissionId);

        OpsPendingVerification row = rows.stream()
                .filter(r -> r.submissionId().equals(inconclusive.submissionId)).findFirst().orElseThrow();
        assertThat(row.taskTitle()).isEqualTo("待判定的任务");
        assertThat(row.applicationId()).isEqualTo(inconclusive.applicationId);
        assertThat(row.checksJson()).contains("ai_visual");
        assertThat(row.contentUrl()).isEqualTo("https://example.com/d");
    }

    @Test
    @DisplayName("端点要求运营角色；返回体字段齐全")
    void endpointRequiresOpsRole() {
        Seed s = seed("submitted", "端点用例");
        verifications.upsert(s.submissionId, "inconclusive", "[]").block();

        client().get().uri("/api/ops/pending-verifications")
                .exchange().expectStatus().isUnauthorized();

        client().get().uri("/api/ops/pending-verifications")
                .header("X-Grassland-Identity", sign(OPS, "merchant"))
                .exchange().expectStatus().isForbidden();

        client().get().uri("/api/ops/pending-verifications?limit=200")
                .header("X-Grassland-Identity", signWithRole(OPS, "customer_service"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[?(@.submissionId=='" + s.submissionId + "')].taskTitle")
                .isEqualTo("端点用例");
    }

    @Test
    @DisplayName("待判定不开处置单：/api/ops/cases 里看不到它")
    void inconclusiveDoesNotEnterCaseQueue() {
        Seed s = seed("submitted", "不该开单");
        verifications.upsert(s.submissionId, "inconclusive", "[]").block();

        Long cases = db.sql("SELECT count(*) AS n FROM ops_case WHERE source_ref = :ref")
                .bind("ref", s.submissionId).map(r -> r.get("n", Long.class)).one().block();
        assertThat(cases).isZero();
    }

    private record Seed(String taskId, String applicationId, String submissionId) {
    }

    /** 插 task→app→submission；submission 状态决定是否仍「待判定」。 */
    private Seed seed(String submissionStatus, String title) {
        String task = UUID.randomUUID().toString();
        String app = UUID.randomUUID().toString();
        String sub = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, title)
                VALUES (CAST(:t AS uuid), CAST(:o AS uuid), CAST(:g AS uuid), :title)
                """)
                .bind("t", task).bind("o", UUID.randomUUID().toString())
                .bind("g", UUID.randomUUID().toString()).bind("title", title).then().block();
        db.sql("""
                INSERT INTO task_application(id, task_id, recommender_account_id, status)
                VALUES (CAST(:a AS uuid), CAST(:t AS uuid), CAST(:r AS uuid), 'accepted')
                """)
                .bind("a", app).bind("t", task).bind("r", UUID.randomUUID().toString()).then().block();
        db.sql("""
                INSERT INTO engagement_submission(id, application_id, recommender_account_id,
                        content_url, status)
                VALUES (CAST(:s AS uuid), CAST(:a AS uuid), CAST(:r AS uuid),
                        'https://example.com/d', :st)
                """)
                .bind("s", sub).bind("a", app).bind("r", UUID.randomUUID().toString())
                .bind("st", submissionStatus).then().block();
        return new Seed(task, app, sub);
    }
}
