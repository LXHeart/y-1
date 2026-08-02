package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code ops_case} 仓储与 DB 约束的集成测试（GL-P1-OPS-001 Stage 1）。
 *
 * <p>这里验证的是控制器测试<b>看不到</b>的那一层：登记幂等靠唯一键而非应用层查重、状态机与乐观锁靠
 * {@code WHERE} 而非读改写、四眼原则即使绕过端点也被 CHECK 约束挡住。
 */
class OpsCaseRepositoryIT extends MarketplaceItSupport {

    private static final String OPS_A = "11111111-1111-4111-8111-111111111111";
    private static final String OPS_B = "22222222-2222-4222-8222-222222222222";

    @Autowired
    private OpsCaseRepository cases;

    @Autowired
    private OpsCaseAuditRepository audits;

    @Autowired
    private OpsCaseRegistrar registrar;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ops_case_audit").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_case").fetch().rowsUpdated().block();
    }

    private OpsCase insert(String reason) {
        return cases.insertIfAbsent(OpsCaseSource.SETTLEMENT_HELD, UUID.randomUUID().toString(),
                "org-1", "app-1", reason).block();
    }

    @Test
    @DisplayName("首次登记落库并带上 source 推导的 severity；结算阻断算高危")
    void insertDerivesSeverity() {
        OpsCase held = insert("open_dispute");
        assertThat(held).isNotNull();
        assertThat(held.status()).isEqualTo("open");
        assertThat(held.version()).isEqualTo(1L);
        assertThat(held.severity()).isEqualTo("normal");
        assertThat(held.isTerminal()).isFalse();

        OpsCase blocked = cases.insertIfAbsent(OpsCaseSource.SETTLEMENT_BLOCKED,
                UUID.randomUUID().toString(), "org-1", "app-1", "trust_mismatch").block();
        assertThat(blocked.severity()).isEqualTo("high");
    }

    @Test
    @DisplayName("同一 source 重复登记 → insertIfAbsent 返回 empty（靠唯一键判定，不是先查后插）")
    void insertIsIdempotent() {
        String ref = UUID.randomUUID().toString();
        OpsCase first = cases.insertIfAbsent(OpsCaseSource.SETTLEMENT_HELD, ref, "org-1", "app-1", "open_dispute")
                .block();
        assertThat(first).isNotNull();

        OpsCase replay = cases.insertIfAbsent(OpsCaseSource.SETTLEMENT_HELD, ref, "org-1", "app-1", "open_dispute")
                .block();
        assertThat(replay).isNull();

        assertThat(cases.findBySource(OpsCaseSource.SETTLEMENT_HELD, ref).block().id()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("重放登记不追加第二条 registered 审计，也不覆盖已推进的状态")
    void registrarReplayIsClean() {
        String ref = UUID.randomUUID().toString();
        OpsCase first = registrar.register(OpsCaseSource.SETTLEMENT_HELD, ref, "org-1", "app-1", "open_dispute")
                .block();
        cases.submit(first.id(), 1L, OPS_A, "提审").block();

        OpsCase replay = registrar.register(OpsCaseSource.SETTLEMENT_HELD, ref, "org-1", "app-1", "open_dispute")
                .block();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("in_review");  // 重放不把状态打回 open

        // 只有一条 registered：重放没有再写一条。这里直接调仓储 submit，不经端点，故无 submitted 审计
        // （审计由控制器在同事务内追加，见 OpsCaseControllerIT#fullLifecycle 断言 4 条）。
        assertThat(audits.listByCase(first.id()).collectList().block())
                .extracting(OpsCaseAudit::action)
                .containsExactly("registered");
    }

    @Test
    @DisplayName("状态机：submit 只吃 open，decide 只吃 in_review，resolve 只吃 approved")
    void stateMachineGuards() {
        OpsCase c = insert("open_dispute");

        assertThat(cases.decide(c.id(), 1L, OPS_B, true, null).block()).isNull();   // open 不能直接审批
        assertThat(cases.resolve(c.id(), 1L, "compensated").block()).isNull();      // open 不能直接收单

        OpsCase submitted = cases.submit(c.id(), 1L, OPS_A, "提审").block();
        assertThat(submitted.status()).isEqualTo("in_review");
        assertThat(submitted.version()).isEqualTo(2L);
        assertThat(submitted.submittedBy()).isEqualTo(OPS_A);
        assertThat(submitted.submittedAt()).isNotNull();

        assertThat(cases.submit(c.id(), 2L, OPS_A, null).block()).isNull();         // 不能重复提审

        OpsCase approved = cases.decide(c.id(), 2L, OPS_B, true, "同意").block();
        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.approvedBy()).isEqualTo(OPS_B);
        assertThat(approved.approvedAt()).isNotNull();

        OpsCase resolved = cases.resolve(c.id(), 3L, "compensated").block();
        assertThat(resolved.status()).isEqualTo("resolved");
        assertThat(resolved.resolution()).isEqualTo("compensated");
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("乐观锁：expectedVersion 不符一律 empty，不做读改写")
    void optimisticLock() {
        OpsCase c = insert("open_dispute");

        assertThat(cases.submit(c.id(), 99L, OPS_A, null).block()).isNull();
        assertThat(cases.findById(c.id()).block().version()).isEqualTo(1L);

        cases.submit(c.id(), 1L, OPS_A, null).block();
        assertThat(cases.decide(c.id(), 1L, OPS_B, true, null).block()).isNull();   // 拿旧版本审批
        assertThat(cases.findById(c.id()).block().status()).isEqualTo("in_review");
    }

    @Test
    @DisplayName("四眼原则：仓储层 decide 排除提审人 → empty")
    void selfApprovalRejectedByRepository() {
        OpsCase c = insert("open_dispute");
        cases.submit(c.id(), 1L, OPS_A, null).block();

        assertThat(cases.decide(c.id(), 2L, OPS_A, true, null).block()).isNull();
        assertThat(cases.decide(c.id(), 2L, OPS_B, true, null).block()).isNotNull();
    }

    @Test
    @DisplayName("四眼原则第二道：绕过仓储直接 UPDATE 也会被 ck_ops_case_two_person 挡住")
    void selfApprovalRejectedByConstraint() {
        OpsCase c = insert("open_dispute");
        cases.submit(c.id(), 1L, OPS_A, null).block();

        assertThatThrownBy(() -> db.sql("""
                        UPDATE ops_case
                           SET status = 'approved', approved_by = CAST(:by AS uuid), approved_at = now()
                         WHERE id = CAST(:id AS uuid)
                        """)
                .bind("by", OPS_A)
                .bind("id", c.id())
                .fetch().rowsUpdated().block())
                .hasMessageContaining("ck_ops_case_two_person");
    }

    @Test
    @DisplayName("默认队列只列未终态并按 created_at 升序（最久未处置的排最前）")
    void listDefaultsToOpenQueue() {
        OpsCase first = insert("open_dispute");
        OpsCase second = insert("verification_failed");
        cases.submit(second.id(), 1L, OPS_A, null).block();
        cases.decide(second.id(), 2L, OPS_B, false, null).block();  // rejected → 终态

        assertThat(cases.list(null, 50).collectList().block())
                .extracting(OpsCase::id)
                .containsExactly(first.id());

        assertThat(cases.list("rejected", 50).collectList().block())
                .extracting(OpsCase::id)
                .containsExactly(second.id());
    }

    @Test
    @DisplayName("审计流水只增不改，按 id 升序返回")
    void auditIsAppendOnly() {
        OpsCase c = insert("open_dispute");
        audits.append(c.id(), "registered", null, "system", null, "open", "open_dispute").block();
        audits.append(c.id(), "submitted", OPS_A, "customer_service", "open", "in_review", "提审").block();

        assertThat(audits.listByCase(c.id()).collectList().block())
                .extracting(OpsCaseAudit::action, OpsCaseAudit::actorAccountId, OpsCaseAudit::actorRole)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("registered", null, "system"),
                        org.assertj.core.groups.Tuple.tuple("submitted", OPS_A, "customer_service"));
    }
}
