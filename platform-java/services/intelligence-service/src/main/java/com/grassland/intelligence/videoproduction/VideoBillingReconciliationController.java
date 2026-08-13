package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Admin-only, read-only evidence for provider/video/AI-run/credit reconciliation. */
@RestController
public class VideoBillingReconciliationController {
    private final IntelligenceCallerResolver callers;
    private final VideoBillingReconciliationRepository repository;
    private final FinanceCreditOperationClient finance;
    private final VideoGenerationProperties properties;

    public VideoBillingReconciliationController(
            IntelligenceCallerResolver callers,
            VideoBillingReconciliationRepository repository,
            FinanceCreditOperationClient finance,
            VideoGenerationProperties properties) {
        this.callers = callers;
        this.repository = repository;
        this.finance = finance;
        this.properties = properties;
    }

    @GetMapping("/api/admin/ai/video-reconciliation")
    public Mono<Report> report(
            @RequestParam(defaultValue = "100") int limit,
            ServerWebExchange exchange) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(ignored -> repository.latest(boundedLimit)
                        .collectList()
                        .flatMap(rows -> finance.query(rows.stream()
                                        .map(VideoBillingReconciliationRepository.RowData::operationId)
                                        .filter(Objects::nonNull).distinct().toList())
                                .map(operations -> report(rows.stream()
                                        .map(row -> reconcile(row, operations.get(row.operationId()), false,
                                                policyState()))
                                        .toList(), policyState()))
                                .onErrorResume(error -> Mono.just(report(rows.stream()
                                        .map(row -> reconcile(row, null, true, policyState())).toList(),
                                        policyState())))));
    }

    private String policyState() {
        return properties.financeCreditsCentsPolicyState();
    }

    private static Item reconcile(VideoBillingReconciliationRepository.RowData row,
                                  FinanceCreditOperationClient.Operation operation,
                                  boolean financeUnavailable, String policyState) {
        List<String> issues = new ArrayList<>();
        String state;
        if (!isTerminal(row.jobStatus())) {
            state = "pending";
        } else if ("succeeded".equals(row.jobStatus())) {
            checkSucceeded(row, issues);
            state = issues.isEmpty() ? "consistent" : "inconsistent";
        } else if (row.runId() == null) {
            state = "consistent";
        } else if (!"failed".equals(row.runStatus())) {
            issues.add("run_not_failed");
            state = "inconsistent";
        } else if ("completed".equals(row.compensationStatus())) {
            state = "consistent";
        } else if ("pending".equals(row.compensationStatus())) {
            state = "pending";
            issues.add("credit_compensation_pending");
        } else {
            state = "inconsistent";
            issues.add(row.compensationStatus() == null
                    ? "credit_compensation_missing" : "credit_compensation_failed");
        }
        String financeState = financeState(row, operation, financeUnavailable, issues);
        if ("inconsistent".equals(financeState)) state = "inconsistent";
        else if ("unavailable".equals(financeState) && "consistent".equals(state)) state = "pending";
        return new Item(
                row.jobId(), row.runId(), row.operationId(), row.provider(), row.model(),
                row.pricingVersion(), row.jobStatus(), row.runStatus(), row.compensationStatus(),
                row.requestedDurationSeconds(), row.actualDurationSeconds(),
                row.unitPriceCents(), row.estimatedCostCents(), row.actualCostCents(),
                row.runActualCents(), row.runVideoSeconds(), financeState,
                operation == null ? null : operation.source(),
                operation == null ? null : operation.policyVersion(),
                policyState, state, List.copyOf(issues));
    }

    private static String financeState(
            VideoBillingReconciliationRepository.RowData row,
            FinanceCreditOperationClient.Operation operation,
            boolean unavailable, List<String> issues) {
        if (row.operationId() == null) return "not_applicable";
        if (unavailable) {
            issues.add("finance_authority_unavailable");
            return "unavailable";
        }
        if (operation == null) {
            issues.add("finance_credit_operation_missing");
            return "inconsistent";
        }
        if (!row.accountId().equals(operation.accountId())) issues.add("finance_credit_account_mismatch");
        if (!"video_production_video".equals(operation.feature())) issues.add("finance_credit_feature_mismatch");
        String expected = "succeeded".equals(row.jobStatus()) ? "consumed"
                : "completed".equals(row.compensationStatus()) ? "compensated" : null;
        if (expected != null && !expected.equals(operation.state())) {
            issues.add("finance_credit_state_mismatch");
        }
        return issues.stream().anyMatch(issue -> issue.startsWith("finance_credit_"))
                ? "inconsistent" : operation.state();
    }

    private static void checkSucceeded(
            VideoBillingReconciliationRepository.RowData row, List<String> issues) {
        if (!"completed".equals(row.runStatus())) issues.add("run_not_completed");
        if (row.actualDurationSeconds() == null) issues.add("actual_duration_missing");
        if (row.actualCostCents() == null) issues.add("job_actual_cost_missing");
        if (row.resultReference() == null || !row.resultReference().startsWith("/api/media/")) {
            issues.add("archived_media_reference_missing");
        }
        if (!Objects.equals(row.actualCostCents(), row.runActualCents())) {
            issues.add("job_run_cost_mismatch");
        }
        if (!Objects.equals(row.actualDurationSeconds(), row.runVideoSeconds())) {
            issues.add("job_run_duration_mismatch");
        }
        if (row.actualDurationSeconds() != null && row.actualCostCents() != null) {
            try {
                if (Math.multiplyExact(row.actualDurationSeconds(), row.unitPriceCents())
                        != row.actualCostCents()) {
                    issues.add("frozen_price_cost_mismatch");
                }
            } catch (ArithmeticException error) {
                issues.add("frozen_price_cost_overflow");
            }
        }
    }

    private static boolean isTerminal(String status) {
        return "succeeded".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private static Report report(List<Item> items, String policyState) {
        Map<String, Long> summary = Map.of(
                "total", (long) items.size(),
                "consistent", count(items, "consistent"),
                "pending", count(items, "pending"),
                "inconsistent", count(items, "inconsistent"));
        return new Report(summary, policyState, items);
    }

    private static long count(List<Item> items, String state) {
        return items.stream().filter(item -> state.equals(item.reconciliationState())).count();
    }

    public record Report(
            Map<String, Long> summary, String monetaryConversionState, List<Item> items) {}

    public record Item(
            UUID jobId, UUID runId, UUID creditOperationId,
            String provider, String model, String pricingVersion,
            String jobStatus, String runStatus, String compensationStatus,
            int requestedDurationSeconds, Integer actualDurationSeconds,
            int unitPriceCents, int estimatedCostCents, Integer jobActualCostCents,
            Integer runActualCents, Integer runVideoSeconds,
            String financeAuthorityState, String creditSource, Long creditPolicyVersion,
            String monetaryConversionState,
            String reconciliationState, List<String> issues) {}
}
