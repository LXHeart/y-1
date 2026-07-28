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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 门店 HTTP 入口。草场身份域 Slice 2F。挂 {@code /api/organizations/{orgId}/stores}（门店属于 org，RESTful 嵌套）。
 *
 * <ul>
 *   <li>POST — 建门店，需 org 内 ADMIN 及以上角色，写 outbox {@code StoreCreated} 事件。</li>
 *   <li>GET — 列 org 下门店，需 MEMBER 及以上。</li>
 *   <li>GET /{storeId} — 单查，需 MEMBER 及以上；跨 org 或不存在返回 404。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/stores")
public class StoreController {

    private final OrgAuthorization authz;
    private final StoreRepository stores;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public StoreController(OrgAuthorization authz, StoreRepository stores, OutboxRepository outbox,
                           TransactionalOperator transactions) {
        this.authz = authz;
        this.stores = stores;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable String orgId,
                                                            @RequestBody CreateStoreRequest body,
                                                            ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        stores.create(orgId, body.name())
                                .flatMap(store -> outbox.append(new EventEnvelope(
                                        UUID.randomUUID().toString(), "StoreCreated", "Store",
                                        store.id(), 1, Instant.now(), null,
                                        Map.of("storeId", store.id(), "organizationId", orgId, "name", store.name())))
                                        .thenReturn(store)))
                        .map(store -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(store)))));
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> stores.findByOrganization(orgId).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
    }

    @GetMapping("/{storeId}")
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String orgId,
                                                         @PathVariable String storeId,
                                                         ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> stores.findByOrganizationAndId(orgId, storeId)
                        .map(store -> ResponseEntity.ok(Map.of("success", true, "data", toBody(store))))
                        .switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在"))));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(Store store) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", store.id());
        m.put("organizationId", store.organizationId());
        m.put("name", store.name());
        m.put("status", store.status());
        m.put("createdAt", store.createdAt() == null ? null : store.createdAt().toString());
        return m;
    }
}
