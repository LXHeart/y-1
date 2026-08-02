package com.grassland.finance.ledger;

import io.r2dbc.spi.Readable;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 账本持久化（HLD §6.4 双录账本，ADR-D01）。
 *
 * <p>{@link #postJournal} **不自启事务**——调用方在其既有 {@code TransactionalOperator} 作用域内调用，
 * 使「余额守卫条件 UPDATE + 账本记账 + outbox append」同生共死（同 Slice 7C 的 {@code OutboxRepository.append}）。
 * 余额行保留为投影/并发守卫（Approach B），账本是不可变真相源 + 可重建投影。
 */
@Component
public class LedgerRepository {

    /** sandbox/stub 通道名（{@code EXTERNAL} 账户 owner）；真实 PSP 接入时改由 {@code PaymentProviderAdapter} 决定。 */
    public static final String SANDBOX_CHANNEL = "sandbox";

    private final DatabaseClient db;

    public LedgerRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 写一条 journal + 其全部 posting。操作在调用方事务内执行。 */
    public Mono<Void> postJournal(JournalEntry journal, List<Posting> postings) {
        return postJournalHead(journal).then(postPostings(journal.id(), postings));
    }

    /** 幂等预检：同一 operationId 是否已记账（credit-bridge 三件套之一；null 直接 empty）。 */
    public Mono<UUID> findJournalIdByOperationId(String operationId) {
        if (operationId == null) {
            return Mono.empty();
        }
        return db.sql("SELECT id::text FROM journal WHERE operation_id = :opId LIMIT 1")
                .bind("opId", operationId)
                .map(row -> UUID.fromString(row.get("id", String.class)))
                .one();
    }

    /** 重建投影：某账户的余额 = SUM(credit) - SUM(debit)（负债/收入类账户）。owner 为 null 时按 type 匹配 null owner。 */
    public Mono<Long> sumBalance(LedgerAccount.Type type, String owner) {
        GenericExecuteSpec spec = db.sql("""
                SELECT COALESCE(SUM(
                    CASE direction
                        WHEN 'CREDIT' THEN amount_cents
                        WHEN 'DEBIT' THEN -amount_cents
                    END
                ), 0)::bigint AS balance
                FROM posting
                WHERE account_type = :type
                  AND (account_owner = :owner OR (account_owner IS NULL AND CAST(:owner AS text) IS NULL))
                """)
                .bind("type", type.dbValue());
        spec = owner == null ? spec.bindNull("owner", String.class) : spec.bind("owner", owner);
        return spec.map(row -> row.get("balance", Long.class)).one().defaultIfEmpty(0L);
    }

    /** 列出某 journal 的全部 posting（审计/校验用）。 */
    public Mono<List<Posting>> findPostingsByJournal(UUID journalId) {
        return db.sql("""
                SELECT account_type, account_owner, account_ref, direction, amount_cents
                FROM posting WHERE journal_id = CAST(:journalId AS uuid)
                ORDER BY direction, amount_cents
                """)
                .bind("journalId", journalId)
                .map(LedgerRepository::mapPosting).all().collectList();
    }

    public Mono<Long> journalCount() {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM journal")
                .map(row -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
    }

    private Mono<Void> postJournalHead(JournalEntry journal) {
        // created_at 由 DB DEFAULT now() 填充（避免 text→timestamptz 的绑定类型问题）。
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO journal (id, journal_type, operation_id, currency, organization_id, engagement_ref, memo)
                VALUES (CAST(:id AS uuid), :journalType, :operationId, :currency,
                        CAST(:orgId AS uuid), :engagementRef, :memo)
                """)
                .bind("id", journal.id())
                .bind("journalType", journal.type().dbValue())
                .bind("currency", journal.currency() == null ? "CNY" : journal.currency());
        spec = bindNullable(spec, "operationId", journal.operationId());
        spec = bindNullableUuid(spec, "orgId", journal.organizationId());
        spec = bindNullable(spec, "engagementRef", journal.engagementRef());
        spec = bindNullable(spec, "memo", journal.memo());
        return spec.then();
    }

    private Mono<Void> postPostings(UUID journalId, List<Posting> postings) {
        Mono<Void> chain = Mono.empty();
        for (Posting posting : postings) {
            chain = chain.then(insertPosting(journalId, posting));
        }
        return chain;
    }

    private Mono<Void> insertPosting(UUID journalId, Posting posting) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO posting (id, journal_id, account_type, account_owner, account_ref, direction, amount_cents, created_at)
                VALUES (CAST(:id AS uuid), CAST(:journalId AS uuid), :accountType, :accountOwner, :accountRef,
                        :direction, :amountCents, now())
                """)
                .bind("id", UUID.randomUUID())
                .bind("journalId", journalId)
                .bind("accountType", posting.account().type().dbValue())
                .bind("direction", posting.direction().dbValue())
                .bind("amountCents", posting.amountCents());
        spec = bindNullable(spec, "accountOwner", posting.account().owner());
        spec = bindNullable(spec, "accountRef", posting.account().ref());
        return spec.then();
    }

    private static Posting mapPosting(Readable row) {
        return new Posting(
                new LedgerAccount(
                        LedgerAccount.Type.valueOf(row.get("account_type", String.class)),
                        row.get("account_owner", String.class),
                        row.get("account_ref", String.class)),
                Posting.Direction.valueOf(row.get("direction", String.class)),
                row.get("amount_cents", Long.class));
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableUuid(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
