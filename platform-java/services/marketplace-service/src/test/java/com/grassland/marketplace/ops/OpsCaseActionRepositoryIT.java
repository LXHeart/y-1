package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code ops_case_action} 幂等台账（GL-P1-OPS-001 Stage 2）：operationId 唯一索引是唯一真相。 */
class OpsCaseActionRepositoryIT extends MarketplaceItSupport {

    private static final String OPS_A = "11111111-1111-4111-8111-111111111111";

    @Autowired
    private OpsCaseRepository cases;

    @Autowired
    private OpsCaseActionRepository actions;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ops_case_action").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_case_audit").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_case").fetch().rowsUpdated().block();
    }

    private String givenCase() {
        return cases.insertIfAbsent(OpsCaseSource.SETTLEMENT_HELD, UUID.randomUUID().toString(),
                "org-1", "app-1", "open_dispute").block().id();
    }

    @Test
    @DisplayName("claim 落 pending 行；同一 operationId 再 claim → empty（不重复执行下游）")
    void claimIsIdempotent() {
        String caseId = givenCase();
        OpsCaseAction first = actions.claim(caseId, "op-1", OpsCaseAction.RELEASE_FUNDS, OPS_A).block();
        assertThat(first).isNotNull();
        assertThat(first.status()).isEqualTo("pending");
        assertThat(first.isPending()).isTrue();
        assertThat(first.requestedBy()).isEqualTo(OPS_A);
        assertThat(first.completedAt()).isNull();

        assertThat(actions.claim(caseId, "op-1", OpsCaseAction.RELEASE_FUNDS, OPS_A).block()).isNull();
        assertThat(actions.findByOperationId("op-1").block().id()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("幂等键是全局的：跨 case 复用同一 operationId 也冲突（供服务层识别键复用）")
    void operationIdIsGlobal() {
        actions.claim(givenCase(), "op-shared", OpsCaseAction.RELEASE_FUNDS, OPS_A).block();
        assertThat(actions.claim(givenCase(), "op-shared", OpsCaseAction.RELEASE_FUNDS, OPS_A).block())
                .isNull();
    }

    @Test
    @DisplayName("complete 只吃 pending：重复完成 → empty，failed 不会被改写成 succeeded")
    void completeOnlyOnce() {
        String caseId = givenCase();
        actions.claim(caseId, "op-2", OpsCaseAction.RETRY_RECONCILIATION, OPS_A).block();

        OpsCaseAction failed = actions.complete("op-2", false, null, "对账仍未通过").block();
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.error()).isEqualTo("对账仍未通过");
        assertThat(failed.outcome()).isNull();
        assertThat(failed.completedAt()).isNotNull();

        assertThat(actions.complete("op-2", true, "released", null).block()).isNull();
        assertThat(actions.findByOperationId("op-2").block().status()).isEqualTo("failed");
    }

    @Test
    @DisplayName("成功回填 outcome，按 case 列出按时间升序")
    void listByCase() {
        String caseId = givenCase();
        actions.claim(caseId, "op-3", OpsCaseAction.RELEASE_FUNDS, OPS_A).block();
        actions.complete("op-3", true, "released", null).block();
        actions.claim(caseId, "op-4", OpsCaseAction.RETRY_RECONCILIATION, OPS_A).block();

        assertThat(actions.listByCase(caseId).collectList().block())
                .extracting(OpsCaseAction::operationId, OpsCaseAction::status, OpsCaseAction::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("op-3", "succeeded", "released"),
                        org.assertj.core.groups.Tuple.tuple("op-4", "pending", null));
    }
}
