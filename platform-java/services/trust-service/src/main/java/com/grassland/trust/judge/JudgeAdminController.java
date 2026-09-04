package com.grassland.trust.judge;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import java.util.LinkedHashMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 平台管理员查看审判官候选人并授予/撤销运营准入。 */
@RestController
public class JudgeAdminController {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 100;
    private static final int MAX_REASON_LENGTH = 500;

    private final TrustCallerResolver callers;
    private final JudgeRepository judges;
    private final JudgeAdmissionAuditRepository audits;
    private final MarketplaceReputationClient reputationClient;
    private final TransactionalOperator transactions;

    public JudgeAdminController(TrustCallerResolver callers, JudgeRepository judges,
                                JudgeAdmissionAuditRepository audits,
                                MarketplaceReputationClient reputationClient,
                                TransactionalOperator transactions) {
        this.callers = callers;
        this.judges = judges;
        this.audits = audits;
        this.reputationClient = reputationClient;
        this.transactions = transactions;
    }

    @GetMapping("/api/admin/trust/judges")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(defaultValue = "" + DEFAULT_LIST_LIMIT) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String accountId,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .then(Mono.fromCallable(() -> listQuery(limit, cursor, accountId)))
                .flatMap(query -> judges.listForAdmin(query.limit() + 1,
                                query.beforeCreatedAt(), query.beforeId(), query.accountId())
                        .collectList().map(rows -> pageBody(rows, query.limit())))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    @GetMapping("/api/admin/trust/judges/{accountId}")
    public Mono<ResponseEntity<Map<String, Object>>> detail(@PathVariable String accountId,
                                                            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(ignored -> {
                    String validAccountId = requireUuid(accountId);
                    return judges.findByAccountId(validAccountId)
                            .switchIfEmpty(fail(404, "审判官不存在"))
                            .flatMap(judge -> audits.listByJudge(judge.id()).map(this::auditBody).collectList()
                                    .map(audit -> {
                                        Map<String, Object> body = judgeBody(judge);
                                        body.put("audit", audit);
                                        return ResponseEntity.ok(Map.of("success", true, "data", body));
                                    }));
                });
    }

    @PutMapping("/api/admin/trust/judges/{accountId}/admission")
    public Mono<ResponseEntity<Map<String, Object>>> updateAdmission(
            @PathVariable String accountId,
            @RequestBody UpdateJudgeAdmissionRequest body,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> {
                    String validAccountId = requireUuid(accountId);
                    String adminAccountId = requireUuid(admin.accountId());
                    ValidAdmission command = validate(body);
                    return judges.findByAccountId(validAccountId)
                            .switchIfEmpty(fail(404, "审判官不存在"))
                            .flatMap(current -> update(current, command, adminAccountId))
                            .map(updated -> ResponseEntity.ok(Map.of(
                                    "success", true, "data", judgeBody(updated))));
                });
    }

    private Mono<Judge> update(Judge current, ValidAdmission command, String adminAccountId) {
        if (current.version() != command.expectedVersion()) {
            return fail(409, "版本冲突，请刷新后重试");
        }
        if (current.opsAdmitted() == command.admitted()) {
            return Mono.just(current);
        }
        Mono<Void> eligibility = command.admitted()
                ? reputationClient.getLevel(current.accountId())
                        .onErrorMap(error -> new TrustException(503, "声誉服务暂时不可用"))
                        // 任务书 #74 卡 E（D4）：Lv5 直入；Lv4 须已过准入考试（见习通道）。
                        .filter(level -> level.levelNumber() >= 5
                                || (level.levelNumber() >= 4 && current.examPassedAt() != null))
                        .switchIfEmpty(fail(409, "该推荐官不满足审判资格（Lv5，或 Lv4 且已过准入考试）"))
                        .then()
                : Mono.empty();
        return eligibility.then(changeAdmission(current, command, adminAccountId));
    }

    private Mono<Judge> changeAdmission(Judge current, ValidAdmission command, String adminAccountId) {
        Mono<Judge> change = judges.updateAdmission(
                        current.accountId(), command.admitted(), command.expectedVersion(), adminAccountId)
                .switchIfEmpty(fail(409, "版本冲突，请刷新后重试"))
                .flatMap(updated -> audits.append(
                                updated.id(), command.admitted(), adminAccountId,
                                command.reason(), command.expectedVersion())
                        .thenReturn(updated));
        return transactions.transactional(change);
    }

    private static ValidAdmission validate(UpdateJudgeAdmissionRequest body) {
        if (body == null || body.admitted() == null || body.expectedVersion() == null) {
            throw new IllegalArgumentException("admitted 和 expectedVersion 必填");
        }
        if (body.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion 不能为负数");
        }
        String reason = body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty() || body.reason().length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason 长度须为 1-500 字符");
        }
        return new ValidAdmission(body.admitted(), body.expectedVersion(), reason);
    }

    private static String requireUuid(String value) {
        try {
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("accountId 格式错误");
            }
            return normalized;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("accountId 格式错误");
        }
    }

    private Map<String, Object> judgeBody(Judge judge) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", judge.id());
        body.put("accountId", judge.accountId());
        body.put("organizationId", judge.organizationId());
        body.put("eligibilityTier", judge.eligibilityTier());
        body.put("active", judge.active());
        body.put("opsAdmitted", judge.opsAdmitted());
        body.put("version", judge.version());
        body.put("opsAdmittedAt", judge.opsAdmittedAt() == null ? null : judge.opsAdmittedAt().toString());
        body.put("opsAdmittedBy", judge.opsAdmittedBy());
        // 任务书 #74 卡 E：考试/见习/挂起状态（治理台「审判官管理」扩列）。
        body.put("admissionLevel", judge.admissionLevel() == null ? "full" : judge.admissionLevel());
        body.put("examPassedAt", judge.examPassedAt() == null ? null : judge.examPassedAt().toString());
        body.put("probationSince", judge.probationSince() == null ? null : judge.probationSince().toString());
        body.put("suspendedNow", judge.suspendedNow());
        body.put("suspendedUntil", judge.suspendedUntil() == null ? null : judge.suspendedUntil().toString());
        body.put("suspensionReason", judge.suspensionReason());
        body.put("createdAt", judge.createdAt() == null ? null : judge.createdAt().toString());
        return body;
    }

    private Map<String, Object> auditBody(JudgeAdmissionAudit audit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", audit.id());
        body.put("action", audit.action());
        body.put("actorAccountId", audit.actorAccountId());
        body.put("reason", audit.reason());
        body.put("previousVersion", audit.previousVersion());
        body.put("newVersion", audit.newVersion());
        body.put("createdAt", audit.createdAt() == null ? null : audit.createdAt().toString());
        return body;
    }

    private Map<String, Object> pageBody(List<Judge> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<Judge> page = hasMore ? rows.subList(0, limit) : rows;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.stream().map(this::judgeBody).toList());
        body.put("hasMore", hasMore);
        body.put("nextCursor", hasMore && !page.isEmpty() ? encodeCursor(page.getLast()) : null);
        return body;
    }

    private static ListQuery listQuery(int limit, String cursor, String accountId) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException("limit 须为 1-100");
        }
        String search = accountId == null || accountId.isBlank() ? null : requireUuid(accountId.trim());
        if (search != null) {
            if (cursor != null && !cursor.isBlank()) {
                throw new IllegalArgumentException("账号搜索不能与 cursor 同时使用");
            }
            return new ListQuery(limit, null, null, search);
        }
        if (cursor == null || cursor.isBlank()) {
            return new ListQuery(limit, null, null, null);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("cursor 格式错误");
            }
            Instant createdAt = Instant.ofEpochMilli(Long.parseLong(parts[0]));
            String id = requireUuid(parts[1]);
            return new ListQuery(limit, createdAt, id, null);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("cursor 格式错误");
        }
    }

    private static String encodeCursor(Judge judge) {
        String raw = judge.createdAt().toEpochMilli() + ":" + judge.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }

    private record ValidAdmission(boolean admitted, long expectedVersion, String reason) {}

    private record ListQuery(int limit, Instant beforeCreatedAt, String beforeId, String accountId) {}
}
