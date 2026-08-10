package com.grassland.identity.identityprofile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 本人身份审计查询。原始 session token 不出接口，仅返回不可逆短指纹。 */
@RestController
public class IdentityAuditController {

    private static final int MAX_LIMIT = 100;

    private final CurrentAccountResolver accounts;
    private final IdentityAuditLogRepository audit;

    public IdentityAuditController(CurrentAccountResolver accounts, IdentityAuditLogRepository audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    @GetMapping("/api/me/identity-audit")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit,
            ServerHttpRequest request) {
        if (limit < 1 || limit > MAX_LIMIT) {
            return Mono.error(new IdentityException(400, "limit 必须在 1 到 " + MAX_LIMIT + " 之间"));
        }

        String normalizedAction = normalizeAction(action);
        IdentityAuditCursor decodedCursor = decodeCursor(cursor);
        return accounts.resolvePrincipal(request)
                .flatMap(principal -> audit.findPage(principal.user().id(), normalizedAction,
                                decodedCursor, limit + 1)
                        .collectList()
                        .map(rows -> response(rows, limit, principal.sid())))
                .map(ResponseEntity::ok);
    }

    private Map<String, Object> response(List<IdentityAuditLog> rows, int limit, String currentSid) {
        boolean hasMore = rows.size() > limit;
        List<IdentityAuditLog> page = hasMore ? rows.subList(0, limit) : rows;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", page.stream().map(row -> toBody(row, currentSid)).toList());
        data.put("nextCursor", hasMore && !page.isEmpty()
                ? new IdentityAuditCursor(page.getLast().occurredAt(), page.getLast().id()).encode()
                : null);
        return Map.of("success", true, "data", data);
    }

    private Map<String, Object> toBody(IdentityAuditLog row, String currentSid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.id());
        body.put("action", row.action());
        body.put("fromIdentityType", row.fromIdentityType());
        body.put("toIdentityType", row.toIdentityType());
        body.put("sessionFingerprint", fingerprint(row.sessionToken()));
        body.put("currentSession", row.sessionToken() != null && row.sessionToken().equals(currentSid));
        body.put("deviceId", row.deviceId());
        body.put("ipAddress", row.ipAddress());
        body.put("userAgent", row.userAgent());
        body.put("occurredAt", row.occurredAt().toString());
        return body;
    }

    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        try {
            return IdentityAuditAction.fromDb(action).dbValue();
        } catch (IllegalArgumentException error) {
            throw new IdentityException(400, "未知审计动作");
        }
    }

    private IdentityAuditCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return IdentityAuditCursor.decode(cursor);
        } catch (IllegalArgumentException error) {
            throw new IdentityException(400, "无效 cursor");
        }
    }

    private String fingerprint(String sessionToken) {
        if (sessionToken == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
