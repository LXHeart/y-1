package com.grassland.identity.compliance;

import static com.grassland.identity.compliance.ComplianceModels.AuditEntry;
import static com.grassland.identity.compliance.ComplianceModels.Blocker;
import static com.grassland.identity.compliance.ComplianceModels.ClosureCheck;
import static com.grassland.identity.compliance.ComplianceModels.ClosureRequest;
import static com.grassland.identity.compliance.ComplianceModels.ExportRequest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ComplianceController {

    private final CurrentAccountResolver accounts;
    private final ComplianceService service;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ComplianceController(CurrentAccountResolver accounts, ComplianceService service) {
        this.accounts = accounts;
        this.service = service;
    }

    @GetMapping("/api/me/compliance/closure-check")
    public Mono<ResponseEntity<Map<String, Object>>> closureCheck(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> service.checkClosure(account.id()))
                .map(check -> ResponseEntity.ok(success(checkBody(check))));
    }

    @PostMapping("/api/me/compliance/exports")
    public Mono<ResponseEntity<Map<String, Object>>> requestExport(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> service.requestExport(account.id()))
                .map(export -> ResponseEntity.accepted().body(success(exportBody(export))));
    }

    @GetMapping("/api/me/compliance/exports/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> exportStatus(
            @PathVariable String id, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> service.findExport(id, account.id()))
                .map(export -> ResponseEntity.ok(success(exportBody(export))));
    }

    @GetMapping("/api/me/compliance/exports/{id}/download")
    public Mono<ResponseEntity<byte[]>> download(
            @PathVariable String id, @RequestParam String token, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> service.findExport(id, account.id()))
                .map(export -> {
                    if (!"completed".equals(export.status()) || export.artifact() == null) {
                        throw new IdentityException(409, "导出文件尚未生成");
                    }
                    service.verifyDownloadToken(export, token);
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("application/zip"))
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=grassland-personal-data-" + export.id() + ".zip")
                            .header("X-Content-Type-Options", "nosniff")
                            .body(export.artifact());
                });
    }

    @PostMapping("/api/me/compliance/account-closure")
    public Mono<ResponseEntity<Map<String, Object>>> requestClosure(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> service.requestClosure(account.id()))
                .map(outcome -> {
                    Map<String, Object> data = closureBody(outcome.request());
                    data.put("check", checkBody(outcome.check()));
                    data.put("existing", outcome.existing());
                    return outcome.check().eligible()
                            ? ResponseEntity.accepted().body(success(data))
                            : ResponseEntity.status(409).body(Map.of(
                                    "success", false,
                                    "error", "账号尚不满足注销条件",
                                    "data", data));
                });
    }

    @GetMapping("/api/me/compliance/account-closure")
    public Mono<ResponseEntity<Map<String, Object>>> closureStatus(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> service.findClosure(account.id()))
                .map(closure -> ResponseEntity.ok(success(closureBody(closure))));
    }

    @GetMapping("/api/me/compliance/audit")
    public Mono<ResponseEntity<Map<String, Object>>> audit(
            @RequestParam(defaultValue = "50") int limit, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> service.audit(account.id(), limit).map(this::auditBody).collectList())
                .map(entries -> ResponseEntity.ok(success(Map.of("entries", entries))));
    }

    private Map<String, Object> exportBody(ExportRequest export) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", export.id());
        body.put("status", export.status());
        body.put("format", export.format());
        body.put("createdAt", format(export.createdAt()));
        body.put("completedAt", format(export.completedAt()));
        body.put("expiresAt", format(export.expiresAt()));
        body.put("sizeBytes", export.sizeBytes());
        body.put("sha256", export.sha256());
        if ("completed".equals(export.status()) && export.expiresAt() != null
                && export.expiresAt().isAfter(java.time.Instant.now())) {
            String token = URLEncoder.encode(service.downloadToken(export), StandardCharsets.UTF_8);
            body.put("downloadUrl", "/api/me/compliance/exports/" + export.id() + "/download?token=" + token);
        }
        if ("failed".equals(export.status())) {
            body.put("errorCode", export.errorCode());
        }
        return body;
    }

    private Map<String, Object> closureBody(ClosureRequest closure) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", closure.id());
        body.put("status", closure.status());
        body.put("blockers", readJsonList(closure.blockersJson()));
        body.put("retentionUntil", format(closure.retentionUntil()));
        body.put("requestedAt", format(closure.requestedAt()));
        body.put("completedAt", format(closure.completedAt()));
        body.put("errorCode", closure.errorCode());
        return body;
    }

    private Map<String, Object> auditBody(AuditEntry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", entry.id());
        body.put("action", entry.action());
        body.put("requestId", entry.requestId());
        body.put("actorType", entry.actorType());
        body.put("detail", readJsonMap(entry.detailJson()));
        body.put("occurredAt", format(entry.occurredAt()));
        return body;
    }

    private static Map<String, Object> checkBody(ClosureCheck check) {
        return Map.of(
                "eligible", check.eligible(),
                "blockers", check.blockers().stream().map(ComplianceController::blockerBody).toList(),
                "domains", check.domains());
    }

    private static Map<String, Object> blockerBody(Blocker blocker) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domain", blocker.domain());
        body.put("code", blocker.code());
        body.put("message", blocker.message());
        body.put("count", blocker.count());
        body.put("amountCents", blocker.amountCents());
        return body;
    }

    private List<Map<String, Object>> readJsonList(String json) {
        try {
            return mapper.readValue(json == null ? "[]" : json, new TypeReference<>() {});
        } catch (Exception error) {
            return List.of();
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            return mapper.readValue(json == null ? "{}" : json, new TypeReference<>() {});
        } catch (Exception error) {
            return Map.of();
        }
    }

    private static String format(java.time.Instant value) {
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value);
    }

    private static Map<String, Object> success(Map<String, Object> data) {
        return Map.of("success", true, "data", data);
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleIdentityError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", "请求参数无效"));
    }
}
