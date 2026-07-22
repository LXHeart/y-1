package com.grassland.identity.organization;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
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
import reactor.core.publisher.Mono;

/**
 * 商家主体（Organization）HTTP 入口。草场身份域 Slice 2E。
 *
 * <ul>
 *   <li>POST /api/organizations — 创建组织（当前 account 为 owner），写 outbox {@code OrganizationCreated} 事件。</li>
 *   <li>GET /api/organizations/{id} — 取单个组织。</li>
 *   <li>GET /api/organizations — 列出当前 account 名下组织。</li>
 * </ul>
 *
 * <p>所有端点经 {@link CurrentAccountResolver} 鉴权（需登录 session）。
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final CurrentAccountResolver accounts;
    private final OrganizationRepository organizations;
    private final OutboxRepository outbox;

    public OrganizationController(CurrentAccountResolver accounts, OrganizationRepository organizations, OutboxRepository outbox) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.outbox = outbox;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateOrganizationRequest body, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(owner -> organizations.create(owner.id(), body.name())
                        .flatMap(org -> outbox.append(new EventEnvelope(
                                UUID.randomUUID().toString(), "OrganizationCreated", "Organization",
                                org.id(), 1, Instant.now(), null,
                                Map.of("organizationId", org.id(), "ownerAccountId", owner.id(), "name", org.name())))
                                .thenReturn(org))
                        .map(org -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(org)))));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(acc -> organizations.findById(id)
                        .map(org -> ResponseEntity.ok(Map.of("success", true, "data", toBody(org))))
                        .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在"))));
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> listMine(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(acc -> organizations.findByOwner(acc.id()).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(Organization org) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", org.id());
        m.put("ownerAccountId", org.ownerAccountId());
        m.put("name", org.name());
        m.put("status", org.status());
        m.put("createdAt", org.createdAt() == null ? null : org.createdAt().toString());
        return m;
    }
}
