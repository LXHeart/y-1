package com.grassland.finance.freebie;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * freebie_escrow 数据访问（R2DBC 手写 SQL，风格镜像 {@code ReservationRepository}，ADR-D12）。
 *
 * <p>{@link #create} 用 UNIQUE(engagement_ref) + ON CONFLICT DO NOTHING 幂等（Saga 重试安全）；
 * 状态迁移用条件 UPDATE（status='reserved' 守卫），非 reserved → empty → 调用方判 409。
 */
@Component
public class FreebieEscrowRepository {

    private static final String SELECT_COLS =
            "id::text, engagement_ref, recommender_account_id::text, task_owner_account_id::text,"
                    + " organization_id::text, amount_cents, status, created_at, updated_at";

    private final DatabaseClient db;

    public FreebieEscrowRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建托管行（status=reserved）。UNIQUE(engagement_ref) 冲突 → empty（调用方读胜者判幂等/范围冲突）。 */
    public Mono<FreebieEscrow> create(String engagementRef, String recommenderAccountId,
                                      String taskOwnerAccountId, String organizationId, long amountCents) {
        var spec = db.sql("""
                INSERT INTO freebie_escrow(id, engagement_ref, recommender_account_id, task_owner_account_id,
                                           organization_id, amount_cents, status)
                VALUES (CAST(:id AS uuid), :ref, CAST(:recommender AS uuid), CAST(:owner AS uuid),
                        CAST(:org AS uuid), :amt, 'reserved')
                ON CONFLICT (engagement_ref) DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("ref", engagementRef)
                .bind("recommender", recommenderAccountId)
                .bind("org", organizationId)
                .bind("amt", amountCents);
        spec = (taskOwnerAccountId == null || taskOwnerAccountId.isBlank())
                ? spec.bindNull("owner", String.class) : spec.bind("owner", taskOwnerAccountId);
        return spec.map(FreebieEscrowRepository::map).one();
    }

    public Mono<FreebieEscrow> findByEngagementRef(String engagementRef) {
        return db.sql("SELECT " + SELECT_COLS + " FROM freebie_escrow WHERE engagement_ref = :ref")
                .bind("ref", engagementRef)
                .map(FreebieEscrowRepository::map).one();
    }

    /** 退还推荐官：reserved → refunded。0 行（非 reserved / 不存在）→ empty。 */
    public Mono<FreebieEscrow> markRefunded(String id) {
        return transition(id, "refunded");
    }

    /** 补偿商家：reserved → compensated。0 行（非 reserved / 不存在）→ empty。 */
    public Mono<FreebieEscrow> markCompensated(String id) {
        return transition(id, "compensated");
    }

    private Mono<FreebieEscrow> transition(String id, String targetStatus) {
        return db.sql("""
                UPDATE freebie_escrow SET status = :target, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'reserved'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("target", targetStatus)
                .bind("id", id)
                .map(FreebieEscrowRepository::map).one();
    }

    private static FreebieEscrow map(Readable row) {
        return new FreebieEscrow(
                row.get("id", String.class),
                row.get("engagement_ref", String.class),
                row.get("recommender_account_id", String.class),
                row.get("task_owner_account_id", String.class),
                row.get("organization_id", String.class),
                row.get("amount_cents", Long.class),
                row.get("status", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
