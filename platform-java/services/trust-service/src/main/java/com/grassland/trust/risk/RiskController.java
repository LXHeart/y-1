package com.grassland.trust.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.trust.admin.PageEnvelope;
import com.grassland.trust.risk.RiskModels.CaseActionRequest;
import com.grassland.trust.risk.RiskModels.RegisterSignalRequest;
import com.grassland.trust.risk.RiskModels.RiskCase;
import com.grassland.trust.risk.RiskModels.Signal;
import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class RiskController {
    private final TrustCallerResolver callers;
    private final RiskRepository repository;
    private final RiskService service;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public RiskController(TrustCallerResolver callers, RiskRepository repository, RiskService service) {
        this.callers = callers;
        this.repository = repository;
        this.service = service;
    }

    @PostMapping("/api/trust/risk/signals")
    public Mono<ResponseEntity<Map<String, Object>>> register(
            @RequestBody RegisterSignalRequest body, ServerHttpRequest request) {
        return callers.resolve(request).flatMap(caller -> {
            if (!caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE)
                    && !caller.hasBackendRole(BackendRole.RISK)) {
                return Mono.error(new TrustException(403, "需要风控角色或受信 marketplace 服务"));
            }
            return service.register(body, caller);
        }).map(result -> ResponseEntity.status(result.created() ? 201 : 200).body(success(Map.of(
                "created", result.created(), "signal", signalBody(result.signal()),
                "case", result.riskCase() == null ? Map.of() : caseBody(result.riskCase())))));
    }

    @GetMapping("/api/trust/risk/signals")
    public Mono<ResponseEntity<Map<String, Object>>> signals(
            @RequestParam(required = false) String status, @RequestParam(required = false) String subjectKind,
            @RequestParam(required = false) String subjectRef, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset, ServerHttpRequest request) {
        int pageSize = PageEnvelope.limit(limit);
        int pageOffset = PageEnvelope.offset(offset);
        return callers.requireRole(request, BackendRole.RISK)
                .then(Mono.zip(
                        repository.listSignals(status, subjectKind, subjectRef, pageSize, pageOffset)
                                .map(this::signalBody).collectList(),
                        repository.countListSignals(status, subjectKind, subjectRef))
                        .map(tuple -> ResponseEntity.ok(success(PageEnvelope
                                .data(tuple.getT1(), tuple.getT2(), pageSize, pageOffset)))));
    }

    @GetMapping("/api/trust/risk/cases")
    public Mono<ResponseEntity<Map<String, Object>>> cases(
            @RequestParam(required = false) String status, @RequestParam(required = false) String severity,
            @RequestParam(required = false) String subjectKind, @RequestParam(required = false) String subjectRef,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset,
            ServerHttpRequest request) {
        int pageSize = PageEnvelope.limit(limit);
        int pageOffset = PageEnvelope.offset(offset);
        return callers.requireRole(request, BackendRole.RISK)
                .then(Mono.zip(
                        repository.listCases(status, severity, subjectKind, subjectRef, pageSize, pageOffset)
                                .map(RiskController::caseBody).collectList(),
                        repository.countListCases(status, severity, subjectKind, subjectRef))
                        .map(tuple -> ResponseEntity.ok(success(PageEnvelope
                                .data(tuple.getT1(), tuple.getT2(), pageSize, pageOffset)))));
    }

    @GetMapping("/api/trust/risk/cases/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> detail(@PathVariable String id, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.RISK).then(repository.findCase(id)
                .switchIfEmpty(Mono.error(new TrustException(404, "风控案件不存在"))))
                .flatMap(riskCase -> Mono.zip(repository.listCaseSignals(id).map(this::signalBody).collectList(),
                                repository.listAudits(id).collectList())
                        .map(tuple -> ResponseEntity.ok(success(Map.of("case", caseBody(riskCase),
                                "signals", tuple.getT1(), "audits", tuple.getT2())))));
    }

    @PostMapping("/api/trust/risk/cases/{id}/actions")
    public Mono<ResponseEntity<Map<String, Object>>> act(
            @PathVariable String id, @RequestBody CaseActionRequest body, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.RISK)
                .flatMap(caller -> service.act(id, body, caller))
                .map(updated -> ResponseEntity.ok(success(caseBody(updated))));
    }

    private Map<String, Object> signalBody(Signal signal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", signal.id()); body.put("sourceKind", signal.sourceKind()); body.put("sourceRef", signal.sourceRef());
        body.put("subjectKind", signal.subjectKind()); body.put("subjectRef", signal.subjectRef());
        body.put("organizationId", signal.organizationId()); body.put("ruleCode", signal.ruleCode());
        body.put("ruleVersion", signal.ruleVersion()); body.put("score", signal.score());
        body.put("severity", signal.severity()); body.put("status", signal.status());
        try { body.put("evidence", mapper.readTree(signal.evidenceJson())); }
        catch (Exception ignored) { body.put("evidence", Map.of()); }
        body.put("occurredAt", signal.occurredAt()); body.put("createdAt", signal.createdAt());
        return body;
    }

    private static Map<String, Object> caseBody(RiskCase value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", value.id()); body.put("subjectKind", value.subjectKind()); body.put("subjectRef", value.subjectRef());
        body.put("organizationId", value.organizationId()); body.put("status", value.status());
        body.put("severity", value.severity()); body.put("score", value.score()); body.put("reason", value.reason());
        body.put("resolutionNote", value.resolutionNote()); body.put("assignedTo", value.assignedTo());
        body.put("createdAt", value.createdAt()); body.put("updatedAt", value.updatedAt()); body.put("resolvedAt", value.resolvedAt());
        return body;
    }

    private static Map<String, Object> success(Object data) { return Map.of("success", true, "data", data); }
}
