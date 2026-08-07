-- Flyway sidecar disables the migration transaction because PostgreSQL concurrent index builds
-- must run outside transaction blocks. Keep this file index-only.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_task_feed_min_recommender_level
    ON task (min_recommender_level, created_at DESC, id DESC)
    WHERE status = 'published';

-- Batch reputation aggregation joins the requested account set to these two fact tables.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_task_application_recommender
    ON task_application(recommender_account_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_engagement_submission_recommender_application_created
    ON engagement_submission(recommender_account_id, application_id, created_at);
