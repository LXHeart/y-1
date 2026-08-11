package com.grassland.trust.risk;

import com.grassland.trust.risk.RiskModels.Evaluation;
import com.grassland.trust.risk.RiskModels.RegisterSignalRequest;
import org.springframework.stereotype.Component;

/** Deterministic cold-start rules. Scores are deliberately configurable through the signal contract. */
@Component
public class RiskRuleEngine {

    public Evaluation evaluate(RegisterSignalRequest request) {
        requireText(request.sourceKind(), "sourceKind");
        requireText(request.sourceRef(), "sourceRef");
        requireText(request.subjectKind(), "subjectKind");
        requireText(request.subjectRef(), "subjectRef");
        requireText(request.ruleCode(), "ruleCode");
        if (request.sourceKind().length() > 48 || request.sourceRef().length() > 160
                || request.subjectKind().length() > 32 || request.subjectRef().length() > 160
                || request.ruleCode().length() > 64 || version(request.ruleVersion()).length() > 32) {
            throw new IllegalArgumentException("风控信号字段超过长度限制");
        }
        if (request.organizationId() != null && !request.organizationId().isBlank()) {
            try { java.util.UUID.fromString(request.organizationId()); }
            catch (RuntimeException error) { throw new IllegalArgumentException("organizationId 格式错误"); }
        }
        if (!java.util.Set.of("account", "organization", "task", "order", "engagement")
                .contains(request.subjectKind())) {
            throw new IllegalArgumentException("subjectKind 不受支持");
        }
        int score = request.score() == null ? scoreKnownRule(request.ruleCode(), request.occurrenceCount()) : request.score();
        if (score < 0 || score > 100) throw new IllegalArgumentException("score 须为 0-100");
        String severity = score >= 90 ? "critical" : score >= 70 ? "high" : score >= 40 ? "medium" : "low";
        String reason = request.ruleCode() + "@" + version(request.ruleVersion()) + " score=" + score;
        return new Evaluation(score, severity, reason, score >= 70);
    }

    private static int scoreKnownRule(String ruleCode, Integer occurrences) {
        int count = occurrences == null ? 1 : Math.max(1, occurrences);
        return switch (ruleCode) {
            case "repeated_refund_attempts" -> Math.min(100, 25 + count * 20);
            case "repeated_rejected_submissions" -> Math.min(100, 20 + count * 18);
            case "dispute_velocity" -> Math.min(100, 30 + count * 20);
            case "abnormal_application_velocity" -> Math.min(100, 20 + count * 15);
            default -> 50;
        };
    }

    static String version(String version) {
        return version == null || version.isBlank() ? "v1" : version.trim();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }
}
