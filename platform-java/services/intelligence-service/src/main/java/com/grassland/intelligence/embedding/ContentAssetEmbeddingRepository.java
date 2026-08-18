package com.grassland.intelligence.embedding;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ContentAssetEmbeddingRepository {

    private static final Pattern FAILURE_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {};
    private static final String COLUMNS = """
            id::text, asset_id::text, asset_version, content_hash, status, provider, model,
            model_version_key, algorithm_version, dimensions, embedding::text, ai_run_id::text,
            failure_code, attempt_count, next_attempt_at, claim_token::text, claimed_until,
            created_at, updated_at, completed_at
            """;
    private static final String CLAIM_COLUMNS = """
            target.id::text, target.asset_id::text, target.asset_version, target.content_hash, target.status,
            target.provider, target.model, target.model_version_key, target.algorithm_version,
            target.dimensions, target.embedding::text, target.ai_run_id::text, target.failure_code,
            target.attempt_count, target.next_attempt_at, target.claim_token::text, target.claimed_until,
            target.created_at, target.updated_at, target.completed_at
            """;

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ContentAssetEmbeddingRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Boolean> enqueue(UUID assetId, int version, String contentHash) {
        if (assetId == null || version <= 0 || contentHash == null || contentHash.isBlank()) {
            return Mono.error(new IllegalArgumentException("An embedding requires asset, positive version and content hash"));
        }
        return db.sql("""
                INSERT INTO content_asset_embedding (asset_id, asset_version, content_hash, status)
                VALUES (CAST(:assetId AS uuid), :version, :contentHash, 'pending')
                ON CONFLICT (asset_id, asset_version, content_hash) WHERE status IN ('pending', 'processing') DO NOTHING
                """)
                .bind("assetId", assetId.toString())
                .bind("version", version)
                .bind("contentHash", contentHash)
                .fetch().rowsUpdated().map(rows -> rows > 0).defaultIfEmpty(false);
    }

    public Flux<ContentAssetEmbedding> claimBatch(int limit, UUID claimToken, Duration lease, int maxAttempts) {
        if (limit < 1 || claimToken == null || lease == null || lease.toMillis() < 1 || maxAttempts < 1) {
            return Flux.error(new IllegalArgumentException("A positive limit, token, positive lease and positive max attempts are required"));
        }
        return db.sql("""
                WITH candidates AS (
                    SELECT candidate.id
                    FROM content_asset_embedding AS candidate
                    WHERE candidate.attempt_count < :maxAttempts
                      AND (
                          (candidate.status IN ('pending', 'failed') AND candidate.next_attempt_at <= now())
                          OR (candidate.status = 'processing' AND candidate.claimed_until <= now())
                      )
                    ORDER BY candidate.next_attempt_at, candidate.created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE content_asset_embedding AS target
                SET status = 'processing', claim_token = CAST(:claimToken AS uuid),
                    claimed_until = now() + (:leaseMillis * interval '1 millisecond'),
                    attempt_count = target.attempt_count + 1, failure_code = NULL, updated_at = now()
                FROM candidates
                WHERE target.id = candidates.id
                RETURNING %s
                """.formatted(CLAIM_COLUMNS))
                .bind("maxAttempts", maxAttempts)
                .bind("limit", limit)
                .bind("claimToken", claimToken.toString())
                .bind("leaseMillis", lease.toMillis())
                .map(this::map)
                .all();
    }

    public Flux<ContentAssetEmbedding> claimBatch(int limit, UUID claimToken, Duration lease) {
        return claimBatch(limit, claimToken, lease, Integer.MAX_VALUE);
    }

    public Mono<Boolean> markReady(
            UUID id, UUID claimToken, ProviderResolution provider, String algorithmVersion,
            List<Double> vector, UUID runId) {
        if (provider == null || provider.isDenied() || provider.provider() == null || provider.model() == null
                || algorithmVersion == null || algorithmVersion.isBlank() || runId == null) {
            return Mono.error(new IllegalArgumentException("A ready embedding requires an allowed provider snapshot and run id"));
        }
        String vectorJson;
        try {
            validateVector(vector);
            vectorJson = mapper.writeValueAsString(vector);
        } catch (Exception error) {
            return Mono.error(new IllegalArgumentException("Embedding must be a finite numeric vector", error));
        }
        return changed(db.sql("""
                UPDATE content_asset_embedding
                SET status = 'ready', provider = :provider, model = :model,
                    model_version_key = :modelVersionKey, algorithm_version = :algorithmVersion,
                    dimensions = :dimensions, embedding = CAST(:embedding AS jsonb), ai_run_id = CAST(:runId AS uuid),
                    failure_code = NULL, claim_token = NULL, claimed_until = NULL,
                    completed_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'processing' AND claim_token = CAST(:claimToken AS uuid)
                """)
                .bind("id", id.toString())
                .bind("claimToken", claimToken.toString())
                .bind("provider", provider.provider())
                .bind("model", provider.model())
                .bind("modelVersionKey", provider.modelVersionKey())
                .bind("algorithmVersion", algorithmVersion)
                .bind("dimensions", vector.size())
                .bind("embedding", vectorJson)
                .bind("runId", runId.toString()));
    }

    public Mono<Boolean> markFailed(UUID id, UUID claimToken, String failureCode, Duration delay) {
        if (delay == null) {
            return Mono.error(new IllegalArgumentException("Retry delay is required"));
        }
        return changed(db.sql("""
                UPDATE content_asset_embedding
                SET status = 'failed', failure_code = :failureCode,
                    claim_token = NULL, claimed_until = NULL,
                    next_attempt_at = now() + (:delayMillis * interval '1 millisecond'), updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'processing' AND claim_token = CAST(:claimToken AS uuid)
                """)
                .bind("id", id.toString())
                .bind("claimToken", claimToken.toString())
                .bind("failureCode", failureCode(failureCode))
                .bind("delayMillis", Math.max(delay.toMillis(), 1L)));
    }

    public Mono<Boolean> markStale(UUID id, UUID claimToken) {
        return changed(db.sql("""
                UPDATE content_asset_embedding
                SET status = 'stale', claim_token = NULL, claimed_until = NULL, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'processing' AND claim_token = CAST(:claimToken AS uuid)
                """).bind("id", id.toString()).bind("claimToken", claimToken.toString()));
    }

    public Flux<ContentAssetEmbedding> findReadyForAssets(Collection<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Flux.empty();
        }
        DatabaseClient.GenericExecuteSpec statement = db.sql("""
                SELECT %s FROM content_asset_embedding
                WHERE status = 'ready' AND asset_id IN (%s)
                ORDER BY asset_id, asset_version DESC, completed_at DESC
                """.formatted(COLUMNS, placeholders(assetIds.size())));
        int index = 0;
        for (UUID assetId : assetIds) {
            statement = statement.bind("asset" + index++, assetId.toString());
        }
        return statement.map(this::map).all();
    }

    private static Mono<Boolean> changed(DatabaseClient.GenericExecuteSpec statement) {
        return statement.fetch().rowsUpdated().map(rows -> rows > 0).defaultIfEmpty(false);
    }

    private ContentAssetEmbedding map(Readable row) {
        return new ContentAssetEmbedding(
                uuid(row.get("id", String.class)), uuid(row.get("asset_id", String.class)),
                value(row.get("asset_version", Integer.class), 0), row.get("content_hash", String.class),
                row.get("status", String.class), row.get("provider", String.class), row.get("model", String.class),
                row.get("model_version_key", String.class), row.get("algorithm_version", String.class),
                row.get("dimensions", Integer.class), vector(row.get("embedding", String.class)),
                uuid(row.get("ai_run_id", String.class)), row.get("failure_code", String.class),
                value(row.get("attempt_count", Integer.class), 0), instant(row.get("next_attempt_at", OffsetDateTime.class)),
                uuid(row.get("claim_token", String.class)), instant(row.get("claimed_until", OffsetDateTime.class)),
                instant(row.get("created_at", OffsetDateTime.class)), instant(row.get("updated_at", OffsetDateTime.class)),
                instant(row.get("completed_at", OffsetDateTime.class)));
    }

    private List<Double> vector(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, DOUBLE_LIST);
        } catch (Exception error) {
            throw new IllegalStateException("Invalid persisted embedding JSON", error);
        }
    }

    private static void validateVector(List<Double> vector) {
        if (vector == null || vector.isEmpty() || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("Embedding must contain finite values");
        }
    }

    private static String placeholders(int size) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append("CAST(:asset").append(i).append(" AS uuid)");
        }
        return result.toString();
    }

    private static String failureCode(String value) {
        if (value == null || !FAILURE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("failureCode must be a stable lowercase code");
        }
        return value;
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static int value(Integer value, int fallback) { return value == null ? fallback : value; }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
