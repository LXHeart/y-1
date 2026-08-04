package com.grassland.identity.kyb;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * withdrawal_account 数据访问（R2DBC {@link DatabaseClient} 手写 SQL）。
 * GL-P3-MERCHANT-001。
 */
@Component
public class WithdrawalAccountRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, account_type, account_name, account_number_encrypted, bank_name,"
                    + " branch_name, is_default, status, submitted_at, reviewed_at, reviewer_account_id::text,"
                    + " review_note, created_at, updated_at";

    private final DatabaseClient db;

    public WithdrawalAccountRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建收款账户。*/
    public Mono<WithdrawalAccount> create(String organizationId, String accountType, String accountName,
                                          String accountNumberEncrypted, String bankName, String branchName) {
        UUID id = UUID.randomUUID();
        var spec = db.sql("""
                INSERT INTO withdrawal_account(id, organization_id, account_type, account_name,
                        account_number_encrypted, bank_name, branch_name)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), :type, :name, :numberEnc, :bank, :branch)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId)
                .bind("numberEnc", accountNumberEncrypted);
        // bank_name/branch_name/account_type/account_name 都可空（V18 未加 NOT NULL）。
        // R2DBC 的 bind(name, null) 直接抛 IllegalArgumentException → 省略任一可空字段就是 500。
        spec = bindNullable(spec, "type", accountType);
        spec = bindNullable(spec, "name", accountName);
        spec = bindNullable(spec, "bank", bankName);
        spec = bindNullable(spec, "branch", branchName);
        return spec.map(WithdrawalAccountRepository::map).one();
    }

    /** 查询收款账户。*/
    public Mono<WithdrawalAccount> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM withdrawal_account WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(WithdrawalAccountRepository::map).one();
    }

    /** 列出组织下所有收款账户。*/
    public Flux<WithdrawalAccount> findByOrganization(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM withdrawal_account WHERE organization_id = CAST(:org AS uuid) ORDER BY created_at DESC")
                .bind("org", organizationId)
                .map(WithdrawalAccountRepository::map).all();
    }

    /**
     * 更新账户。**按 org 作用域**，状态限 pending/rejected（被拒可改后重新提交，见
     * {@link WithdrawalAccountStatus#isEditable()}；审核中与已批准不可改）。
     *
     * <p>此前谓词只有 {@code id = … AND status = 'pending'}：① 无 org 限定 → 跨租户改他人收款账号
     * （收款账户是资金出口，被改指向攻击者账号是直接资金损失）；② 硬编码 pending → 被拒账户永久锁死。
     */
    public Mono<WithdrawalAccount> update(UUID id, String organizationId, String accountType, String accountName,
                                          String accountNumberEncrypted, String bankName, String branchName) {
        var spec = db.sql("""
                UPDATE withdrawal_account
                SET account_type = :type, account_name = :name, account_number_encrypted = :numberEnc,
                    bank_name = :bank, branch_name = :branch, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND organization_id = CAST(:org AS uuid)
                  AND status IN ('pending', 'rejected')
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId)
                .bind("numberEnc", accountNumberEncrypted);
        spec = bindNullable(spec, "type", accountType);
        spec = bindNullable(spec, "name", accountName);
        spec = bindNullable(spec, "bank", bankName);
        spec = bindNullable(spec, "branch", branchName);
        return spec.map(WithdrawalAccountRepository::map).one();
    }

    /** 按 org 作用域查询单个账户（避免跨租户读他人收款信息）。*/
    public Mono<WithdrawalAccount> findByIdAndOrganization(UUID id, String organizationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM withdrawal_account"
                + " WHERE id = CAST(:id AS uuid) AND organization_id = CAST(:org AS uuid)")
                .bind("id", id).bind("org", organizationId)
                .map(WithdrawalAccountRepository::map).one();
    }

    /** 更新状态（pending→under_review, under_review→approved/rejected）。*/
    public Mono<WithdrawalAccount> updateStatus(UUID id, String status, Instant submittedAt,
                                                Instant reviewedAt, String reviewerAccountId, String reviewNote) {
        var spec = db.sql("""
                UPDATE withdrawal_account
                SET status = :status, submitted_at = :submittedAt, reviewed_at = :reviewedAt,
                    reviewer_account_id = CAST(:reviewer AS uuid), review_note = :reviewNote, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("status", status);
        spec = bindNullable(spec, "submittedAt", submittedAt);
        spec = bindNullable(spec, "reviewedAt", reviewedAt);
        spec = bindNullable(spec, "reviewer", reviewerAccountId);
        spec = bindNullable(spec, "reviewNote", reviewNote);
        return spec.map(WithdrawalAccountRepository::map).one();
    }

    /** 删除账户，**按 org 作用域**；状态限 pending/rejected（审核中与已批准不可删）。*/
    public Mono<Long> deleteByIdAndOrganization(UUID id, String organizationId) {
        return db.sql("DELETE FROM withdrawal_account WHERE id = CAST(:id AS uuid)"
                + " AND organization_id = CAST(:org AS uuid) AND status IN ('pending', 'rejected')")
                .bind("id", id).bind("org", organizationId)
                .fetch().rowsUpdated();
    }

    /** 设置默认账户（同组织其他账户取消默认）。*/
    public Mono<WithdrawalAccount> setDefault(UUID id, String organizationId) {
        return db.sql("""
                UPDATE withdrawal_account SET is_default = false
                WHERE organization_id = CAST(:org AS uuid) AND id != CAST(:id AS uuid)
                """)
                .bind("org", organizationId).bind("id", id)
                .fetch().rowsUpdated()
                .then(db.sql("""
                        UPDATE withdrawal_account SET is_default = true, updated_at = now()
                        WHERE id = CAST(:id AS uuid) RETURNING %s
                        """.formatted(SELECT_COLS))
                        .bind("id", id)
                        .map(WithdrawalAccountRepository::map).one());
    }

    /** Admin 审核队列：列 pending/under_review。*/
    public Flux<WithdrawalAccount> findPending() {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM withdrawal_account WHERE status IN ('pending', 'under_review') ORDER BY submitted_at")
                .map(WithdrawalAccountRepository::map).all();
    }

    private static WithdrawalAccount map(Readable row) {
        return new WithdrawalAccount(
                UUID.fromString(row.get("id", String.class)),
                row.get("organization_id", String.class),
                row.get("account_type", String.class),
                row.get("account_name", String.class),
                row.get("account_number_encrypted", String.class),
                row.get("bank_name", String.class),
                row.get("branch_name", String.class),
                row.get("is_default", Boolean.class),
                row.get("status", String.class),
                toInstant(row.get("submitted_at", OffsetDateTime.class)),
                toInstant(row.get("reviewed_at", OffsetDateTime.class)),
                row.get("reviewer_account_id", String.class),
                row.get("review_note", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, Instant value) {
        if (value == null) {
            return spec.bindNull(name, OffsetDateTime.class);
        }
        return spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }
}
