package com.grassland.marketplace.benefit;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * experience_benefit 数据访问（任务书 #96 C96-03，表建于 V57）。guarded UPDATE + RETURNING，
 * 0 行 = 状态已被并发路径处理；失约成立扫描用部分索引（已主张/未决/到期）。
 */
@Component
public class ExperienceBenefitRepository {

    private static final String SELECT_COLS = "id::text, application_id::text, store_id::text,"
            + " items::text AS items_json, booking_window, fulfilled_at, fulfilled_confirmed_by::text,"
            + " default_claimed_at, default_deadline_at, default_resolved_at, default_resolution,"
            + " status, created_at, updated_at";

    private final DatabaseClient db;

    public ExperienceBenefitRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<ExperienceBenefit> findByApplication(String applicationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM experience_benefit"
                        + " WHERE application_id = CAST(:app AS uuid)")
                .bind("app", applicationId).map(ExperienceBenefitRepository::map).one();
    }

    /** 派发扫描：已主张、未决、回应窗已过的权益行（V57 部分索引覆盖）。 */
    public Flux<ExperienceBenefit> findDefaultEstablishable(int limit) {
        return db.sql("SELECT " + SELECT_COLS + " FROM experience_benefit"
                        + " WHERE default_claimed_at IS NOT NULL AND default_resolved_at IS NULL"
                        + " AND status = 'booked' AND default_deadline_at <= now()"
                        + " ORDER BY default_deadline_at LIMIT :limit")
                .bind("limit", Math.max(1, limit)).map(ExperienceBenefitRepository::map).all();
    }

    /**
     * 派发扫描：已成立、近 24h 内的 defaulted 行——押金全退腿的 durable 重放窗口（freebieRefund 幂等，
     * 超窗未收敛转人工对账）。404/409 视作成功，重放无副作用。
     */
    public Flux<ExperienceBenefit> findDefaultRefundReplayable(int limit) {
        return db.sql("SELECT " + SELECT_COLS + " FROM experience_benefit"
                        + " WHERE status = 'merchant_defaulted' AND default_resolved_at > now() - interval '24 hours'"
                        + " ORDER BY default_resolved_at LIMIT :limit")
                .bind("limit", Math.max(1, limit)).map(ExperienceBenefitRepository::map).all();
    }

    /** 预约：初始行直接建为 booked；pending_booking → booked。其余状态 0 行（调用方 409）。 */
    public Mono<ExperienceBenefit> upsertBooking(String applicationId, String storeId, String itemsJson,
            Instant bookingWindow) {
        String id = UUID.randomUUID().toString();
        GenericExecuteSpec insert = db.sql("""
                INSERT INTO experience_benefit(id, application_id, store_id, items, booking_window, status)
                VALUES (CAST(:id AS uuid), CAST(:app AS uuid), CAST(:store AS uuid), CAST(:items AS jsonb),
                        :window, 'booked')
                ON CONFLICT (application_id) DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("app", applicationId).bind("items", itemsJson).bind("window",
                        bookingWindow == null ? null : bookingWindow.atOffset(java.time.ZoneOffset.UTC));
        insert = storeId == null ? insert.bindNull("store", UUID.class)
                : insert.bind("store", UUID.fromString(storeId));
        Mono<ExperienceBenefit> inserted = insert.map(ExperienceBenefitRepository::map).one()
                .onErrorResume(io.r2dbc.spi.R2dbcDataIntegrityViolationException.class, e -> Mono.empty());
        GenericExecuteSpec promote = db.sql("""
                UPDATE experience_benefit
                SET status = 'booked', items = CAST(:items AS jsonb), booking_window = :window, updated_at = now()
                WHERE application_id = CAST(:app AS uuid) AND status = 'pending_booking'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("items", itemsJson).bind("app", applicationId)
                .bind("window", bookingWindow == null ? null : bookingWindow.atOffset(java.time.ZoneOffset.UTC));
        return inserted.switchIfEmpty(promote.map(ExperienceBenefitRepository::map).one());
    }

    /** 推荐官主张兑现：booked → fulfilled（fulfilled_at=now）。 */
    public Mono<ExperienceBenefit> markFulfilled(String applicationId) {
        return db.sql("""
                UPDATE experience_benefit
                SET status = 'fulfilled', fulfilled_at = now(), updated_at = now()
                WHERE application_id = CAST(:app AS uuid) AND status = 'booked'
                RETURNING %s
                """.formatted(SELECT_COLS)).bind("app", applicationId)
                .map(ExperienceBenefitRepository::map).one();
    }

    /** 商家兑现确认（幂等：已确认保持原值）。 */
    public Mono<ExperienceBenefit> confirmFulfillment(String applicationId, String merchantAccountId) {
        return db.sql("""
                UPDATE experience_benefit
                SET fulfilled_confirmed_by = COALESCE(fulfilled_confirmed_by, CAST(:by AS uuid)), updated_at = now()
                WHERE application_id = CAST(:app AS uuid) AND status = 'fulfilled'
                RETURNING %s
                """.formatted(SELECT_COLS)).bind("app", applicationId).bind("by", merchantAccountId)
                .map(ExperienceBenefitRepository::map).one();
    }

    /** 取消：pending_booking|booked → cancelled。 */
    public Mono<ExperienceBenefit> cancel(String applicationId) {
        return db.sql("""
                UPDATE experience_benefit
                SET status = 'cancelled', updated_at = now()
                WHERE application_id = CAST(:app AS uuid) AND status IN ('pending_booking', 'booked')
                RETURNING %s
                """.formatted(SELECT_COLS)).bind("app", applicationId)
                .map(ExperienceBenefitRepository::map).one();
    }

    /** 失约主张：booked + 未主张 → 设主张与回应窗截止。 */
    public Mono<ExperienceBenefit> claimDefault(String applicationId, long responseSeconds) {
        return db.sql("""
                UPDATE experience_benefit
                SET default_claimed_at = now(),
                    default_deadline_at = now() + (:seconds * interval '1 second'),
                    updated_at = now()
                WHERE application_id = CAST(:app AS uuid) AND status = 'booked'
                  AND default_claimed_at IS NULL
                RETURNING %s
                """.formatted(SELECT_COLS)).bind("app", applicationId).bind("seconds", Math.max(1, responseSeconds))
                .map(ExperienceBenefitRepository::map).one();
    }

    /** 商家限时回应（否认）：已主张、未决 → 结局 denied，status 回 booked（继续履约）。 */
    public Mono<ExperienceBenefit> respondDefaultDenied(String applicationId) {
        return db.sql("""
                UPDATE experience_benefit
                SET default_resolved_at = now(), default_resolution = 'denied', updated_at = now()
                WHERE application_id = CAST(:app AS uuid)
                  AND default_claimed_at IS NOT NULL AND default_resolved_at IS NULL
                RETURNING %s
                """.formatted(SELECT_COLS)).bind("app", applicationId)
                .map(ExperienceBenefitRepository::map).one();
    }

    /** 失约自动成立（单边胜出）：已主张、未决、回应窗已过 → merchant_defaulted + 结局 defaulted。 */
    public Mono<ExperienceBenefit> establishDefault(String applicationId) {
        return db.sql("""
                UPDATE experience_benefit
                SET status = 'merchant_defaulted',
                    default_resolved_at = now(), default_resolution = 'defaulted', updated_at = now()
                WHERE application_id = CAST(:app AS uuid)
                  AND status = 'booked'
                  AND default_claimed_at IS NOT NULL
                  AND default_resolved_at IS NULL
                  AND default_deadline_at <= now()
                RETURNING %s
                """.formatted(SELECT_COLS)).bind("app", applicationId)
                .map(ExperienceBenefitRepository::map).one();
    }

    private static ExperienceBenefit map(Readable row) {
        return new ExperienceBenefit(row.get("id", String.class), row.get("application_id", String.class),
                row.get("store_id", String.class), row.get("items_json", String.class),
                toInstant(row.get("booking_window", OffsetDateTime.class)),
                toInstant(row.get("fulfilled_at", OffsetDateTime.class)),
                row.get("fulfilled_confirmed_by", String.class),
                toInstant(row.get("default_claimed_at", OffsetDateTime.class)),
                toInstant(row.get("default_deadline_at", OffsetDateTime.class)),
                toInstant(row.get("default_resolved_at", OffsetDateTime.class)),
                row.get("default_resolution", String.class), row.get("status", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
