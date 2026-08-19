package com.grassland.identity.compliance;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class ComplianceModels {
    private ComplianceModels() {}

    record ExportRequest(
            String id,
            String accountId,
            String status,
            String format,
            byte[] artifact,
            String sha256,
            Long sizeBytes,
            Instant expiresAt,
            int attemptCount,
            String claimToken,
            Instant createdAt,
            Instant completedAt,
            String errorCode) {}

    record ClosureRequest(
            String id,
            String accountId,
            String status,
            String blockersJson,
            Instant retentionUntil,
            int attemptCount,
            String claimToken,
            Instant requestedAt,
            Instant completedAt,
            String errorCode) {}

    record Blocker(String domain, String code, String message, long count, Long amountCents) {
        static Blocker unavailable(String domain) {
            return new Blocker(domain, "DEPENDENCY_UNAVAILABLE", domain + " 服务暂不可用", 1, null);
        }
    }

    record DomainCheck(List<Blocker> blockers, List<String> engagementRefs) {
        DomainCheck {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            engagementRefs = engagementRefs == null ? List.of() : List.copyOf(engagementRefs);
        }

        static DomainCheck empty() {
            return new DomainCheck(List.of(), List.of());
        }
    }

    record ClosureCheck(List<Blocker> blockers, Map<String, Boolean> domains) {
        boolean eligible() {
            return blockers.isEmpty();
        }
    }

    record AuditEntry(String id, String action, String requestId, String actorType,
                      String detailJson, Instant occurredAt) {}
}
