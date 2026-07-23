package com.grassland.identity.identityprofile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 身份档案 + 活动身份 HTTP 入口。草场身份域 Slice 2G（HLD 5.2 identity-profile / 1.3 事实 2）。
 *
 * <ul>
 *   <li>GET /api/me/identities — 列已开通身份。</li>
 *   <li>POST /api/me/identities — 开通身份（merchant+orgId→校验 org owner；已开通 409；写 outbox {@code IdentityOpened}）。</li>
 *   <li>GET /api/me/active-identity — 当前活动身份（null=消费者）。</li>
 *   <li>POST /api/me/active-identity — 激活（须已开通，否则 409；写 outbox {@code ActiveIdentityChanged}）。</li>
 *   <li>DELETE /api/me/active-identity — 切回消费者（active 置 NULL）。</li>
 * </ul>
 *
 * <p>所有端点经 {@link CurrentAccountResolver} 鉴权。活动身份账号级存储（HLD D-08 per-session/多设备规则延期）。
 */
@RestController
public class IdentityProfileController {

    private final CurrentAccountResolver accounts;
    private final IdentityProfileRepository profiles;
    private final ActiveIdentityRepository activeIdentities;
    private final OrgAuthorization authz;
    private final OutboxRepository outbox;

    public IdentityProfileController(CurrentAccountResolver accounts, IdentityProfileRepository profiles,
                                     ActiveIdentityRepository activeIdentities, OrgAuthorization authz,
                                     OutboxRepository outbox) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.activeIdentities = activeIdentities;
        this.authz = authz;
        this.outbox = outbox;
    }

    @GetMapping("/api/me/identities")
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> profiles.findByAccount(account.id()).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::profileBody).toList()))));
    }

    @PostMapping(value = "/api/me/identities", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> open(@RequestBody OpenIdentityRequest body, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> {
                    IdentityType type = IdentityType.fromDb(body.type());
                    String rawOrgId = body.organizationId();
                    String orgId = (rawOrgId == null || rawOrgId.isBlank()) ? null : rawOrgId;
                    // 商家身份 + 提供了 org → 校验为该 org owner；否则放行（orgId 为 null 时透传，不能用 Mono.just(null)）
                    Mono<Void> ownershipGate = (type == IdentityType.MERCHANT && orgId != null)
                            ? authz.requireRole(request, orgId, MembershipRole.OWNER).then()
                            : Mono.empty();
                    return ownershipGate
                            .then(profiles.create(account.id(), type.dbValue(), orgId))
                            .flatMap(p -> outbox.append(new EventEnvelope(
                                    UUID.randomUUID().toString(), "IdentityOpened", "IdentityProfile",
                                    p.id(), 1, Instant.now(), null,
                                    profileEventPayload(p, account.id())))
                                    .thenReturn(p))
                            .map(p -> ResponseEntity.status(201).body(Map.of("success", true, "data", profileBody(p))));
                })
                .onErrorResume(DataIntegrityViolationException.class, e ->
                        Mono.just(ResponseEntity.status(409).body(Map.of("success", false, "error", "已开通该身份"))));
    }

    @GetMapping("/api/me/active-identity")
    public Mono<ResponseEntity<Map<String, Object>>> getActive(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> activeIdentities.findByAccount(account.id())
                        .map(ai -> activeEnvelope(ai.activeIdentityType()))
                        .switchIfEmpty(Mono.just(activeEnvelope(null))))
                .map(ResponseEntity::ok);
    }

    @PostMapping(value = "/api/me/active-identity", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> activate(@RequestBody ActivateIdentityRequest body, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> {
                    IdentityType type = IdentityType.fromDb(body.type());
                    return profiles.findByAccountAndType(account.id(), type.dbValue())
                            .switchIfEmpty(Mono.error(new IdentityException(409, "未开通该身份，请先开通")))
                            .flatMap(p -> activeIdentities.setActive(account.id(), type.dbValue())
                                    .flatMap(ai -> outbox.append(new EventEnvelope(
                                            UUID.randomUUID().toString(), "ActiveIdentityChanged", "Account",
                                            account.id(), 1, Instant.now(), null,
                                            Map.of("accountId", account.id(), "activeIdentityType", type.dbValue())))
                                            .thenReturn(ai))
                                    .map(ai -> ResponseEntity.ok(activeEnvelope(ai.activeIdentityType()))));
                });
    }

    @DeleteMapping("/api/me/active-identity")
    public Mono<ResponseEntity<Map<String, Object>>> deactivate(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> activeIdentities.clear(account.id())
                        .flatMap(rows -> outbox.append(new EventEnvelope(
                                UUID.randomUUID().toString(), "ActiveIdentityChanged", "Account",
                                account.id(), 1, Instant.now(), null,
                                Map.of("accountId", account.id(), "activeIdentityType", "consumer")))
                                .thenReturn(rows))
                        .map(rows -> ResponseEntity.ok(activeEnvelope(null))));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> profileBody(IdentityProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("identityType", p.identityType());
        m.put("organizationId", p.organizationId());
        m.put("status", p.status());
        return m;
    }

    private Map<String, Object> profileEventPayload(IdentityProfile p, String accountId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", accountId);
        m.put("identityType", p.identityType());
        m.put("organizationId", p.organizationId());
        return m;
    }

    /** 活动身份响应包络；activeIdentityType 为 null 表示消费者。 */
    private Map<String, Object> activeEnvelope(String activeIdentityType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeIdentityType", activeIdentityType);
        return Map.of("success", true, "data", data);
    }
}
