package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link EngagementVerificationRepository} upsert 与查询（Verification v1 Stage 1）。
 *
 * <p>镜像 {@code SettlementReconciliationRepositoryIT} 的 house style：直接 {@code db.sql} 插入
 * task→application→submission 链（满足 FK），用随机 UUID 避免跨用例污染。
 */
class EngagementVerificationRepositoryIT extends MarketplaceItSupport {

    private static final String CHECKS = """
            [{"type":"link_reachability","status":"passed","detail":"HTTP 200",\
            "checked_at":"2026-07-31T00:00:00Z"}]""";

    @Test
    void upsertInsertsThenUpdatesInPlace() {
        String submission = seedSubmission();

        EngagementVerification inserted = repos().upsert(submission, "inconclusive", "[]").block();
        assertThat(inserted.submissionId()).isEqualTo(submission);
        assertThat(inserted.status()).isEqualTo("inconclusive");
        assertThat(inserted.checksJson()).isEqualTo("[]");

        // 重跑 → ON CONFLICT 原地更新同一行（id 不变），status/checks 刷新。
        EngagementVerification updated = repos().upsert(submission, "failed", CHECKS).block();
        assertThat(updated.id()).isEqualTo(inserted.id());
        assertThat(updated.status()).isEqualTo("failed");
        assertThat(updated.checksJson()).contains("link_reachability");
        assertThat(updated.lastCheckedAt()).isAfterOrEqualTo(inserted.lastCheckedAt());
    }

    @Test
    void findBySubmissionIsEmptyWhenAbsent() {
        String submission = seedSubmission();
        assertThat(repos().findBySubmission(submission).block()).isNull();
    }

    @Test
    void findBySubmissionsBatchesAndSkipsMissing() {
        String s1 = seedSubmission();
        String s2 = seedSubmission();
        repos().upsert(s1, "passed", CHECKS).block();

        List<EngagementVerification> rows = repos().findBySubmissions(List.of(s1, s2)).collectList().block();
        assertThat(rows).hasSize(1);  // s2 无记录不返回
        assertThat(rows.get(0).submissionId()).isEqualTo(s1);
        assertThat(rows.get(0).status()).isEqualTo("passed");
    }

    private EngagementVerificationRepository repos() {
        return new EngagementVerificationRepository(db);
    }

    /** 直接插一条 task→application→submission 链（满足 engagement_submission 的 FK），返回 submission id。 */
    private String seedSubmission() {
        String task = UUID.randomUUID().toString();
        String app = UUID.randomUUID().toString();
        String submission = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, title)
                VALUES (CAST(:t AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), 'verification-it')
                """)
                .bind("t", task).bind("owner", UUID.randomUUID().toString())
                .bind("org", UUID.randomUUID().toString()).then().block();
        db.sql("""
                INSERT INTO task_application(id, task_id, recommender_account_id, bounty_cents)
                VALUES (CAST(:a AS uuid), CAST(:t AS uuid), CAST(:rec AS uuid), 0)
                """)
                .bind("a", app).bind("t", task).bind("rec", UUID.randomUUID().toString()).then().block();
        db.sql("""
                INSERT INTO engagement_submission(id, application_id, recommender_account_id, content_url)
                VALUES (CAST(:s AS uuid), CAST(:a AS uuid), CAST(:rec AS uuid), 'https://example.com/deliverable')
                """)
                .bind("s", submission).bind("a", app).bind("rec", UUID.randomUUID().toString()).then().block();
        return submission;
    }
}
