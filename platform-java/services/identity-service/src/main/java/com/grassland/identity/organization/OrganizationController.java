package com.grassland.identity.organization;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.Membership;
import com.grassland.identity.membership.MembershipRepository;
import com.grassland.identity.membership.MembershipRole;
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
 * 商家主体（Organization）HTTP 入口。草场身份域 Slice 2E；Slice 2F 加创建时种 OWNER 成员行。
 *
 * <ul>
 *   <li>POST /api/organizations — 创建组织（当前 account 为 owner），种 OWNER 成员行，写 outbox {@code OrganizationCreated} 事件。</li>
 *   <li>GET /api/organizations/{id} — 取单个组织。</li>
 *   <li>GET /api/organizations — 列出当前 account 名下组织。</li>
 * </ul>
 *
 * <p>所有端点经 {@link CurrentAccountResolver} 鉴权（需登录 session）。
 *
 * <p><b>权限升级不在本 controller。</b>商家准入等级只能经
 * {@code PermissionRequestController} 的审核工作流变更（org OWNER 提交申请 + 材料校验 → 平台 admin
 * 审核 approve → 升 tier）。曾经存在的 {@code POST /{id}/permissions/grant} 是 Slice 2F 的 dev 地基，
 * 允许 org owner 无审核把自己单调升到最高 tier（GL-P0-SEC-002），已随审核流上线删除；不要重新引入。
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final CurrentAccountResolver accounts;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public OrganizationController(CurrentAccountResolver accounts, OrganizationRepository organizations,
                                  MembershipRepository memberships, OutboxRepository outbox,
                                  TransactionalOperator transactions) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.memberships = memberships;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateOrganizationRequest body, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(owner -> transactions.transactional(
                        organizations.create(owner.id(), body.name(), normalizeIndustry(body.industry()))
                                .flatMap(org -> seedOwnerMembership(org, owner.id()).thenReturn(org))
                                .flatMap(org -> outbox.append(new EventEnvelope(
                                        UUID.randomUUID().toString(), "OrganizationCreated", "Organization",
                                        org.id(), 1, Instant.now(), null,
                                        Map.of("organizationId", org.id(), "ownerAccountId", owner.id(), "name", org.name())))
                                        .thenReturn(org)))
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

    /** best-effort 种 OWNER 成员行：失败不阻断 org 创建（鉴权兜底靠 owner_account_id）。 */
    private Mono<Membership> seedOwnerMembership(Organization org, String ownerId) {
        return memberships.create(org.id(), ownerId, MembershipRole.OWNER.dbValue())
                .onErrorResume(e -> Mono.empty());
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
        m.put("permissionTier", org.permissionTier());
        m.put("industry", org.industry());
        m.put("createdAt", org.createdAt() == null ? null : org.createdAt().toString());
        return m;
    }

    /** 归一化行业：null/空 → other；否则小写。合法性留权限申请时校验（避免 organization↔permission 循环）。 */
    private static String normalizeIndustry(String industry) {
        return (industry == null || industry.isBlank()) ? "other" : industry.trim().toLowerCase();
    }
}
