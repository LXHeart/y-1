package com.grassland.marketplace.reputation;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 聚合事实、当前策略和 Lv5 邀请，供三个 HTTP 边界共享同一判定。 */
@Service
public class ReputationService {

    private final ReputationRepository reputations;
    private final ReputationPolicyRepository policies;
    private final Lv5AdmissionRepository admissions;

    public ReputationService(ReputationRepository reputations, ReputationPolicyRepository policies,
                             Lv5AdmissionRepository admissions) {
        this.reputations = reputations;
        this.policies = policies;
        this.admissions = admissions;
    }

    public Mono<ReputationSnapshot> snapshot(String accountId) {
        Mono<Lv5Admission> admission = admissions.find(accountId)
                .defaultIfEmpty(Lv5Admission.none(accountId));
        return Mono.zip(reputations.statsOf(accountId), policies.findCurrent(), admission)
                .map(tuple -> {
                    ReputationStats stats = tuple.getT1();
                    ReputationPolicy policy = tuple.getT2();
                    Lv5Admission currentAdmission = tuple.getT3();
                    ReputationEvaluation evaluation = policy.evaluate(stats, currentAdmission.admitted());
                    return new ReputationSnapshot(accountId, stats, policy, currentAdmission, evaluation);
                });
    }

    /** 多账号共享同一个策略快照，事实和 Lv5 准入也各只发一次批量查询。 */
    public Mono<Map<String, ReputationSnapshot>> snapshots(Collection<String> accountIds) {
        return snapshots(accountIds, Instant.now());
    }

    /** Batch evaluation at one caller-supplied instant, so ranking and inactivity rules share a clock edge. */
    public Mono<Map<String, ReputationSnapshot>> snapshots(Collection<String> accountIds, Instant evaluatedAt) {
        List<String> requested = accountIds.stream().distinct().toList();
        if (requested.isEmpty()) {
            return Mono.just(Map.of());
        }
        return Mono.zip(reputations.statsOfAccounts(requested), policies.findCurrent(), admissions.findAll(requested))
                .map(tuple -> {
                    Map<String, ReputationStats> statsByAccount = tuple.getT1();
                    ReputationPolicy policy = tuple.getT2();
                    Map<String, Lv5Admission> admissionsByAccount = tuple.getT3();
                    Map<String, ReputationSnapshot> snapshots = new LinkedHashMap<>();
                    for (String accountId : requested) {
                        ReputationStats stats = statsByAccount.getOrDefault(accountId, ReputationStats.empty());
                        Lv5Admission admission = admissionsByAccount.getOrDefault(
                                accountId, Lv5Admission.none(accountId));
                        ReputationEvaluation evaluation = policy.evaluate(stats, admission.admitted(), evaluatedAt);
                        snapshots.put(accountId,
                                new ReputationSnapshot(accountId, stats, policy, admission, evaluation));
                    }
                    return Map.copyOf(snapshots);
                });
    }
}
