package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 商家资料 HTTP 入口。GL-P3-MERCHANT-001。
 *
 * <ul>
 *   <li>POST — 创建/更新草稿，需 org 内 ADMIN 及以上角色。</li>
 *   <li>GET — 查询资料，需 MEMBER 及以上。</li>
 *   <li>PUT — 更新资料（仅 draft 状态），需 ADMIN 及以上。</li>
 *   <li>POST /submit — 提交审核（draft → pending），需 ADMIN 及以上。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/merchant-profile")
public class MerchantProfileController {

    private final OrgAuthorization authz;
    private final MerchantProfileRepository profiles;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public MerchantProfileController(
            OrgAuthorization authz,
            MerchantProfileRepository profiles,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.authz = authz;
        this.profiles = profiles;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createOrUpdateDraft(@PathVariable String orgId,
                                                                           @RequestBody CreateMerchantProfileRequest body,
                                                                           ServerHttpRequest request) {
        LocalDate establishmentDate = parseLocalDate(body.establishmentDate());
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        profiles.upsert(orgId, body.legalName(), body.unifiedSocialCreditCode(),
                                body.businessType(), body.legalPersonName(), body.legalPersonIdNumber(),
                                body.registeredCapitalCents(), establishmentDate, body.businessAddress(),
                                body.contactPhone(), body.contactEmail(), "draft", null, null, null, null))
                        .map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile)))));
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> profiles.findById(orgId)
                        .map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile))))
                        .switchIfEmpty(Mono.just(ResponseEntity.ok(Map.of("success", true, "data", null)))));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String orgId,
                                                              @RequestBody CreateMerchantProfileRequest body,
                                                              ServerHttpRequest request) {
        LocalDate establishmentDate = parseLocalDate(body.establishmentDate());
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> profiles.findById(orgId)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "商家资料不存在，请先创建")))
                        .filter(profile -> MerchantProfileStatus.fromDb(profile.status()).isEditable())
                        .switchIfEmpty(Mono.error(new IdentityException(409, "当前状态不可编辑")))
                        .flatMap(profile -> transactions.transactional(
                                profiles.upsert(orgId, body.legalName(), body.unifiedSocialCreditCode(),
                                        body.businessType(), body.legalPersonName(), body.legalPersonIdNumber(),
                                        body.registeredCapitalCents(), establishmentDate, body.businessAddress(),
                                        body.contactPhone(), body.contactEmail(), "draft", null, null, null, null)))
                        .map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile)))));
    }

    @PostMapping("/submit")
    public Mono<ResponseEntity<Map<String, Object>>> submit(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> profiles.findById(orgId)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "商家资料不存在"))))
                .filter(profile -> MerchantProfileStatus.fromDb(profile.status()).isEditable())
                .switchIfEmpty(Mono.error(new IdentityException(409, "当前状态不可提交审核")))
                .flatMap(profile -> transactions.transactional(
                        profiles.updateStatus(orgId, MerchantProfileStatus.PENDING.dbValue(),
                                Instant.now(), null, null, null)
                                .flatMap(updated -> outbox.append(new EventEnvelope(
                                        UUID.randomUUID().toString(), "MerchantProfileSubmitted", "MerchantProfile",
                                        orgId, 1, Instant.now(), null,
                                        Map.of("organizationId", orgId, "legalName", updated.legalName(),
                                                "unifiedSocialCreditCode", updated.unifiedSocialCreditCode())))
                                        .thenReturn(updated))))
                .map(profile -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(profile))));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IdentityException(400, "成立日期格式无效，应为 YYYY-MM-DD");
        }
    }

    private Map<String, Object> toBody(MerchantProfile profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizationId", profile.organizationId());
        m.put("legalName", profile.legalName());
        m.put("unifiedSocialCreditCode", profile.unifiedSocialCreditCode());
        m.put("businessType", profile.businessType());
        m.put("legalPersonName", profile.legalPersonName());
        m.put("registeredCapitalCents", profile.registeredCapitalCents());
        m.put("establishmentDate", profile.establishmentDate());
        m.put("businessAddress", profile.businessAddress());
        m.put("contactPhone", profile.contactPhone());
        m.put("contactEmail", profile.contactEmail());
        m.put("status", profile.status());
        m.put("submittedAt", profile.submittedAt() == null ? null : profile.submittedAt().toString());
        m.put("reviewedAt", profile.reviewedAt() == null ? null : profile.reviewedAt().toString());
        m.put("createdAt", profile.createdAt() == null ? null : profile.createdAt().toString());
        return m;
    }

    public record CreateMerchantProfileRequest(
            String legalName,
            String unifiedSocialCreditCode,
            String businessType,
            String legalPersonName,
            String legalPersonIdNumber,
            Long registeredCapitalCents,
            String establishmentDate,
            String businessAddress,
            String contactPhone,
            String contactEmail
    ) {}
}
