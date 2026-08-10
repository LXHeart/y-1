package com.grassland.identity.notify.external;

import java.time.Duration;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ExternalDeliveryRepository {
    private final DatabaseClient db;

    public ExternalDeliveryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> upsertEndpoint(String accountId, String channel, String address, String provider) {
        return db.sql("""
                INSERT INTO notification_endpoint(account_id, channel, address, provider, verified_at)
                VALUES (CAST(:accountId AS uuid), :channel, :address, :provider, now())
                ON CONFLICT (account_id, channel, address) DO UPDATE SET
                    provider = EXCLUDED.provider, verified_at = now(), disabled_at = NULL, updated_at = now()
                """)
                .bind("accountId", accountId).bind("channel", channel)
                .bind("address", address).bind("provider", provider).then();
    }

    public Mono<Long> disableEndpoint(String accountId, UUID endpointId) {
        return db.sql("""
                UPDATE notification_endpoint SET disabled_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid) AND account_id = CAST(:accountId AS uuid)
                """)
                .bind("id", endpointId).bind("accountId", accountId).fetch().rowsUpdated();
    }

    public Flux<Endpoint> findActiveEndpoints(String accountId, String category) {
        return db.sql("""
                SELECT endpoint.id::text, endpoint.channel, endpoint.address, endpoint.provider
                FROM notification_endpoint endpoint
                LEFT JOIN notification_preference preference
                  ON preference.account_id = endpoint.account_id AND preference.category = :category
                WHERE endpoint.account_id = CAST(:accountId AS uuid) AND endpoint.disabled_at IS NULL
                  AND endpoint.verified_at IS NOT NULL
                  AND ((endpoint.channel = 'push' AND COALESCE(preference.push_enabled, true))
                    OR (endpoint.channel = 'sms' AND COALESCE(preference.sms_enabled, true)))
                """)
                .bind("category", category).bind("accountId", accountId)
                .map(row -> new Endpoint(
                        UUID.fromString(row.get("id", String.class)),
                        row.get("channel", String.class), row.get("address", String.class),
                        row.get("provider", String.class))).all();
    }

    public Mono<Void> setPreference(
            String accountId, String category, boolean pushEnabled, boolean smsEnabled) {
        return db.sql("""
                INSERT INTO notification_preference(account_id, category, push_enabled, sms_enabled)
                VALUES (CAST(:accountId AS uuid), :category, :push, :sms)
                ON CONFLICT (account_id, category) DO UPDATE SET
                    push_enabled = EXCLUDED.push_enabled, sms_enabled = EXCLUDED.sms_enabled, updated_at = now()
                """)
                .bind("accountId", accountId).bind("category", category)
                .bind("push", pushEnabled).bind("sms", smsEnabled).then();
    }

    public Mono<Void> createChallenge(UUID id, String accountId, String phone, String codeHash) {
        return db.sql("""
                INSERT INTO sms_verification_challenge(id, account_id, phone_e164, code_hash, expires_at)
                VALUES (CAST(:id AS uuid), CAST(:accountId AS uuid), :phone, :hash, now() + interval '5 minutes')
                """)
                .bind("id", id).bind("accountId", accountId).bind("phone", phone).bind("hash", codeHash).then();
    }

    public Mono<Boolean> hasRecentChallenge(String accountId) {
        return db.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM sms_verification_challenge
                    WHERE account_id = CAST(:accountId AS uuid)
                      AND created_at > now() - interval '60 seconds'
                ) AS present
                """)
                .bind("accountId", accountId)
                .map(row -> Boolean.TRUE.equals(row.get("present", Boolean.class))).one().defaultIfEmpty(false);
    }

    public Mono<Challenge> findChallengeForUpdate(UUID id, String accountId) {
        return db.sql("""
                SELECT id::text, phone_e164, code_hash, attempt_count
                FROM sms_verification_challenge
                WHERE id = CAST(:id AS uuid) AND account_id = CAST(:accountId AS uuid)
                  AND verified_at IS NULL AND expires_at > now() AND attempt_count < 5
                FOR UPDATE
                """)
                .bind("id", id).bind("accountId", accountId)
                .map(row -> new Challenge(UUID.fromString(row.get("id", String.class)),
                        row.get("phone_e164", String.class), row.get("code_hash", String.class),
                        row.get("attempt_count", Integer.class))).one();
    }

    public Mono<Void> recordChallengeFailure(UUID id) {
        return db.sql("UPDATE sms_verification_challenge SET attempt_count = attempt_count + 1 "
                        + "WHERE id = CAST(:id AS uuid) AND verified_at IS NULL")
                .bind("id", id).then();
    }

    public Mono<Void> markChallengeVerified(UUID id) {
        return db.sql("UPDATE sms_verification_challenge SET verified_at = now() "
                        + "WHERE id = CAST(:id AS uuid) AND verified_at IS NULL")
                .bind("id", id).then();
    }

    public Mono<Void> append(Message message) {
        var spec = db.sql("""
                INSERT INTO external_delivery_outbox(source_event_id, account_id, channel, recipient,
                        provider, title, body, link_path, category)
                VALUES (:eventId, CAST(:accountId AS uuid), :channel, :recipient,
                        :provider, :title, :body, :linkPath, :category)
                ON CONFLICT (source_event_id, channel, recipient) DO NOTHING
                """)
                .bind("eventId", message.sourceEventId())
                .bind("channel", message.channel()).bind("recipient", message.recipient())
                .bind("provider", message.provider()).bind("title", message.title())
                .bind("body", message.body()).bind("category", message.category());
        spec = message.accountId() == null ? spec.bindNull("accountId", UUID.class)
                : spec.bind("accountId", UUID.fromString(message.accountId()));
        spec = message.linkPath() == null ? spec.bindNull("linkPath", String.class)
                : spec.bind("linkPath", message.linkPath());
        return spec.then();
    }

    public Flux<Row> claimBatch(int limit, UUID token, Duration lease) {
        return db.sql("""
                WITH candidates AS (
                    SELECT id FROM external_delivery_outbox
                    WHERE status = 'pending' AND next_attempt_at <= now()
                      AND (claimed_until IS NULL OR claimed_until <= now())
                    ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT :limit
                )
                UPDATE external_delivery_outbox target
                SET claimed_until = now() + (:leaseMs * interval '1 millisecond'),
                    claim_token = CAST(:token AS uuid), attempt_count = target.attempt_count + 1,
                    last_error_code = NULL
                FROM candidates WHERE target.id = candidates.id
                RETURNING target.id::text, target.channel, target.recipient, target.provider,
                          target.title, target.body, target.link_path, target.claim_token::text,
                          target.attempt_count
                """)
                .bind("limit", Math.max(limit, 1)).bind("leaseMs", Math.max(lease.toMillis(), 1L))
                .bind("token", token)
                .map(row -> new Row(UUID.fromString(row.get("id", String.class)),
                        row.get("channel", String.class), row.get("recipient", String.class),
                        row.get("provider", String.class), row.get("title", String.class),
                        row.get("body", String.class), row.get("link_path", String.class),
                        UUID.fromString(row.get("claim_token", String.class)),
                        row.get("attempt_count", Integer.class))).all();
    }

    public Mono<Boolean> markSent(Row row) {
        return mark(row, "sent", Duration.ZERO, null);
    }

    public Mono<Boolean> markFailure(Row row, boolean dead, Duration delay, String errorCode) {
        return mark(row, dead ? "dead" : "pending", delay, normalize(errorCode));
    }

    private Mono<Boolean> mark(Row row, String status, Duration delay, String errorCode) {
        var spec = db.sql("""
                UPDATE external_delivery_outbox SET status = :status,
                    sent_at = CASE WHEN :status = 'sent' THEN now() ELSE sent_at END,
                    next_attempt_at = now() + (:delayMs * interval '1 millisecond'),
                    claimed_until = NULL, claim_token = NULL, last_error_code = :errorCode
                WHERE id = CAST(:id AS uuid) AND claim_token = CAST(:token AS uuid) AND status = 'pending'
                """)
                .bind("status", status).bind("delayMs", Math.max(delay.toMillis(), 0L))
                .bind("id", row.id()).bind("token", row.claimToken());
        spec = errorCode == null ? spec.bindNull("errorCode", String.class) : spec.bind("errorCode", errorCode);
        return spec.fetch().rowsUpdated().map(count -> count > 0).defaultIfEmpty(false);
    }

    private static String normalize(String value) {
        String code = value == null || value.isBlank() ? "DELIVERY_FAILED" : value.trim();
        return code.substring(0, Math.min(code.length(), 64));
    }

    public record Endpoint(UUID id, String channel, String address, String provider) {}
    public record Challenge(UUID id, String phone, String codeHash, int attemptCount) {}
    public record Message(String sourceEventId, String accountId, String channel, String recipient,
                          String provider, String title, String body, String linkPath, String category) {}
    public record Row(UUID id, String channel, String recipient, String provider, String title,
                      String body, String linkPath, UUID claimToken, int attemptCount) {}
}
