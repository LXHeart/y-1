package com.grassland.identity.notify.mail;

import java.time.Duration;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 事务邮件 outbox 持久化（GL-P1-NOTIFY-001）。
 *
 * <p>镜像 {@code event/OutboxRepository}（identity 领域 outbox）的 append/claim/mark 骨架，差异：
 * <ul>
 *   <li>内容是渲染好的 to/subject/body（非领域事件 payload）；</li>
 *   <li>幂等键 {@code (source_event_id, recipient)}（一个事件给同一人只一封）；</li>
 *   <li>claim 只挑 {@code status='pending'}；</li>
 *   <li>邮件各收件人互相独立，<b>无 per-aggregate 顺序约束</b>，故 claimBatch 不带
 *       {@code NOT EXISTS earlier unpublished} 子句（领域 outbox 那条是为保因果序，邮件不需要）；</li>
 *   <li>额外 {@link #markDead}：失败封顶后置 {@code status='dead'}，区别于领域 outbox 的无限重试。</li>
 * </ul>
 *
 * <p>同事务原子：{@link #append} 由 {@code NotificationEventProcessor.emit} 在站内通知插入的同一
 * R2DBC 事务内调用，保证「通知落库 ⇔ 邮件入队」要么都提交要么都回滚。
 */
@Component
public class MailOutboxRepository {

    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final DatabaseClient db;

    public MailOutboxRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 入队一封渲染好的邮件。{@code ON CONFLICT (source_event_id, recipient) DO NOTHING} 吸收事件重投。 */
    public Mono<Void> append(MailMessage message) {
        return db.sql("""
                INSERT INTO mail_outbox (source_event_id, recipient, subject, body, category)
                VALUES (CAST(:sourceEventId AS text), :recipient, :subject, :body, CAST(:category AS text))
                ON CONFLICT (source_event_id, recipient) DO NOTHING
                """)
                .bind("sourceEventId", message.sourceEventId())
                .bind("recipient", message.recipient())
                .bind("subject", message.subject())
                .bind("body", message.body())
                .bind("category", message.category())
                .then();
    }

    /** 领取一批 pending 邮件（FOR UPDATE SKIP LOCKED + lease）。 */
    public Flux<MailOutboxRow> claimBatch(int limit, UUID claimToken, Duration leaseDuration) {
        long leaseMillis = Math.max(leaseDuration.toMillis(), 1L);
        return db.sql("""
                WITH candidates AS (
                    SELECT id FROM mail_outbox
                    WHERE status = 'pending'
                      AND next_attempt_at <= now()
                      AND (claimed_until IS NULL OR claimed_until <= now())
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE mail_outbox AS target
                SET claimed_until = now() + (:leaseMillis * interval '1 millisecond'),
                    claim_token = CAST(:claimToken AS uuid),
                    attempt_count = target.attempt_count + 1,
                    last_error_code = NULL
                FROM candidates
                WHERE target.id = candidates.id
                RETURNING target.id::text, target.source_event_id, target.recipient,
                          target.subject, target.body, target.claim_token::text, target.attempt_count
                """)
                .bind("leaseMillis", leaseMillis)
                .bind("claimToken", claimToken.toString())
                .bind("limit", Math.max(limit, 1))
                .map(row -> new MailOutboxRow(
                        row.get("id", String.class),
                        row.get("source_event_id", String.class),
                        row.get("recipient", String.class),
                        row.get("subject", String.class),
                        row.get("body", String.class),
                        UUID.fromString(row.get("claim_token", String.class)),
                        value(row.get("attempt_count", Integer.class), 0)))
                .all();
    }

    /** 发送成功：置 sent + 记 sent_at，仅当 claim_token 匹配且仍 pending。 */
    public Mono<Boolean> markSent(String id, UUID claimToken) {
        return db.sql("""
                UPDATE mail_outbox
                SET status = 'sent', sent_at = now(),
                    claimed_until = NULL, claim_token = NULL, last_error_code = NULL
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("id", id)
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    /** 发送失败但未封顶：清 claim、置退避 next_attempt_at、记 last_error_code。 */
    public Mono<Boolean> markFailure(String id, UUID claimToken, Duration retryDelay, String errorCode) {
        return db.sql("""
                UPDATE mail_outbox
                SET claimed_until = NULL, claim_token = NULL,
                    next_attempt_at = now() + (:retryDelayMillis * interval '1 millisecond'),
                    last_error_code = :lastErrorCode
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("retryDelayMillis", Math.max(retryDelay.toMillis(), 1L))
                .bind("lastErrorCode", normalizeErrorCode(errorCode))
                .bind("id", id)
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    /** 封顶死信：置 status='dead'，停止重试。claim_token 校验保证只有持有租约的轮次能置死信。 */
    public Mono<Boolean> markDead(String id, UUID claimToken, String errorCode) {
        return db.sql("""
                UPDATE mail_outbox
                SET status = 'dead', claimed_until = NULL, claim_token = NULL,
                    last_error_code = :lastErrorCode
                WHERE id = CAST(:id AS uuid)
                  AND claim_token = CAST(:claimToken AS uuid)
                  AND status = 'pending'
                """)
                .bind("lastErrorCode", normalizeErrorCode(errorCode))
                .bind("id", id)
                .bind("claimToken", claimToken.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Long> pendingCount() {
        return db.sql("SELECT COUNT(*)::bigint AS n FROM mail_outbox WHERE status = 'pending'")
                .map(row -> value(row.get("n", Long.class), 0L))
                .one().defaultIfEmpty(0L);
    }

    public Mono<Long> deadCount() {
        return db.sql("SELECT COUNT(*)::bigint AS n FROM mail_outbox WHERE status = 'dead'")
                .map(row -> value(row.get("n", Long.class), 0L))
                .one().defaultIfEmpty(0L);
    }

    private static String normalizeErrorCode(String errorCode) {
        String normalized = errorCode == null || errorCode.isBlank() ? "Unknown" : errorCode;
        return normalized.length() <= MAX_ERROR_CODE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_CODE_LENGTH);
    }

    private static long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    /** 待入队的渲染好的邮件（sourceEventId 可空用于非事件邮件）。 */
    public record MailMessage(
            String sourceEventId,
            String recipient,
            String subject,
            String body,
            String category) {}

    /** claim 出的待发邮件行。 */
    public record MailOutboxRow(
            String id,
            String sourceEventId,
            String recipient,
            String subject,
            String body,
            UUID claimToken,
            int attemptCount) {}
}
