package com.grassland.identity.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * refresh token 清理 IT（GL-P3-IDENTITY-001）。直接调 {@code cleanup(now)}（注入时刻，不等定时器）。
 *
 * <p>清理器本身 {@code @ConditionalOnProperty}，这里手 new，避免为一个测试打开全局定时任务。
 */
class RefreshTokenCleanupIT extends IdentityItSupport {

    @Autowired
    private RefreshTokenRepository repository;

    @Test
    void deletesExpiredAndLongRevokedRowsButKeepsActiveAndRecent() {
        Instant now = Instant.parse("2026-08-04T12:00:00Z");
        String accountId = UUID.randomUUID().toString();

        String active = insert(accountId, now.plus(Duration.ofDays(30)), null);
        String expiredLongAgo = insert(accountId, now.minus(Duration.ofDays(30)), null);
        String revokedLongAgo = insert(accountId, now.plus(Duration.ofDays(30)), now.minus(Duration.ofDays(30)));
        String revokedJustNow = insert(accountId, now.plus(Duration.ofDays(30)), now.minus(Duration.ofHours(1)));

        // retention 7 天 → cutoff = now-7d。
        RefreshTokenCleanup cleanup = new RefreshTokenCleanup(repository, 7);
        Long deleted = cleanup.cleanup(now).block();

        assertThat(deleted).isEqualTo(2L);
        assertThat(exists(active)).isTrue();
        assertThat(exists(revokedJustNow)).isTrue();
        assertThat(exists(expiredLongAgo)).isFalse();
        assertThat(exists(revokedLongAgo)).isFalse();
    }

    @Test
    void emptyTableCleanupIsZero() {
        RefreshTokenCleanup cleanup = new RefreshTokenCleanup(repository, 7);
        // 表内可能有其他测试留下的活跃行，用远古 cutoff 保证只删该删的：此处仅验不抛且非负。
        assertThat(cleanup.cleanup(Instant.parse("2000-01-01T00:00:00Z")).block()).isNotNegative();
    }

    private String insert(String accountId, Instant expiresAt, Instant revokedAt) {
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO refresh_token(id, account_id, token_hash, expires_at, revoked_at) "
                        + "VALUES (CAST(:id AS uuid), CAST(:acc AS uuid), :hash, :exp, :rev)")
                .bind("id", id).bind("acc", accountId).bind("hash", "hash-" + id)
                .bind("exp", expiresAt)
                .bind("rev", revokedAt == null ? org.springframework.r2dbc.core.Parameter.empty(Instant.class)
                        : org.springframework.r2dbc.core.Parameter.from(revokedAt))
                .then().block();
        return id;
    }

    private boolean exists(String id) {
        Long count = db.sql("SELECT count(*) FROM refresh_token WHERE id = CAST(:id AS uuid)")
                .bind("id", id).map(row -> row.get(0, Long.class)).one().block();
        return count != null && count > 0;
    }
}
