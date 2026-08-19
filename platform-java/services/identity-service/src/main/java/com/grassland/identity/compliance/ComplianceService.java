package com.grassland.identity.compliance;

import static com.grassland.identity.compliance.ComplianceModels.AuditEntry;
import static com.grassland.identity.compliance.ComplianceModels.Blocker;
import static com.grassland.identity.compliance.ComplianceModels.ClosureCheck;
import static com.grassland.identity.compliance.ComplianceModels.ClosureRequest;
import static com.grassland.identity.compliance.ComplianceModels.DomainCheck;
import static com.grassland.identity.compliance.ComplianceModels.ExportRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.identityprofile.IdentitySessionRepository;
import com.grassland.identity.mobile.RefreshTokenRepository;
import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.session.SessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ComplianceService {

    private final ComplianceRepository repository;
    private final ComplianceDomainClient domains;
    private final ComplianceProperties properties;
    private final PersonalDataArchiveBuilder archives;
    private final SessionRepository sessions;
    private final IdentitySessionRepository identitySessions;
    private final RefreshTokenRepository refreshTokens;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final CookieSigner downloadSigner;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ComplianceService(
            ComplianceRepository repository,
            ComplianceDomainClient domains,
            ComplianceProperties properties,
            PersonalDataArchiveBuilder archives,
            SessionRepository sessions,
            IdentitySessionRepository identitySessions,
            RefreshTokenRepository refreshTokens,
            OutboxRepository outbox,
            TransactionalOperator transactions,
            CookieSigner downloadSigner) {
        this.repository = repository;
        this.domains = domains;
        this.properties = properties;
        this.archives = archives;
        this.sessions = sessions;
        this.identitySessions = identitySessions;
        this.refreshTokens = refreshTokens;
        this.outbox = outbox;
        this.transactions = transactions;
        this.downloadSigner = downloadSigner;
    }

    public Mono<ClosureCheck> checkClosure(String accountId) {
        return Mono.zip(repository.ownedOrganizationCount(accountId), repository.activeExportCount(accountId),
                        domains.marketplaceCheck(accountId))
                .flatMap(initial -> Mono.zip(
                        Mono.just(identityCheck(initial.getT1(), initial.getT2())),
                        Mono.just(initial.getT3()),
                        domains.financeCheck(accountId),
                        domains.trustCheck(accountId, initial.getT3().engagementRefs()),
                        domains.intelligenceCheck(accountId)))
                .map(tuple -> combine(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), tuple.getT5()));
    }

    public Mono<ExportRequest> requestExport(String accountId) {
        return repository.createExport(accountId)
                .flatMap(request -> repository.appendAudit(accountId, "export_requested", request.id(),
                                "account", "{\"format\":\"zip\"}")
                        .thenReturn(request))
                .onErrorMap(DuplicateKeyException.class,
                        error -> new IdentityException(409, "已有尚未过期的数据导出"));
    }

    public Mono<ExportRequest> findExport(String id, String accountId) {
        return repository.findExport(id, accountId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "导出任务不存在")));
    }

    public Mono<ClosureOutcome> requestClosure(String accountId) {
        return repository.findActiveClosure(accountId)
                .filter(request -> "retention".equals(request.status())
                        || "erasing".equals(request.status()) || "failed".equals(request.status()))
                .flatMap(existing -> Mono.just(new ClosureOutcome(existing,
                        new ClosureCheck(List.of(), Map.of()), true)))
                .switchIfEmpty(checkClosure(accountId).flatMap(check -> {
                    String blockers = json(check.blockers());
                    if (!check.eligible()) {
                        return repository.createBlockedClosure(accountId, blockers)
                                .flatMap(request -> repository.appendAudit(accountId, "closure_blocked", request.id(),
                                                "account", json(Map.of("blockers", check.blockers())))
                                        .thenReturn(new ClosureOutcome(request, check, false)));
                    }
                    Instant retentionUntil = Instant.now().plus(properties.piiRetention());
                    return transactions.transactional(
                            repository.createRetentionClosure(accountId, blockers, retentionUntil)
                            .flatMap(request ->
                                    repository.softDeleteAccount(accountId)
                                            .then(repository.suspendAccountProcessing(accountId))
                                            .then(sessions.deleteAllForAccount(accountId))
                                            .then(identitySessions.deleteByAccount(accountId))
                                            .then(refreshTokens.revokeAllForAccount(accountId))
                                            .then(repository.appendAudit(accountId, "closure_requested", request.id(),
                                                    "account", json(Map.of(
                                                            "retentionUntil", retentionUntil.toString(),
                                                            "retentionDays", properties.piiRetentionDays()))))
                                            .then(outbox.append(closureEvent(request, retentionUntil)))
                                            .thenReturn(new ClosureOutcome(request, check, false))));
                }));
    }

    public Mono<ClosureRequest> findClosure(String accountId) {
        return repository.findActiveClosure(accountId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "暂无账号注销请求")));
    }

    public Flux<AuditEntry> audit(String accountId, int limit) {
        return repository.findAudit(accountId, limit);
    }

    Mono<Void> generateExport(ExportRequest request) {
        return Mono.zip(repository.exportIdentityJson(request.accountId()),
                        domains.financeExport(request.accountId()))
                .map(tuple -> archives.build(tuple.getT1(), tuple.getT2()))
                .flatMap(artifact -> {
                    Instant expiresAt = Instant.now().plus(properties.exportTtl());
                    return repository.completeExport(
                                    request.id(), request.claimToken(), artifact, sha256(artifact), expiresAt)
                            .flatMap(updated -> updated == 1
                                    ? repository.appendAudit(request.accountId(), "export_completed", request.id(),
                                            "system", json(Map.of(
                                                    "expiresAt", expiresAt.toString(),
                                                    "sizeBytes", artifact.length)))
                                    : Mono.empty());
                })
                .onErrorResume(error -> repository.failExport(
                                request.id(), request.claimToken(), errorCode(error),
                                properties.retryBackoff(request.attemptCount()))
                        .then());
    }

    Mono<Void> eraseAccount(ClosureRequest request) {
        return domains.eraseMarketplace(request.accountId())
                .then(domains.eraseFinance(request.accountId()))
                .then(domains.eraseTrust(request.accountId()))
                .then(domains.eraseIntelligence(request.accountId()))
                .then(repository.purgeLocalPii(request.accountId()))
                .then(repository.appendAudit(request.accountId(), "pii_erased", request.id(),
                        "system", json(Map.of("retained", List.of(
                                "financial_facts", "dispute_facts", "immutable_audit")))))
                .then(repository.completeClosure(request.id(), request.claimToken()))
                .then()
                .onErrorResume(error -> repository.failClosure(
                                request.id(), request.claimToken(), errorCode(error),
                                properties.retryBackoff(request.attemptCount()))
                        .then());
    }

    public String downloadToken(ExportRequest request) {
        if (!downloadSigner.isConfigured() || request.expiresAt() == null) {
            throw new IdentityException(503, "导出下载签名尚未配置");
        }
        return downloadSigner.sign(tokenPayload(request));
    }

    public void verifyDownloadToken(ExportRequest request, String token) {
        if (request.expiresAt() == null || request.expiresAt().isBefore(Instant.now())) {
            throw new IdentityException(410, "导出文件已过期");
        }
        String payload = downloadSigner.unsign(token).orElseThrow(
                () -> new IdentityException(403, "下载链接无效"));
        if (!MessageDigest.isEqual(payload.getBytes(StandardCharsets.UTF_8),
                tokenPayload(request).getBytes(StandardCharsets.UTF_8))) {
            throw new IdentityException(403, "下载链接无效");
        }
    }

    private static ClosureCheck combine(DomainCheck... checks) {
        List<Blocker> blockers = new ArrayList<>();
        Map<String, Boolean> available = new LinkedHashMap<>();
        for (DomainCheck check : checks) {
            blockers.addAll(check.blockers());
        }
        for (String domain : List.of("identity", "marketplace", "finance", "trust", "intelligence")) {
            available.put(domain, blockers.stream().noneMatch(blocker -> domain.equals(blocker.domain())
                    && "DEPENDENCY_UNAVAILABLE".equals(blocker.code())));
        }
        return new ClosureCheck(List.copyOf(blockers), Map.copyOf(available));
    }

    private static DomainCheck identityCheck(long ownedOrganizations, long activeExports) {
        List<Blocker> blockers = new ArrayList<>();
        if (ownedOrganizations > 0) {
            blockers.add(new Blocker(
                    "identity", "ORGANIZATION_OWNERSHIP", "请先转让或关闭名下组织",
                    ownedOrganizations, null));
        }
        if (activeExports > 0) {
            blockers.add(new Blocker(
                    "identity", "ACTIVE_DATA_EXPORT", "请先下载或等待当前数据导出过期",
                    activeExports, null));
        }
        return new DomainCheck(blockers, List.of());
    }

    private EventEnvelope closureEvent(ClosureRequest request, Instant retentionUntil) {
        return new EventEnvelope(UUID.randomUUID().toString(), "AccountClosureRequested", "Account",
                request.accountId(), 1, Instant.now(), request.id(), Map.of(
                        "accountId", request.accountId(),
                        "closureRequestId", request.id(),
                        "retentionUntil", retentionUntil.toString()));
    }

    private String tokenPayload(ExportRequest request) {
        return "personal-export:" + request.id() + ':' + request.accountId() + ':' + request.expiresAt().getEpochSecond();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize compliance record", error);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String errorCode(Throwable error) {
        String code = reactor.core.Exceptions.unwrap(error).getClass().getSimpleName();
        return code.length() > 64 ? code.substring(0, 64) : code;
    }

    public record ClosureOutcome(ClosureRequest request, ClosureCheck check, boolean existing) {}
}
