package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.taskcatalog.EngagementVerificationRepository;
import com.grassland.marketplace.taskcatalog.SubmissionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DbVerificationChecker} 解析路径（Verification v1）：app → accepted submission → 核验记录 failed？
 *
 * <p>镜像 {@code EngagementVerificationRepositoryIT} 的 house style：{@code db} 是 {@code @Autowired}（字段初始化时
 * 仍为 null），故 repo/checker 用方法懒构造（非字段初始化器）。直接 {@code db.sql} 插 task→app→submission，
 * 随机 UUID 避免跨用例污染。验证四态：failed 阻断；passed/无记录 不阻断；submission 未 accepted 不阻断。
 */
class DbVerificationCheckerIT extends MarketplaceItSupport {

    @Test
    void blocksWhenAcceptedSubmissionHasFailedVerification() {
        String[] ids = seed("accepted", "accepted");  // app accepted + submission accepted（confirm 后形态）
        verifications().upsert(ids[1], "failed", "[]").block();
        assertThat(checker().blocksSettlement("org", ids[0])).isTrue();
    }

    @Test
    void doesNotBlockWhenPassed() {
        String[] ids = seed("accepted", "accepted");
        verifications().upsert(ids[1], "passed", "[]").block();
        assertThat(checker().blocksSettlement("org", ids[0])).isFalse();
    }

    @Test
    void doesNotBlockWhenNoVerificationRecord() {
        String[] ids = seed("accepted", "accepted");
        assertThat(checker().blocksSettlement("org", ids[0])).isFalse();
    }

    @Test
    void doesNotBlockWhenSubmissionNotAccepted() {
        // submission 仍 submitted（未 confirm）→ checker 只看 accepted → 不阻断
        String[] ids = seed("accepted", "submitted");
        verifications().upsert(ids[1], "failed", "[]").block();
        assertThat(checker().blocksSettlement("org", ids[0])).isFalse();
    }

    /** 懒构造（db 注入后才可用），避免字段初始化器拿到 null db。 */
    private EngagementVerificationRepository verifications() {
        return new EngagementVerificationRepository(db);
    }

    private DbVerificationChecker checker() {
        return new DbVerificationChecker(new SubmissionRepository(db), verifications());
    }

    /** 插 task→app→submission（指定状态），返回 {appId, submissionId}。 */
    private String[] seed(String appStatus, String submissionStatus) {
        String task = UUID.randomUUID().toString();
        String app = UUID.randomUUID().toString();
        String sub = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, title)
                VALUES (CAST(:t AS uuid), CAST(:o AS uuid), CAST(:g AS uuid), 'checker-it')
                """)
                .bind("t", task).bind("o", UUID.randomUUID().toString())
                .bind("g", UUID.randomUUID().toString()).then().block();
        db.sql("""
                INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents)
                VALUES (CAST(:a AS uuid), CAST(:t AS uuid), CAST(:r AS uuid), :st, 0)
                """)
                .bind("a", app).bind("t", task).bind("r", UUID.randomUUID().toString())
                .bind("st", appStatus).then().block();
        db.sql("""
                INSERT INTO engagement_submission(id, application_id, recommender_account_id, content_url, status)
                VALUES (CAST(:s AS uuid), CAST(:a AS uuid), CAST(:r AS uuid), 'https://example.com/d', :st)
                """)
                .bind("s", sub).bind("a", app).bind("r", UUID.randomUUID().toString())
                .bind("st", submissionStatus).then().block();
        return new String[]{app, sub};
    }
}
