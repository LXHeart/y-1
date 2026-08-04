package com.grassland.identity.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.DeleteMapping;

/**
 * 门店 HTTP 入口。草场身份域 Slice 2F。挂 {@code /api/organizations/{orgId}/stores}（门店属于 org，RESTful 嵌套）。
 *
 * <ul>
 *   <li>POST — 建门店，需 org 内 ADMIN 及以上角色，写 outbox {@code StoreCreated} 事件。</li>
 *   <li>GET — 列 org 下门店，需 MEMBER 及以上。</li>
 *   <li>GET /{storeId} — 单查，需 MEMBER 及以上；跨 org 或不存在返回 404。</li>
 *   <li>GL-P3-MERCHANT-001 新增：</li>
 *   <li>POST /{storeId}/profile — 创建/更新门店详细资料，需 ADMIN 及以上。</li>
 *   <li>GET /{storeId}/profile — 查询门店详细资料，需 MEMBER 及以上。</li>
 *   <li>DELETE /{storeId}/profile — 删除门店详细资料，需 ADMIN 及以上。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/stores")
public class StoreController {

    private final OrgAuthorization authz;
    private final StoreRepository stores;
    private final StoreProfileRepository storeProfiles;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final ObjectMapper json = new ObjectMapper();

    public StoreController(OrgAuthorization authz, StoreRepository stores, StoreProfileRepository storeProfiles,
                           OutboxRepository outbox, TransactionalOperator transactions) {
        this.authz = authz;
        this.stores = stores;
        this.storeProfiles = storeProfiles;
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

    // GL-P3-MERCHANT-001: 门店详细资料端点

    @PostMapping(path = "/{storeId}/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> upsertProfile(@PathVariable String orgId,
                                                                    @PathVariable String storeId,
                                                                    @RequestBody CreateStoreProfileRequest body,
                                                                    ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> requireStoreInOrganization(orgId, storeId)
                        .then(transactions.transactional(
                                storeProfiles.upsert(orgId, storeId, requireAddress(body.address()), body.phone(),
                                        requireBusinessHours(body.businessHours()), body.description(), "active")))
                        .map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile)))));
    }

    @GetMapping("/{storeId}/profile")
    public Mono<ResponseEntity<Map<String, Object>>> getProfile(@PathVariable String orgId,
                                                                 @PathVariable String storeId,
                                                                 ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> requireStoreInOrganization(orgId, storeId)
                        .then(storeProfiles.findByOrganizationAndId(orgId, storeId))
                        .map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile))))
                        .defaultIfEmpty(ResponseEntity.ok(envelope(null))));
    }

    @DeleteMapping("/{storeId}/profile")
    public Mono<ResponseEntity<Map<String, Object>>> deleteProfile(@PathVariable String orgId,
                                                                    @PathVariable String storeId,
                                                                    ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> requireStoreInOrganization(orgId, storeId)
                        .then(transactions.transactional(
                                storeProfiles.deactivate(orgId, storeId)
                                        .switchIfEmpty(Mono.error(new IdentityException(
                                                404, "门店资料不存在")))))
                        .map(profile -> ResponseEntity.ok(Map.of(
                                "success", true, "data", Map.of("deleted", true)))));
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

    private Mono<Void> requireStoreInOrganization(String orgId, String storeId) {
        return stores.findByOrganizationAndId(orgId, storeId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在")))
                .then();
    }

    private String requireAddress(String value) {
        if (value == null || value.isBlank()) {
            throw new IdentityException(400, "门店地址不能为空");
        }
        try {
            JsonNode address = json.readTree(value);
            JsonNode street = address == null ? null : address.get("address");
            if (address == null || !address.isObject() || street == null
                    || !street.isTextual() || street.asText().isBlank()) {
                throw new IdentityException(400, "门店地址格式无效");
            }
            return value;
        } catch (JsonProcessingException error) {
            throw new IdentityException(400, "门店地址格式无效");
        }
    }

    private String requireBusinessHours(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IdentityException(400, "门店营业时间格式无效");
        }
        try {
            JsonNode businessHours = json.readTree(value);
            if (businessHours == null || !businessHours.isArray()) {
                throw new IdentityException(400, "门店营业时间格式无效");
            }
            if (businessHours.size() > 7) {
                throw new IdentityException(400, "门店营业时间格式无效");
            }
            Set<Integer> seenDays = new HashSet<>();
            for (JsonNode item : businessHours) {
                JsonNode dayOfWeek = item.get("dayOfWeek");
                JsonNode openTime = item.get("openTime");
                JsonNode closeTime = item.get("closeTime");
                if (!item.isObject() || item.size() != 3
                        || dayOfWeek == null || !dayOfWeek.isIntegralNumber() || !dayOfWeek.canConvertToInt()
                        || dayOfWeek.intValue() < 1 || dayOfWeek.intValue() > 7
                        || openTime == null || !openTime.isTextual()
                        || closeTime == null || !closeTime.isTextual()
                        || !openTime.textValue().matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
                        || !closeTime.textValue().matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
                        || !seenDays.add(dayOfWeek.intValue())) {
                    throw new IdentityException(400, "门店营业时间格式无效");
                }
                LocalTime opensAt = LocalTime.parse(openTime.textValue());
                LocalTime closesAt = LocalTime.parse(closeTime.textValue());
                if (!opensAt.isBefore(closesAt)) {
                    throw new IdentityException(400, "门店营业时间格式无效");
                }
            }
            return json.writeValueAsString(businessHours);
        } catch (JsonProcessingException | DateTimeParseException error) {
            throw new IdentityException(400, "门店营业时间格式无效");
        }
    }

    private static Map<String, Object> envelope(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private Map<String, Object> toBody(StoreProfile profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("storeId", profile.storeId());
        m.put("address", profile.address());
        m.put("phone", profile.phone());
        m.put("businessHours", profile.businessHours());
        m.put("description", profile.description());
        m.put("status", profile.status());
        m.put("createdAt", profile.createdAt() == null ? null : profile.createdAt().toString());
        return m;
    }

    public record CreateStoreProfileRequest(
            String address,
            String phone,
            String businessHours,
            String description
    ) {}
}
