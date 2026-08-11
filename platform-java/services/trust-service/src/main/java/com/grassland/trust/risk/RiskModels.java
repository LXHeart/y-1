package com.grassland.trust.risk;

import java.time.Instant;
import java.util.Map;

public final class RiskModels {
    private RiskModels() {}

    public record RegisterSignalRequest(
            String sourceKind, String sourceRef, String subjectKind, String subjectRef,
            String organizationId, String ruleCode, String ruleVersion, Integer score,
            Integer occurrenceCount, Instant occurredAt, Map<String, Object> evidence) {}

    public record Signal(
            String id, String sourceKind, String sourceRef, String subjectKind, String subjectRef,
            String organizationId, String ruleCode, String ruleVersion, int score, String severity,
            String status, String evidenceJson, Instant occurredAt, Instant createdAt, Instant updatedAt) {}

    public record RiskCase(
            String id, String subjectKind, String subjectRef, String organizationId, String status,
            String severity, int score, String reason, String resolutionNote, String assignedTo,
            Instant createdAt, Instant updatedAt, Instant resolvedAt) {}

    public record CaseAudit(
            long id, String caseId, String action, String actorAccountId, String actorRole,
            String note, Instant createdAt) {}

    public record Evaluation(int score, String severity, String reason, boolean opensCase) {}
    public record Registration(Signal signal, RiskCase riskCase, boolean created) {}
    public record CaseActionRequest(String action, String note) {}
}
