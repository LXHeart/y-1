package com.grassland.marketplace.matching;

import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Batch candidate facts derived only from marketplace-owned application and task data. */
@Component
public class RecommenderMatchingRepository {

    private final DatabaseClient db;

    public RecommenderMatchingRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<MatchingCandidate> findCandidates(String taskId, String taskPlatform, String ownerAccountId) {
        return db.sql("""
                WITH candidate AS (
                  SELECT DISTINCT history.recommender_account_id AS account_id
                  FROM task_application history
                  WHERE history.recommender_account_id <> CAST(:owner AS uuid)
                    AND NOT EXISTS (
                      SELECT 1 FROM task_application current_application
                      WHERE current_application.task_id = CAST(:taskId AS uuid)
                        AND current_application.recommender_account_id = history.recommender_account_id
                    )
                ),
                platform_experience AS (
                  SELECT application.recommender_account_id AS account_id, COUNT(*)::int AS engagement_count
                  FROM task_application application
                  JOIN task historical_task ON historical_task.id = application.task_id
                  JOIN candidate ON candidate.account_id = application.recommender_account_id
                  WHERE :platform <> ''
                    AND historical_task.platform = :platform
                    AND application.status IN ('accepted', 'refunded')
                  GROUP BY application.recommender_account_id
                )
                SELECT candidate.account_id::text AS account_id,
                       COALESCE(platform_experience.engagement_count, 0)::int AS platform_engagement_count,
                       invitation.id::text AS invitation_id,
                       invitation.task_id::text AS invitation_task_id,
                       invitation.recommender_account_id::text AS invitation_recommender_account_id,
                       invitation.invited_by_account_id::text AS invited_by_account_id,
                       invitation.scoring_version,
                       invitation.score_snapshot::text AS score_snapshot,
                       invitation.created_at AS invitation_created_at,
                       invitation.applied_at AS invitation_applied_at
                FROM candidate
                LEFT JOIN platform_experience ON platform_experience.account_id = candidate.account_id
                LEFT JOIN task_recommender_invitation invitation
                  ON invitation.task_id = CAST(:taskId AS uuid)
                 AND invitation.recommender_account_id = candidate.account_id
                ORDER BY candidate.account_id
                """)
                .bind("owner", ownerAccountId)
                .bind("taskId", taskId)
                .bind("platform", taskPlatform == null ? "" : taskPlatform.trim())
                .map(RecommenderMatchingRepository::map).all();
    }

    private static MatchingCandidate map(Readable row) {
        String invitationId = row.get("invitation_id", String.class);
        TaskRecommenderInvitation invitation = invitationId == null ? null : new TaskRecommenderInvitation(
                invitationId,
                row.get("invitation_task_id", String.class),
                row.get("invitation_recommender_account_id", String.class),
                row.get("invited_by_account_id", String.class),
                row.get("scoring_version", String.class),
                row.get("score_snapshot", String.class),
                toInstant(row.get("invitation_created_at", java.time.OffsetDateTime.class)),
                toInstant(row.get("invitation_applied_at", java.time.OffsetDateTime.class)));
        Integer count = row.get("platform_engagement_count", Integer.class);
        return new MatchingCandidate(row.get("account_id", String.class), count == null ? 0 : count, invitation);
    }

    private static java.time.Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
