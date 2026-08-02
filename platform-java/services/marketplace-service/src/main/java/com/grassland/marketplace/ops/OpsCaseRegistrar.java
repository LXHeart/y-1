package com.grassland.marketplace.ops;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 处置单登记（GL-P1-OPS-001 Stage 1）：{@code register} + 首次登记时的 {@code registered} 审计，成对完成。
 *
 * <p><b>调用方必须把本方法放进领域写的同一事务</b>（{@code transactions.transactional(领域写.then(registrar.register(...)))}），
 * 保证「阻断落库 ⇔ 处置单存在」原子。分两次提交时，崩溃落在中间会留下一笔无人知晓的阻断 ——
 * 而阻断恰恰是「没人来看就永远卡着」的那类状态，比丢事件更难发现。
 *
 * <p>幂等由 {@code UNIQUE(source_kind, source_ref)} 保证：重投/重跑返回既有单，且<b>不</b>重复写审计
 * （审计若随重试增长，时间线就不再等于真实处置历史）。
 */
@Component
public class OpsCaseRegistrar {

    private final OpsCaseRepository cases;
    private final OpsCaseAuditRepository audits;

    public OpsCaseRegistrar(OpsCaseRepository cases, OpsCaseAuditRepository audits) {
        this.cases = cases;
        this.audits = audits;
    }

    /**
     * 登记（幂等）。首次 → 追加 {@code registered} 审计（actor 为 NULL + role={@code system}）；
     * 已存在 → 回读原样返回，不动状态也不加审计。
     *
     * <p>「是否首次」取自 {@code insertIfAbsent} 是否返回行（冲突 → empty），而非按状态猜 ——
     * 未被处置的既有单同样是 {@code version=1} + {@code open}，按状态猜会重复写审计。
     */
    public Mono<OpsCase> register(String sourceKind, String sourceRef, String organizationId,
                                  String applicationId, String reason) {
        return cases.insertIfAbsent(sourceKind, sourceRef, organizationId, applicationId, reason)
                .flatMap(created -> audits
                        .append(created.id(), "registered", null, "system", null, created.status(), reason)
                        .thenReturn(created))
                .switchIfEmpty(Mono.defer(() -> cases.findBySource(sourceKind, sourceRef)));
    }
}
