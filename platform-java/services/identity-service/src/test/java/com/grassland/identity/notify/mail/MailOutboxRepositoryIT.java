package com.grassland.identity.notify.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.notify.mail.MailOutboxRepository.MailMessage;
import com.grassland.identity.notify.mail.MailOutboxRepository.MailOutboxRow;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link MailOutboxRepository} DB 集成测试（GL-P1-NOTIFY-001）。真 Flyway V14 mail_outbox 表 +
 * 单例 Postgres。覆盖 append 幂等、claim 只挑 pending、markSent/markFailure/markDead、claim_token 守卫。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MailOutboxRepositoryIT extends IdentityItSupport {

    private MailOutboxRepository repo;

    @BeforeEach
    void setUp() {
        repo = new MailOutboxRepository(db);
        // 单例 DB 跨 IT 共享，清表隔离（本类独占 mail_outbox 的直接断言）。
        db.sql("DELETE FROM mail_outbox").then().block();
    }

    @Test
    void appendIsIdempotentByEventAndRecipient() {
        var msg = new MailMessage("evt-1", "a@example.com", "subj", "body", "invitation");
        repo.append(msg).block();
        repo.append(msg).block(); // 同 (source_event_id, recipient) → ON CONFLICT 吸收
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void differentRecipientsForSameEventEachEnqueue() {
        repo.append(new MailMessage("evt-2", "a@example.com", "s", "b", "engagement")).block();
        repo.append(new MailMessage("evt-2", "b@example.com", "s", "b", "engagement")).block();
        assertThat(pendingCount()).isEqualTo(2);
    }

    @Test
    void claimReturnsOnlyPendingRows() {
        repo.append(new MailMessage("evt-3", "a@example.com", "s", "b", "wallet")).block();
        List<MailOutboxRow> rows = repo.claimBatch(10, UUID.randomUUID(), Duration.ofSeconds(60))
                .collectList().block();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).recipient()).isEqualTo("a@example.com");
        assertThat(rows.get(0).attemptCount()).isEqualTo(1); // claim 时 +1
    }

    @Test
    void markSentSetsStatusSentAndClearsClaim() {
        UUID token = enqueueAndClaim();
        assertThat(repo.markSent(lastId(), token).block()).isTrue();
        assertThat(pendingCount()).isZero();
        assertThat(deadCount()).isZero();
        // sent_at 已置：sent 行不计 pending/dead
    }

    @Test
    void markFailureKeepsPendingAndSetsBackoff() {
        UUID token = enqueueAndClaim();
        assertThat(repo.markFailure(lastId(), token, Duration.ofSeconds(30), "SmtpTimeout").block()).isTrue();
        // 仍 pending（退避后可重试）
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void markDeadStopsRetry() {
        UUID token = enqueueAndClaim();
        assertThat(repo.markDead(lastId(), token, "AddressNotFound").block()).isTrue();
        assertThat(pendingCount()).isZero(); // 不再 pending
        assertThat(deadCount()).isEqualTo(1);
    }

    @Test
    void markRequiresMatchingClaimToken() {
        UUID token = enqueueAndClaim();
        // 错 token → 不更新（防其它轮次误改）
        assertThat(repo.markSent(lastId(), UUID.randomUUID()).block()).isFalse();
        assertThat(repo.markDead(lastId(), UUID.randomUUID(), "x").block()).isFalse();
        // 仍 pending
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void claimRespectsLimit() {
        for (int i = 0; i < 3; i++) {
            repo.append(new MailMessage("evt-" + i, "r" + i + "@example.com", "s", "b", "engagement")).block();
        }
        List<MailOutboxRow> rows = repo.claimBatch(2, UUID.randomUUID(), Duration.ofSeconds(60))
                .collectList().block();
        assertThat(rows).hasSize(2);
    }

    // ---- helpers ----

    private UUID enqueueAndClaim() {
        repo.append(new MailMessage("evt-x", "x@example.com", "s", "b", "invitation")).block();
        UUID token = UUID.randomUUID();
        MailOutboxRow row = repo.claimBatch(1, token, Duration.ofSeconds(60)).blockLast();
        lastId.set(row.id());
        lastToken.set(row.claimToken());
        return row.claimToken();
    }

    private final java.util.concurrent.atomic.AtomicReference<String> lastId = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<UUID> lastToken = new java.util.concurrent.atomic.AtomicReference<>();

    private String lastId() { return lastId.get(); }

    private long pendingCount() {
        return repo.pendingCount().block();
    }

    private long deadCount() {
        return repo.deadCount().block();
    }
}
