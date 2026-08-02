package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.settlement.SettlementReconciliationRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.FinanceReconciliationClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 受限处置动作执行（GL-P1-OPS-001 Stage 2）。
 *
 * <p><b>「受限」是本类的全部要点</b>：动作集是封闭的四个常量，且每个动作都<b>只复用 finance 既有原语</b> ——
 * {@code retry_reconciliation} 打 finance {@code reconcile}，{@code release_funds} 打 finance
 * {@code release}。不新增资金原语，不新增判定口径，运营不能指定金额、收款方或结算结论。
 *
 * <p><b>为什么补偿走 reconcile 而不是直接调 reverse</b>：finance 的 {@code /reverse} 端点
 * 是 <b>trust-only</b>（{@code resolveMerchantOrService(request, TRUST_SERVICE)}），marketplace 打不通。
 * 而 {@code /reconcile} 是 marketplace-gated，且 finance 内部对 {@code captured} 态本就会走
 * {@code reverse}（{@code reconcileForMerchant}）。所以「重试对账」这一个动作既覆盖了 released 也覆盖了
 * refunded，且<b>没有放宽任何服务的资金授权</b>。刻意不放宽：那是资金原语的授权边界，不该为运营台让步。
 *
 * <p><b>刻意不提供的动作</b>：capture（放款）。运营点一下就把钱付出去、绕过结算判定，
 * 是本条 backlog 最该避免的东西。窗口/验收/争议都通过才该放款，那是结算 saga 的职责。
 *
 * <p>幂等：{@code operationId} 唯一索引。重复提交回放既有台账行，<b>不再调下游</b>。
 * 台账先落 {@code pending} 再调下游，成功/失败回填。
 *
 * <p>前置：case 必须已 {@code approved}（双人审批过）。未审批的 case 执行动作 → 409。
 */
@Service
public class OpsCaseActionService {

    private final OpsCaseRepository cases;
    private final OpsCaseActionRepository actions;
    private final OpsCaseAuditRepository audits;
    private final SettlementReconciliationRepository reconciliations;
    private final FinanceReconciliationClient reconciliationClient;
    private final FinanceEscrowClient escrowClient;

    public OpsCaseActionService(OpsCaseRepository cases, OpsCaseActionRepository actions,
                                OpsCaseAuditRepository audits,
                                SettlementReconciliationRepository reconciliations,
                                FinanceReconciliationClient reconciliationClient,
                                FinanceEscrowClient escrowClient) {
        this.cases = cases;
        this.actions = actions;
        this.audits = audits;
        this.reconciliations = reconciliations;
        this.reconciliationClient = reconciliationClient;
        this.escrowClient = escrowClient;
    }

    /**
     * 执行一个受限动作。
     *
     * @param operationId 幂等键，由调用方提供；重复即回放
     */
    public Mono<OpsCaseAction> execute(String caseId, String action, String operationId,
                                       String actorAccountId, String actorRole) {
        String kind = requireSupported(action);
        return cases.findById(caseId)
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "处置单不存在")))
                .flatMap(opsCase -> {
                    if (!"approved".equals(opsCase.status())) {
                        return Mono.error(new MarketplaceException(409, "处置动作须先经双人审批通过"));
                    }
                    // 动作与来源不匹配是请求错误，必须在抢 operationId 之前拒 —— 否则一次笔误就烧掉
                    // 一个幂等键，运营改对动作后还得换键重试。
                    MarketplaceException mismatch = incompatible(opsCase, kind);
                    if (mismatch != null) {
                        return Mono.error(mismatch);
                    }
                    // claim 非空 = 本次抢到了 operationId，才执行下游；empty = 用过了，纯回放。
                    return actions.claim(caseId, operationId, kind, actorAccountId)
                            .flatMap(claimed -> runAndRecord(opsCase, claimed, actorAccountId, actorRole))
                            .switchIfEmpty(Mono.defer(() -> actions.findByOperationId(operationId)
                                    .flatMap(existing -> guardReplay(existing, caseId, kind))));
                });
    }

    /**
     * 回放守卫：同一 operationId 必须指向同一 case 与同一动作，否则调用方在复用幂等键，
     * 静默返回别人的结果比报错危险得多。
     */
    private Mono<OpsCaseAction> guardReplay(OpsCaseAction existing, String caseId, String kind) {
        if (!existing.caseId().equals(caseId) || !existing.action().equals(kind)) {
            return Mono.error(new MarketplaceException(409, "operationId 已用于其他处置动作"));
        }
        return Mono.just(existing);
    }

    private Mono<OpsCaseAction> runAndRecord(OpsCase opsCase, OpsCaseAction claimed,
                                             String actorAccountId, String actorRole) {
        return dispatch(opsCase, claimed.action())
                .flatMap(outcome -> actions.complete(claimed.operationId(), true, outcome, null)
                        .flatMap(done -> audits.append(opsCase.id(), "action_executed", actorAccountId,
                                        actorRole, opsCase.status(), opsCase.status(),
                                        claimed.action() + ": " + outcome)
                                .thenReturn(done)))
                // 下游失败也要落痕：失败的补偿尝试同样是运营需要看到的历史。
                .onErrorResume(error -> actions
                        .complete(claimed.operationId(), false, null, describe(error))
                        .flatMap(done -> audits.append(opsCase.id(), "action_failed", actorAccountId,
                                        actorRole, opsCase.status(), opsCase.status(),
                                        claimed.action() + ": " + describe(error))
                                .thenReturn(done)));
    }

    private Mono<String> dispatch(OpsCase opsCase, String action) {
        return switch (action) {
            case OpsCaseAction.RETRY_RECONCILIATION -> retryReconciliation(opsCase);
            case OpsCaseAction.RELEASE_FUNDS -> releaseFunds(opsCase);
            default -> Mono.error(new MarketplaceException(400, "该动作不由本端点执行"));
        };
    }

    /**
     * 重试对账：从 {@code settlement_reconciliation} 取回原始 {@code finalDecision} 再打 finance。
     *
     * <p>判决<b>不接受入参</b> —— 运营重试的是同一个终局判决的执行，不是重新判一次。
     * 允许传判决等于让运营台变成第二个争议裁决入口。
     */
    private Mono<String> retryReconciliation(OpsCase opsCase) {
        return reconciliations.findBySourceEventId(opsCase.sourceRef())
                .switchIfEmpty(Mono.error(new MarketplaceException(409, "对账记录已不存在，无法重试")))
                .flatMap(row -> {
                    if (row.applicationId() == null || row.organizationId() == null
                            || row.finalDecision() == null) {
                        return Mono.error(new MarketplaceException(409, "对账记录缺少重试所需字段"));
                    }
                    return reconciliationClient
                            .reconcile(row.organizationId(), row.applicationId(), row.finalDecision())
                            .flatMap(result -> result.isSuccess()
                                    ? Mono.just(result.outcome() + "/" + result.reason())
                                    : Mono.error(new MarketplaceException(409,
                                            "对账仍未通过：" + result.outcome() + "/" + result.reason())));
                });
    }

    /**
     * 释放预留：仅用于结算暂缓。钱退回商家可用余额，等于放弃本次结算。
     *
     * <p>不用于对账阻断 —— 那条链的钱侧结论由 trust 终局判决决定，运营在此处 release 会覆盖判决。
     */
    private Mono<String> releaseFunds(OpsCase opsCase) {
        if (opsCase.organizationId() == null || opsCase.applicationId() == null) {
            return Mono.error(new MarketplaceException(409, "处置单缺少组织或报名信息"));
        }
        return escrowClient.release(opsCase.organizationId(), opsCase.applicationId())
                .thenReturn("released");
    }

    /**
     * 动作与来源的相容性。返回 null 表示相容。
     *
     * <p>{@code release_funds} 不用于对账阻断：那条链的钱侧结论由 trust 终局判决决定，
     * 运营在此 release 会覆盖判决。{@code retry_reconciliation} 不用于结算暂缓：暂缓单没有对账行。
     */
    private static MarketplaceException incompatible(OpsCase opsCase, String action) {
        return switch (action) {
            case OpsCaseAction.RETRY_RECONCILIATION ->
                    OpsCaseSource.SETTLEMENT_BLOCKED.equals(opsCase.sourceKind())
                            ? null : new MarketplaceException(400, "重试对账仅适用于对账阻断处置单");
            case OpsCaseAction.RELEASE_FUNDS ->
                    OpsCaseSource.SETTLEMENT_HELD.equals(opsCase.sourceKind())
                            ? null : new MarketplaceException(400, "释放预留仅适用于结算暂缓处置单");
            default -> new MarketplaceException(400, "该动作不由本端点执行");
        };
    }

    private static String requireSupported(String action) {
        if (OpsCaseAction.RETRY_RECONCILIATION.equals(action) || OpsCaseAction.RELEASE_FUNDS.equals(action)) {
            return action;
        }
        throw new MarketplaceException(400, "未知处置动作");
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
