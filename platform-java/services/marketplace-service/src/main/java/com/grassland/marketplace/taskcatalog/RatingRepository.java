package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** engagement_rating 数据访问（R2DBC 手写 SQL，house style）。 */
@Component
public class RatingRepository {

    private static final String SELECT_COLS =
            "id::text, application_id::text, task_id::text, recommender_account_id::text,"
                    + " rated_by_account_id::text, score, comment, created_at";

    private final DatabaseClient db;

    public RatingRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 评分。UNIQUE(application_id) 违例 → empty（调用方转 409「已评价」）：一次履约只评一次。 */
    public Mono<EngagementRating> create(String applicationId, String taskId, String recommenderAccountId,
                                         String ratedByAccountId, int score, String comment) {
        var spec = db.sql("""
                INSERT INTO engagement_rating(id, application_id, task_id, recommender_account_id,
                                              rated_by_account_id, score, comment)
                VALUES (CAST(:id AS uuid), CAST(:app AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid),
                        CAST(:by AS uuid), :score, :comment)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("app", applicationId).bind("task", taskId)
                .bind("rec", recommenderAccountId).bind("by", ratedByAccountId)
                .bind("score", score);
        spec = bindNullable(spec, "comment", comment);
        return spec.map(RatingRepository::map).one()
                .onErrorResume(DataIntegrityViolationException.class, e -> Mono.empty());
    }

    /** 某次履约的评分（未评 → empty）。 */
    public Mono<EngagementRating> findByApplication(String applicationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_rating"
                + " WHERE application_id = CAST(:app AS uuid)")
                .bind("app", applicationId)
                .map(RatingRepository::map).one();
    }

    private static EngagementRating map(Readable row) {
        Number score = row.get("score", Number.class);
        return new EngagementRating(
                row.get("id", String.class),
                row.get("application_id", String.class),
                row.get("task_id", String.class),
                row.get("recommender_account_id", String.class),
                row.get("rated_by_account_id", String.class),
                score == null ? 0 : score.intValue(),
                row.get("comment", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
