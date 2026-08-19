package com.grassland.intelligence.compliance;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class IntelligenceComplianceRepository {

    private final DatabaseClient db;

    public IntelligenceComplianceRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Long> activeJobCount(String accountId) {
        return db.sql("""
                SELECT (
                  (SELECT COUNT(*) FROM ai_run WHERE account_id = :accountId AND status = 'running')
                  + (SELECT COUNT(*) FROM video_generation_job WHERE account_id = :accountId
                       AND status IN ('preparing', 'queued', 'submitted', 'processing'))
                  + (SELECT COUNT(*) FROM speech_transcription WHERE owner_account_id = :accountId
                       AND status = 'processing')
                  + (SELECT COUNT(*) FROM ai_credit_compensation WHERE account_id = :accountId
                       AND status = 'pending')
                )::bigint AS active_count
                """)
                .bind("accountId", accountId)
                .map(row -> row.get("active_count", Long.class))
                .one().defaultIfEmpty(0L);
    }

    public Mono<Map<String, Long>> erasePii(String accountId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        return update("DELETE FROM ai_provider_key WHERE owner_account_id = :id AND organization_id IS NULL", accountId)
                .doOnNext(value -> counts.put("personalProviderKeys", value))
                .then(update("DELETE FROM intelligence_style_preferences WHERE account_id = :id", accountId))
                .doOnNext(value -> counts.put("stylePreferences", value))
                .then(update("DELETE FROM creation_draft_version WHERE draft_id IN"
                        + " (SELECT id FROM creation_draft WHERE owner_account_id = :id AND organization_id IS NULL)", accountId))
                .doOnNext(value -> counts.put("draftVersions", value))
                .then(update("DELETE FROM creation_draft WHERE owner_account_id = :id AND organization_id IS NULL", accountId))
                .doOnNext(value -> counts.put("drafts", value))
                .then(update("DELETE FROM content_asset_embedding WHERE asset_id IN"
                        + " (SELECT id FROM content_asset WHERE owner_account_id = :id AND library_type = 'personal')", accountId))
                .doOnNext(value -> counts.put("assetEmbeddings", value))
                .then(update("DELETE FROM content_asset_grant WHERE asset_id IN"
                        + " (SELECT id FROM content_asset WHERE owner_account_id = :id AND library_type = 'personal')", accountId))
                .doOnNext(value -> counts.put("assetGrants", value))
                .then(update("DELETE FROM content_asset_version WHERE asset_id IN"
                        + " (SELECT id FROM content_asset WHERE owner_account_id = :id AND library_type = 'personal')", accountId))
                .doOnNext(value -> counts.put("assetVersions", value))
                .then(update("DELETE FROM content_asset WHERE owner_account_id = :id AND library_type = 'personal'", accountId))
                .doOnNext(value -> counts.put("personalAssets", value))
                .then(update("DELETE FROM speech_transcription WHERE owner_account_id = :id AND organization_id IS NULL", accountId))
                .doOnNext(value -> counts.put("speechTranscripts", value))
                .then(update("DELETE FROM creation_generation WHERE owner_account_id = :id AND organization_id IS NULL", accountId))
                .doOnNext(value -> counts.put("generationContent", value))
                .then(update("DELETE FROM creation_context_snapshot WHERE account_id = :id", accountId))
                .doOnNext(value -> counts.put("creationContexts", value))
                .then(update("DELETE FROM content_fingerprint WHERE owner_account_id = :id", accountId))
                .doOnNext(value -> counts.put("contentFingerprints", value))
                .then(update("UPDATE media_reference SET status = 'deleting', updated_at = now()"
                        + " WHERE owner_account_id = :id AND organization_id IS NULL"
                        + " AND status NOT IN ('deleting', 'deleted')", accountId))
                .doOnNext(value -> counts.put("mediaQueuedForDeletion", value))
                .thenReturn(counts);
    }

    private Mono<Long> update(String sql, String accountId) {
        return db.sql(sql).bind("id", accountId).fetch().rowsUpdated();
    }
}
