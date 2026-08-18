package com.grassland.intelligence.speech;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SpeechTranscriptionRepository {

    private static final Pattern FAILURE_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final String COLUMNS = """
            id::text, media_reference_id::text, owner_account_id, organization_id,
            requested_language, detected_language, duration_ms, status, transcript_text,
            provider, model, platform_model_version, ai_run_id::text, failure_code,
            created_at, updated_at, completed_at
            """;

    private final DatabaseClient db;

    public SpeechTranscriptionRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<SpeechTranscription> createProcessing(SpeechTranscription value) {
        if (value == null || value.id() == null || value.mediaReferenceId() == null
                || value.ownerAccountId() == null || value.requestedLanguage() == null || value.durationMs() < 0) {
            return Mono.error(new IllegalArgumentException("A processing transcription requires identity and media fields"));
        }
        return db.sql("""
                INSERT INTO speech_transcription (
                    id, media_reference_id, owner_account_id, organization_id, requested_language,
                    duration_ms, status, created_at, updated_at
                ) VALUES (
                    CAST(:id AS uuid), CAST(:mediaReferenceId AS uuid), :ownerAccountId, :organizationId, :requestedLanguage,
                    :durationMs, 'processing', COALESCE(:createdAt, now()), COALESCE(:updatedAt, now())
                )
                RETURNING %s
                """.formatted(COLUMNS))
                .bind("id", value.id().toString())
                .bind("mediaReferenceId", value.mediaReferenceId().toString())
                .bind("ownerAccountId", value.ownerAccountId())
                .bind("organizationId", nullable(value.organizationId(), String.class))
                .bind("requestedLanguage", value.requestedLanguage())
                .bind("durationMs", value.durationMs())
                .bind("createdAt", nullable(value.createdAt(), java.time.Instant.class))
                .bind("updatedAt", nullable(value.updatedAt(), java.time.Instant.class))
                .map(SpeechTranscriptionRepository::map)
                .one();
    }

    public Mono<SpeechTranscription> findOwned(UUID id, String ownerAccountId) {
        return db.sql("SELECT " + COLUMNS + " FROM speech_transcription WHERE id = CAST(:id AS uuid) AND owner_account_id = :ownerAccountId")
                .bind("id", id.toString())
                .bind("ownerAccountId", ownerAccountId)
                .map(SpeechTranscriptionRepository::map)
                .one();
    }

    public Mono<Boolean> storeProviderResult(
            UUID id, String text, String detectedLanguage, String provider, String model,
            Integer platformModelVersion, UUID runId) {
        if (text == null || provider == null || model == null || runId == null) {
            return Mono.error(new IllegalArgumentException("A provider result requires text, provider, model and run id"));
        }
        return changed(db.sql("""
                UPDATE speech_transcription
                SET transcript_text = :text, detected_language = :detectedLanguage, provider = :provider,
                    model = :model, platform_model_version = :platformModelVersion,
                    ai_run_id = CAST(:runId AS uuid), failure_code = NULL, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'processing'
                """)
                .bind("id", id.toString())
                .bind("text", text)
                .bind("detectedLanguage", nullable(detectedLanguage, String.class))
                .bind("provider", provider)
                .bind("model", model)
                .bind("platformModelVersion", nullable(platformModelVersion, Integer.class))
                .bind("runId", runId.toString()));
    }

    public Mono<Boolean> markCompleted(UUID id) {
        return changed(db.sql("""
                UPDATE speech_transcription
                SET status = 'completed', completed_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'processing'
                  AND transcript_text IS NOT NULL AND provider IS NOT NULL AND model IS NOT NULL AND ai_run_id IS NOT NULL
                """).bind("id", id.toString()));
    }

    public Mono<Boolean> markFailed(UUID id, String failureCode) {
        return changed(db.sql("""
                UPDATE speech_transcription
                SET status = 'failed', failure_code = :failureCode, transcript_text = NULL,
                    detected_language = NULL, provider = NULL, model = NULL, platform_model_version = NULL,
                    ai_run_id = NULL, completed_at = NULL, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'processing'
                """).bind("id", id.toString()).bind("failureCode", failureCode(failureCode)));
    }

    private static Mono<Boolean> changed(DatabaseClient.GenericExecuteSpec statement) {
        return statement.fetch().rowsUpdated().map(rows -> rows > 0).defaultIfEmpty(false);
    }

    private static SpeechTranscription map(Readable row) {
        return new SpeechTranscription(
                uuid(row.get("id", String.class)), uuid(row.get("media_reference_id", String.class)),
                row.get("owner_account_id", String.class), row.get("organization_id", String.class),
                row.get("requested_language", String.class), row.get("detected_language", String.class),
                value(row.get("duration_ms", Long.class), 0L), row.get("status", String.class),
                row.get("transcript_text", String.class), row.get("provider", String.class), row.get("model", String.class),
                row.get("platform_model_version", Integer.class), uuid(row.get("ai_run_id", String.class)),
                row.get("failure_code", String.class), instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("updated_at", OffsetDateTime.class)), instant(row.get("completed_at", OffsetDateTime.class)));
    }

    private static String failureCode(String value) {
        if (value == null || !FAILURE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("failureCode must be a stable lowercase code");
        }
        return value;
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static long value(Long value, long fallback) { return value == null ? fallback : value; }
    private static java.time.Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
