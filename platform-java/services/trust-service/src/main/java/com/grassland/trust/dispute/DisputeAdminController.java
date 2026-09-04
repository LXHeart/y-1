package com.grassland.trust.dispute;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.trust.adjudication.CaseEvidenceRedactor;
import com.grassland.trust.security.TrustCallerResolver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 客服活跃争议队列。权益取开案快照，排序与游标共同使用 priority/createdAt/id。 */
@RestController
public class DisputeAdminController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final TrustCallerResolver callers;
    private final DisputeCaseRepository disputes;
    private final CaseEvidenceRedactor redactor;

    public DisputeAdminController(TrustCallerResolver callers, DisputeCaseRepository disputes,
                                  CaseEvidenceRedactor redactor) {
        this.callers = callers;
        this.disputes = disputes;
        this.redactor = redactor;
    }

    @GetMapping("/api/admin/trust/disputes")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.PLATFORM_ADMIN)
                .then(Mono.fromCallable(() -> query(limit, cursor)))
                .flatMap(query -> disputes.listForSupport(query.limit() + 1, query.afterPriority(),
                                query.afterCsRank(), query.afterCsDueAt(), query.afterCreatedAt(), query.afterId())
                        .collectList().map(rows -> page(rows, query.limit())))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    /** 游标 v2（任务书 #74 卡 A）：加入 cs_rank/cs_due_key 两列——cs_direct（即将/已超 SLA）排前。 */
    private static Query query(int limit, String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 须为 1-100");
        }
        if (cursor == null || cursor.isBlank()) {
            return new Query(limit, null, null, null, null, null);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 5);
            if (parts.length != 5) {
                throw new IllegalArgumentException("cursor 格式错误");
            }
            int priority = Integer.parseInt(parts[0]);
            if (priority < 0 || priority > 100) {
                throw new IllegalArgumentException("cursor 格式错误");
            }
            int csRank = Integer.parseInt(parts[1]);
            Instant csDueAt = Instant.parse(parts[2]);
            Instant createdAt = Instant.parse(parts[3]);
            String id = UUID.fromString(parts[4]).toString();
            return new Query(limit, priority, csRank, csDueAt, createdAt, id);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("cursor 格式错误");
        }
    }

    private Map<String, Object> page(List<DisputeCase> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<DisputeCase> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items.stream().map(this::toBody).toList());
        body.put("hasMore", hasMore);
        body.put("nextCursor", hasMore && !items.isEmpty() ? encode(items.getLast()) : null);
        return body;
    }

    /** cs_due_key 与仓储排序口径一致：NULL（非 cs_direct）用远期哨兵，排序/比较全程非空。 */
    private static final Instant CS_DUE_SENTINEL = Instant.parse("9999-12-31T00:00:00Z");

    private static String encode(DisputeCase dispute) {
        Instant csDueKey = dispute.csDueAt() == null ? CS_DUE_SENTINEL : dispute.csDueAt();
        String raw = dispute.supportPriority() + "|" + (dispute.effectiveChannel().equals("cs_direct") ? 0 : 1)
                + "|" + csDueKey + "|" + dispute.createdAt() + "|" + dispute.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> toBody(DisputeCase dispute) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", dispute.id());
        body.put("engagementRef", dispute.engagementRef());
        body.put("organizationId", dispute.organizationId());
        body.put("openedByAlias", redactor.pseudonym(dispute.id(), dispute.openedByAccountId()));
        body.put("openedByRole", dispute.openedByRole());
        body.put("status", dispute.status());
        body.put("kind", dispute.kind());
        body.put("reason", redactor.maskText(dispute.reason()));
        body.put("appealState", dispute.appealState());
        body.put("premiumSupport", dispute.premiumSupport());
        body.put("supportPriority", dispute.supportPriority());
        body.put("supportBadge", dispute.premiumSupport() ? "premium" : "standard");
        body.put("createdAt", dispute.createdAt());
        body.put("updatedAt", dispute.updatedAt());
        return body;
    }

    private record Query(int limit, Integer afterPriority, Integer afterCsRank, Instant afterCsDueAt,
                         Instant afterCreatedAt, String afterId) {}
}
