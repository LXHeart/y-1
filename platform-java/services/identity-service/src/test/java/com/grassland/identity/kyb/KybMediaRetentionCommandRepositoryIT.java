package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

class KybMediaRetentionCommandRepositoryIT extends IdentityItSupport {

    @Autowired
    private KybMediaRetentionCommandRepository commands;

    @Test
    void liveCommandCanBeReclaimedAndStaleClaimCannotMarkItSynced() {
        UUID mediaId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        Instant remoteLease = Instant.now().plus(Duration.ofMinutes(30));
        commands.upsertLive(mediaId, referenceId, organizationId, "attachment", remoteLease).block();

        UUID firstClaim = UUID.randomUUID();
        KybMediaRetentionCommand first = commands.claimBatch(
                1, firstClaim, Duration.ofMinutes(1), Duration.ofHours(1)).single().block();
        assertThat(first).isNotNull();
        assertThat(first.desiredState()).isEqualTo("live");

        db.sql("UPDATE kyb_media_retention_sync SET claimed_until=now()-interval '1 second' "
                        + "WHERE media_reference_id=CAST(:media AS uuid) AND reference_id=CAST(:reference AS uuid)")
                .bind("media", mediaId).bind("reference", referenceId).then().block();

        UUID secondClaim = UUID.randomUUID();
        KybMediaRetentionCommand reclaimed = commands.claimBatch(
                1, secondClaim, Duration.ofMinutes(1), Duration.ofHours(1)).single().block();
        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.claimToken()).isEqualTo(secondClaim);
        assertThat(commands.markSynced(mediaId, referenceId, firstClaim,
                Instant.now().plus(Duration.ofDays(7))).block()).isFalse();
        assertThat(commands.markSynced(mediaId, referenceId, secondClaim,
                Instant.now().plus(Duration.ofDays(7))).block()).isTrue();
    }

    @Test
    void releasedCommandRemainsPendingAcrossFailuresUntilRemoteReleaseSucceeds() {
        UUID mediaId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        commands.upsertLive(mediaId, referenceId, organizationId, "attachment",
                Instant.now().plus(Duration.ofDays(7))).block();
        commands.markReleased(mediaId, referenceId, organizationId).block();

        UUID failedClaim = UUID.randomUUID();
        KybMediaRetentionCommand claimed = commands.claimBatch(
                1, failedClaim, Duration.ofMinutes(1), Duration.ofDays(1)).single().block();
        assertThat(claimed.desiredState()).isEqualTo("released");
        assertThat(commands.markFailure(mediaId, referenceId, failedClaim,
                Duration.ofSeconds(10), "ServiceUnavailable").block()).isTrue();

        MapRow pending = db.sql("SELECT desired_state, sync_status, attempt_count, last_error_code "
                        + "FROM kyb_media_retention_sync WHERE media_reference_id=CAST(:media AS uuid) "
                        + "AND reference_id=CAST(:reference AS uuid)")
                .bind("media", mediaId).bind("reference", referenceId)
                .map(row -> new MapRow(row.get("desired_state", String.class),
                        row.get("sync_status", String.class), row.get("attempt_count", Integer.class),
                        row.get("last_error_code", String.class))).one().block();
        assertThat(pending).isEqualTo(new MapRow("released", "pending", 1, "ServiceUnavailable"));
    }

    @Test
    void sealingAReviewRequestUpdatesEveryMaterialWithoutShorteningDeadline() {
        UUID requestId = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            commands.upsertLive(UUID.randomUUID(), requestId, organizationId, "review_request",
                    Instant.now().plus(Duration.ofDays(7))).block();
        }
        Instant longDeadline = Instant.now().plus(Duration.ofDays(365));
        assertThat(commands.sealReference(requestId, organizationId, longDeadline).block()).isEqualTo(3L);
        assertThat(commands.sealReference(requestId, organizationId,
                Instant.now().plus(Duration.ofDays(30))).block()).isEqualTo(3L);

        Long sealed = db.sql("SELECT count(*) FROM kyb_media_retention_sync "
                        + "WHERE reference_id=CAST(:reference AS uuid) AND desired_state='sealed' "
                        + "AND retain_until >= :deadline")
                .bind("reference", requestId).bind("deadline", longDeadline)
                .map(row -> row.get(0, Long.class)).one().block();
        assertThat(sealed).isEqualTo(3L);
    }

    @Test
    void legacyAttachmentIdSnapshotIsBackfilledToItsMediaReference() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        db.sql("""
                        INSERT INTO merchant_attachment(
                            id, organization_id, attachment_type, media_reference_id,
                            mime_type, size_bytes, uploaded_by_account_id)
                        VALUES (CAST(:attachment AS uuid), CAST(:org AS uuid), 'other',
                                CAST(:media AS uuid), 'image/png', 8, CAST(:account AS uuid))
                        """)
                .bind("attachment", attachmentId).bind("org", organizationId)
                .bind("media", mediaId).bind("account", accountId).then().block();
        db.sql("""
                        INSERT INTO kyb_verification_request(
                            id, organization_id, requester_account_id, verification_type,
                            target_id, materials, status)
                        VALUES (CAST(:request AS uuid), CAST(:org AS uuid), CAST(:account AS uuid),
                                'merchant_profile', CAST(:org AS uuid), CAST(:materials AS jsonb), 'pending')
                        """)
                .bind("request", requestId).bind("org", organizationId).bind("account", accountId)
                .bind("materials", "[\"" + attachmentId + "\"]").then().block();

        String migration = new ClassPathResource(
                "db/migration/V25__kyb_media_retention_sync_backfill.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String reviewBackfill = migration.substring(migration.indexOf("-- Review snapshots"));
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(reviewBackfill);
        }

        Long rows = db.sql("""
                        SELECT count(*) FROM kyb_media_retention_sync
                        WHERE media_reference_id=CAST(:media AS uuid)
                          AND reference_id=CAST(:request AS uuid)
                          AND reference_type='review_request' AND desired_state='live'
                        """)
                .bind("media", mediaId).bind("request", requestId)
                .map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isEqualTo(1L);
    }

    private record MapRow(String desiredState, String syncStatus, Integer attemptCount, String lastErrorCode) {}
}
