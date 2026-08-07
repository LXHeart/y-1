package com.grassland.marketplace.reputation;

import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 管理端写模型：乐观锁、领域校验和审计必须同事务。 */
@Service
public class ReputationAdministrationService {

    private static final int MAX_NOTE_LENGTH = 500;

    private final ReputationRepository reputations;
    private final ReputationPolicyRepository policies;
    private final Lv5AdmissionRepository admissions;
    private final ReputationAdminAuditRepository audits;
    private final TransactionalOperator transactions;

    public ReputationAdministrationService(
            ReputationRepository reputations,
            ReputationPolicyRepository policies,
            Lv5AdmissionRepository admissions,
            ReputationAdminAuditRepository audits,
            TransactionalOperator transactions) {
        this.reputations = reputations;
        this.policies = policies;
        this.admissions = admissions;
        this.audits = audits;
        this.transactions = transactions;
    }

    public Mono<ReputationPolicy> updatePolicy(UpdateReputationPolicyRequest request, Caller actor) {
        List<ReputationLevelRule> rules = ReputationPolicyValidator.requireValid(request);
        Mono<ReputationPolicy> work = policies.findCurrentForUpdate()
                .filter(current -> current.version() == request.expectedVersion())
                .switchIfEmpty(Mono.error(new MarketplaceException(409, "等级策略版本已变化，请刷新后重试")))
                .flatMap(before -> policies.advanceVersion(request.expectedVersion(), actor.accountId())
                        .switchIfEmpty(Mono.error(new MarketplaceException(
                                409, "等级策略版本已变化，请刷新后重试")))
                        .flatMap(newVersion -> policies.updateAll(rules)
                                .then(policies.findCurrent())
                                .flatMap(after -> audits.append(
                                                "policy_updated", null, actor.accountId(), actor.role(),
                                                newVersion, null, "更新 Lv1-Lv5 等级阈值与权益",
                                                ReputationResponseMapper.policy(before),
                                                ReputationResponseMapper.policy(after))
                                        .thenReturn(after))));
        return transactions.transactional(work);
    }

    public Mono<Lv5Admission> updateAdmission(String accountId, UpdateLv5AdmissionRequest request,
                                               Caller actor) {
        ValidAdmission input = validateAdmission(request);
        Mono<Void> grantGate = input.admitted()
                ? requireLv5Metrics(accountId)
                : Mono.empty();
        Mono<Lv5Admission> work = grantGate.then(admissions.findForUpdate(accountId)
                        .defaultIfEmpty(Lv5Admission.none(accountId)))
                .filter(current -> current.version() == input.expectedVersion())
                .switchIfEmpty(Mono.error(new MarketplaceException(409, "Lv5 邀请版本已变化，请刷新后重试")))
                .flatMap(before -> admissions.update(accountId, input.admitted(), input.expectedVersion(),
                                actor.accountId(), input.note())
                        .switchIfEmpty(Mono.error(new MarketplaceException(
                                409, "Lv5 邀请版本已变化，请刷新后重试")))
                        .flatMap(after -> audits.append(
                                        input.admitted() ? "lv5_granted" : "lv5_revoked",
                                        accountId, actor.accountId(), actor.role(), null,
                                        after.version(), input.note(),
                                        ReputationResponseMapper.admission(before),
                                        ReputationResponseMapper.admission(after))
                                .thenReturn(after)));
        return transactions.transactional(work);
    }

    private Mono<Void> requireLv5Metrics(String accountId) {
        return policies.findCurrentForUpdate()
                .flatMap(policy -> reputations.statsOf(accountId)
                        .filter(stats -> policy.evaluate(stats, true).calculatedLevel()
                                == RecommenderLevel.LV5))
                .switchIfEmpty(Mono.error(new MarketplaceException(409, "账号尚未达到 Lv5 必要指标")))
                .then();
    }

    private static ValidAdmission validateAdmission(UpdateLv5AdmissionRequest request) {
        if (request == null || request.admitted() == null) {
            throw new IllegalArgumentException("缺少 admitted");
        }
        if (request.expectedVersion() == null) {
            throw new IllegalArgumentException("缺少 expectedVersion");
        }
        if (request.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion 不能为负数");
        }
        String note = request.note() == null ? "" : request.note().trim();
        if (note.isEmpty() || note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note 长度须为 1-500 字符");
        }
        return new ValidAdmission(request.admitted(), request.expectedVersion(), note);
    }

    private record ValidAdmission(boolean admitted, long expectedVersion, String note) {}
}
