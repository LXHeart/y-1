-- P2: task visibility and acceptance-time entitlement snapshots.
ALTER TABLE task
    ADD COLUMN min_recommender_level smallint NOT NULL DEFAULT 1;
ALTER TABLE task
    ADD CONSTRAINT ck_task_min_recommender_level
        CHECK (min_recommender_level BETWEEN 1 AND 5) NOT VALID;
ALTER TABLE task VALIDATE CONSTRAINT ck_task_min_recommender_level;

ALTER TABLE task_version
    ADD COLUMN min_recommender_level smallint NOT NULL DEFAULT 1;
ALTER TABLE task_version
    ADD CONSTRAINT ck_task_version_min_recommender_level
        CHECK (min_recommender_level BETWEEN 1 AND 5) NOT VALID;
ALTER TABLE task_version VALIDATE CONSTRAINT ck_task_version_min_recommender_level;

ALTER TABLE task_application
    ADD COLUMN reputation_level_at_accept smallint,
    ADD COLUMN reputation_policy_version_at_accept bigint,
    ADD COLUMN settlement_delay_days_at_accept integer,
    ADD COLUMN commission_bonus_bps_at_accept integer,
    ADD COLUMN premium_support_at_accept boolean;

-- Never infer historical financial rights from an account's current level.
UPDATE task_application
SET reputation_level_at_accept = 1,
    reputation_policy_version_at_accept = 1,
    settlement_delay_days_at_accept = 2,
    commission_bonus_bps_at_accept = 0,
    premium_support_at_accept = false
WHERE status IN ('reserving', 'accepted', 'refunded') OR confirmed_at IS NOT NULL;

ALTER TABLE task_application
    ADD CONSTRAINT ck_application_reputation_level CHECK (
        reputation_level_at_accept IS NULL OR reputation_level_at_accept BETWEEN 1 AND 5) NOT VALID,
    ADD CONSTRAINT ck_application_reputation_policy CHECK (
        reputation_policy_version_at_accept IS NULL OR reputation_policy_version_at_accept >= 1) NOT VALID,
    ADD CONSTRAINT ck_application_settlement_delay CHECK (
        settlement_delay_days_at_accept IS NULL OR settlement_delay_days_at_accept BETWEEN 0 AND 30) NOT VALID,
    ADD CONSTRAINT ck_application_commission_bonus CHECK (
        commission_bonus_bps_at_accept IS NULL OR commission_bonus_bps_at_accept BETWEEN 0 AND 10000) NOT VALID,
    ADD CONSTRAINT ck_application_reputation_snapshot_complete CHECK (
        (reputation_level_at_accept IS NULL
            AND reputation_policy_version_at_accept IS NULL
            AND settlement_delay_days_at_accept IS NULL
            AND commission_bonus_bps_at_accept IS NULL
            AND premium_support_at_accept IS NULL)
        OR
        (reputation_level_at_accept IS NOT NULL
            AND reputation_policy_version_at_accept IS NOT NULL
            AND settlement_delay_days_at_accept IS NOT NULL
            AND commission_bonus_bps_at_accept IS NOT NULL
            AND premium_support_at_accept IS NOT NULL)) NOT VALID,
    ADD CONSTRAINT ck_application_reputation_snapshot_required CHECK (
        NOT (status IN ('reserving', 'accepted', 'refunded') OR confirmed_at IS NOT NULL)
        OR reputation_level_at_accept IS NOT NULL) NOT VALID;

-- VALIDATE takes SHARE UPDATE EXCLUSIVE rather than holding ACCESS EXCLUSIVE for a table scan,
-- so normal INSERT/UPDATE/DELETE traffic can continue after the brief ADD CONSTRAINT lock.
ALTER TABLE task_application
    VALIDATE CONSTRAINT ck_application_reputation_level,
    VALIDATE CONSTRAINT ck_application_reputation_policy,
    VALIDATE CONSTRAINT ck_application_settlement_delay,
    VALIDATE CONSTRAINT ck_application_commission_bonus,
    VALIDATE CONSTRAINT ck_application_reputation_snapshot_complete,
    VALIDATE CONSTRAINT ck_application_reputation_snapshot_required;
