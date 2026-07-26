package com.grassland.finance.escrow;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * funds_reservation 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，风格同 finance AccountRepository）。草场 Epic 4 Slice 4E。
 *
 * <p>{@link #create} 用 UNIQUE(engagement_ref) 保证幂等：Saga 重试时第二个 INSERT 触发违例 → empty，调用方判既有。
 * {@link #release} 条件 UPDATE（status='reserved' 守卫）：非 reserved → 0 行 → empty（调用方判 409）。
 */
@Component
public class ReservationRepository {

    private static final String SELECT_COLS =
            "id::text, account_id::text, organization_id::text, engagement_ref, amount_cents, status,"
                    + " payee_account_id::text, payout_cents, created_at, updated_at";

    private final DatabaseClient db;

    public ReservationRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建预留（status=reserved）。UNIQUE(engagement_ref) 违例 → empty（调用方判既有/幂等）。 */
    public Mono<FundsReservation> create(String accountId, String organizationId, String engagementRef,
                                         long amountCents, String payeeAccountId) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO funds_reservation(id, account_id, organization_id, engagement_ref, amount_cents, status,
                                              payee_account_id)
                VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), CAST(:org AS uuid), :ref, :amt, 'reserved',
                        CAST(:payee AS uuid))
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("acct", accountId).bind("org", organizationId)
                .bind("amt", amountCents);
        spec = bindNullable(spec, "ref", engagementRef);
        spec = bindNullable(spec, "payee", payeeAccountId);
        return spec.map(ReservationRepository::map).one()
                .onErrorResume(R2dbcDataIntegrityViolationException.class, e -> Mono.empty());
    }

    public Mono<FundsReservation> findByEngagementRef(String engagementRef) {
        return db.sql("SELECT " + SELECT_COLS + " FROM funds_reservation WHERE engagement_ref = :ref")
                .bind("ref", engagementRef)
                .map(ReservationRepository::map).one();
    }

    /** 释放：reserved → released。0 行（非 reserved / 不存在）→ empty。 */
    public Mono<FundsReservation> release(String id) {
        return db.sql("""
                UPDATE funds_reservation SET status = 'released', updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'reserved'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id)
                .map(ReservationRepository::map).one();
    }

    /**
     * 捕获（结算确认）：reserved → captured，并记下分账净额。0 行（非 reserved）→ empty。
     *
     * <p>商家余额在 reserve 时就已扣掉，故这里不动商家余额；{@code payoutCents} 是随后要打进推荐官钱包的净额
     * （无收款人时为 null = 不分账，钱留在平台账上，与本次改动前的行为一致）。
     */
    public Mono<FundsReservation> capture(String id, Long payoutCents) {
        var spec = db.sql("""
                UPDATE funds_reservation SET status = 'captured', payout_cents = :payout, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'reserved'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id);
        spec = payoutCents == null ? spec.bindNull("payout", Long.class) : spec.bind("payout", payoutCents);
        return spec.map(ReservationRepository::map).one();
    }

    /** 冲正（D-06 争议处置，Slice 6C Phase D）：captured → refunded。余额还原由 controller 调 accounts.credit（镜像 release）。
     *  0 行（非 captured）→ empty。 */
    public Mono<FundsReservation> reverse(String id) {
        return db.sql("""
                UPDATE funds_reservation SET status = 'refunded', updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'captured'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id)
                .map(ReservationRepository::map).one();
    }

    private static FundsReservation map(Readable row) {
        return new FundsReservation(
                row.get("id", String.class),
                row.get("account_id", String.class),
                row.get("organization_id", String.class),
                row.get("engagement_ref", String.class),
                row.get("amount_cents", Long.class),
                row.get("status", String.class),
                row.get("payee_account_id", String.class),
                row.get("payout_cents", Long.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
