package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 门店粒度成员 HTTP 入口。草场身份域 Slice 2G（HLD store-membership）。
 * 挂 {@code /api/organizations/{orgId}/stores/{storeId}/memberships}。
 *
 * <ul>
 *   <li>GET — 列门店成员，需 org MEMBER+。</li>
 *   <li>POST — 加成员，需 org ADMIN+；UNIQUE(store,account) 冲突 409；写 outbox {@code StoreMembershipGranted}。</li>
 *   <li>DELETE /{accountId} — 移除成员，需 org ADMIN+。</li>
 * </ul>
 *
 * <p>管理 authz 复用 org 级 {@link OrgAuthorization}（门店 MANAGER 级独立授权留后续）；增成员前校验 store 属于该 org（跨 org storeId → 404）。
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/stores/{storeId}/memberships")
public class StoreMembershipController {

    private final OrgAuthorization authz;
    private final StoreMembershipRepository storeMemberships;
    private final StoreRepository stores;
    private final OutboxRepository outbox;

    public StoreMembershipController(OrgAuthorization authz, StoreMembershipRepository storeMemberships,
                                     StoreRepository stores, OutboxRepository outbox) {
        this.authz = authz;
        this.storeMemberships = storeMemberships;
        this.stores = stores;
        this.outbox = outbox;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, @PathVariable String storeId,
                                                          ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> ensureStoreInOrg(orgId, storeId)
                        .then(storeMemberships.findByStore(storeId).collectList()
                                .map(list -> ResponseEntity.ok(Map.of("success", true,
                                        "data", list.stream().map(this::toBody).toList())))));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> add(@PathVariable String orgId, @PathVariable String storeId,
                                                         @RequestBody CreateStoreMembershipRequest body,
                                                         ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> ensureStoreInOrg(orgId, storeId)
                        .then(storeMemberships.create(storeId, body.accountId(), body.role()))
                        .flatMap(m -> outbox.append(new EventEnvelope(
                                UUID.randomUUID().toString(), "StoreMembershipGranted", "StoreMembership",
                                m.id(), 1, Instant.now(), null,
                                Map.of("storeId", storeId, "accountId", m.accountId(), "role", m.role())))
                                .thenReturn(m))
                        .map(m -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(m)))))
                .onErrorResume(DataIntegrityViolationException.class, e ->
                        Mono.just(ResponseEntity.status(409).body(Map.of("success", false, "error", "该账号已是门店成员"))));
    }

    @DeleteMapping("/{accountId}")
    public Mono<ResponseEntity<Map<String, Object>>> remove(@PathVariable String orgId, @PathVariable String storeId,
                                                            @PathVariable String accountId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> ensureStoreInOrg(orgId, storeId)
                        .then(storeMemberships.deleteByStoreAndAccount(storeId, accountId))
                        .map(deleted -> deleted > 0
                                ? ResponseEntity.ok(Map.<String, Object>of("success", true))
                                : ResponseEntity.status(404).body(Map.<String, Object>of("success", false, "error", "门店成员不存在"))));
    }

    /** 校验 store 属于该 org，否则 404（跨 org 隔离）。 */
    private Mono<Void> ensureStoreInOrg(String orgId, String storeId) {
        return stores.findByOrganizationAndId(orgId, storeId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在")))
                .then();
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(StoreMembership m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id());
        map.put("storeId", m.storeId());
        map.put("accountId", m.accountId());
        map.put("role", m.role());
        map.put("createdAt", m.createdAt() == null ? null : m.createdAt().toString());
        return map;
    }
}
