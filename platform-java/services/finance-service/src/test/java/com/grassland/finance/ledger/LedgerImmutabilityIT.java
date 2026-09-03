package com.grassland.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.finance.FinanceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 账本 V21 不可变触发器与借贷平衡约束触发器（任务书 #67 卡 E）。
 *
 * <p>锁定：
 * 1) UPDATE/DELETE posting/journal 被触发器拦截（append-only，仅维护通道可删）；
 * 2) 借贷不平衡 journal 在 COMMIT 时被 DEFERRABLE 约束触发器拦截。
 */
class LedgerImmutabilityIT extends FinanceItSupport {

    private static final String PREFIX = "it-immutable:";

    @Autowired
    private TransactionalOperator transactionalOperator;

    @AfterEach
    void cleanUp() {
        db.sql("SELECT ledger_maintenance_delete_journals(:prefix)")
                .bind("prefix", PREFIX + "%")
                .then()
                .block();
    }

    @Test
    @DisplayName("UPDATE posting 被触发器拦截")
    void updatePostingIsBlocked() {
        String journalId = seedBalancedJournal();

        assertThatThrownBy(() ->
                db.sql("UPDATE posting SET account_owner = 'x' WHERE journal_id = :jid::uuid")
                        .bind("jid", journalId)
                        .then()
                        .block()
        ).satisfies(ex -> {
            String message = extractExceptionMessage(ex);
            assertThat(message).contains("append-only");
        });
    }

    @Test
    @DisplayName("DELETE journal 被触发器拦截")
    void deleteJournalIsBlocked() {
        String journalId = seedBalancedJournal();

        assertThatThrownBy(() ->
                db.sql("DELETE FROM journal WHERE id = :jid::uuid")
                        .bind("jid", journalId)
                        .then()
                        .block()
        ).satisfies(ex -> {
            String message = extractExceptionMessage(ex);
            assertThat(message).contains("append-only");
        });
    }

    @Test
    @DisplayName("维护通道可删除账本行")
    void maintenanceFunctionCanDeleteLedgerRows() {
        String journalId = seedBalancedJournal();

        db.sql("SELECT ledger_maintenance_delete_journals(:prefix)")
                .bind("prefix", PREFIX + "%")
                .then()
                .block();

        Long count = db.sql("SELECT COUNT(*) FROM journal WHERE id = :jid::uuid")
                .bind("jid", journalId)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("不平衡 journal 在 COMMIT 时被约束触发器拦截")
    void unbalancedJournalIsBlockedAtCommit() {
        String journalId = UUID.randomUUID().toString();
        String postingId = UUID.randomUUID().toString();
        String operationId = PREFIX + UUID.randomUUID();

        assertThatThrownBy(() ->
                transactionalOperator.execute(status ->
                        db.sql("""
                                INSERT INTO journal (id, journal_type, operation_id, currency)
                                VALUES (:jid::uuid, 'DEPOSIT', :opid, 'CNY')
                                """)
                                .bind("jid", journalId)
                                .bind("opid", operationId)
                                .then()
                                .then(db.sql("""
                                        INSERT INTO posting (id, journal_id, account_type, direction, amount_cents)
                                        VALUES (:pid::uuid, :jid::uuid, 'ESCROW', 'DEBIT', 100)
                                        """)
                                        .bind("pid", postingId)
                                        .bind("jid", journalId)
                                        .then())
                ).then().block()
        ).satisfies(ex -> {
            String message = extractExceptionMessage(ex);
            assertThat(message).contains("unbalanced");
        });

        // 事务已回滚，该 journal 不应存在
        Long count = db.sql("SELECT COUNT(*) FROM journal WHERE id = :jid::uuid")
                .bind("jid", journalId)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();

        assertThat(count).isZero();
    }

    private String seedBalancedJournal() {
        String journalId = UUID.randomUUID().toString();
        String debitId = UUID.randomUUID().toString();
        String creditId = UUID.randomUUID().toString();
        String operationId = PREFIX + UUID.randomUUID();

        transactionalOperator.execute(status ->
                db.sql("""
                        INSERT INTO journal (id, journal_type, operation_id, currency)
                        VALUES (:jid::uuid, 'DEPOSIT', :opid, 'CNY')
                        """)
                        .bind("jid", journalId)
                        .bind("opid", operationId)
                        .then()
                        .then(db.sql("""
                                INSERT INTO posting (id, journal_id, account_type, direction, amount_cents)
                                VALUES (:did::uuid, :jid::uuid, 'ESCROW', 'DEBIT', 100)
                                """)
                                .bind("did", debitId)
                                .bind("jid", journalId)
                                .then())
                        .then(db.sql("""
                                INSERT INTO posting (id, journal_id, account_type, direction, amount_cents)
                                VALUES (:cid::uuid, :jid::uuid, 'EXTERNAL', 'CREDIT', 100)
                                """)
                                .bind("cid", creditId)
                                .bind("jid", journalId)
                                .then())
        ).then().block();

        return journalId;
    }

    private String extractExceptionMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            cause = cause.getCause();
        }
        return "";
    }
}
