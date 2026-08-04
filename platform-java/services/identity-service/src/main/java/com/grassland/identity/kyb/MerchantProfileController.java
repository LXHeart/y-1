package com.grassland.identity.kyb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.OrganizationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final OrganizationRepository organizations;
    private final MerchantProfileRepository profiles;
    private final MerchantAttachmentRepository attachments;
    private final KybSubmissionService submissions;
    private final KybFieldCrypto crypto;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final ObjectMapper json = new ObjectMapper();

    public MerchantProfileController(
            OrgAuthorization authz,
            OrganizationRepository organizations,
            MerchantProfileRepository profiles,
            MerchantAttachmentRepository attachments,
            KybSubmissionService submissions,
            KybFieldCrypto crypto,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.authz = authz;
        this.organizations = organizations;
        this.profiles = profiles;
        this.attachments = attachments;
        this.submissions = submissions;
        this.crypto = crypto;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createOrUpdateDraft(@PathVariable String orgId,
                                                                           @RequestBody CreateMerchantProfileRequest body,
                                                                           ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                // 状态守卫：此前 POST 无守卫且 upsert 无条件覆盖 status，
                // 已 approved 的资料被 POST 一下就静默打回 draft，审核结果丢失。
                .flatMap(account -> transactions.transactional(
                        lockOrganization(orgId)
                                .then(profiles.findById(orgId)
                                        .flatMap(existing -> requireEditable(existing).thenReturn(existing)))
                                .then(saveFields(orgId, body))))
                .map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile))));
    }

    /** 写入资料字段（敏感字段先加密），并把 USCC 唯一冲突翻成 409。 */
    private Mono<MerchantProfile> saveFields(String orgId, CreateMerchantProfileRequest body) {
        return Mono.fromCallable(() -> {
                    LocalDate establishmentDate = parseLocalDate(body.establishmentDate());
                    String encryptedId = crypto.encrypt(body.legalPersonIdNumber());
                    return new Object[]{establishmentDate, encryptedId};
                })
                .flatMap(prepared -> profiles.upsertFields(
                        orgId, body.legalName(), body.unifiedSocialCreditCode(), body.businessType(),
                        body.legalPersonName(), (String) prepared[1], body.registeredCapitalCents(),
                        (LocalDate) prepared[0], serializeAddress(body.businessAddress()),
                        body.contactPhone(), body.contactEmail()))
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> new IdentityException(409, "该统一社会信用代码已被其他商家使用"));
    }

    /** 响应包装。用 LinkedHashMap 而非 {@code Map.of}——data 可为 null（资料尚未创建）。 */
    private static Map<String, Object> envelope(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    /**
     * 结构化地址 → jsonb 文本。街道地址为空视为「未填地址」返回 null，
     * 以免落一个 {@code {}} 让提交前的必填校验误判为已填。
     */
    private String serializeAddress(BusinessAddress address) {
        if (address == null || address.address() == null || address.address().isBlank()) {
            return null;
        }
        try {
            return json.writeValueAsString(address);
        } catch (JsonProcessingException e) {
            throw new IdentityException(400, "经营地址格式无效");
        }
    }

    /** 审核中/已批准的资料不可编辑。 */
    private Mono<Void> requireEditable(MerchantProfile profile) {
        MerchantProfileStatus status = MerchantProfileStatus.fromDb(profile.status());
        if (status.isUnderReview()) {
            return Mono.error(new IdentityException(409, "资料审核中，暂不可编辑"));
        }
        if (!status.isEditable()) {
            return Mono.error(new IdentityException(409, "资料已通过审核，如需变更请联系客服"));
        }
        return Mono.empty();
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                // ⚠️ 不能写 switchIfEmpty(Mono.just(ResponseEntity.ok(Map.of("data", null))))：
                // switchIfEmpty 的备选在**装配期**求值，而 Map.of 不接受 null value → 无论资料存不存在，
                // GET 一律 NPE→500。这里改用允许 null 的 LinkedHashMap。
                .flatMap(account -> profiles.findById(orgId)
                        .map(profile -> ResponseEntity.ok(envelope(toBody(profile))))
                        .defaultIfEmpty(ResponseEntity.ok(envelope(null))));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String orgId,
                                                              @RequestBody CreateMerchantProfileRequest body,
                                                              ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        lockOrganization(orgId)
                                .then(profiles.findById(orgId)
                                        .switchIfEmpty(Mono.error(
                                                new IdentityException(404, "商家资料不存在，请先创建")))
                                        .flatMap(profile -> requireEditable(profile)
                                                .then(saveFields(orgId, body))))))
                .map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile))));
    }

    /**
     * 提交审核：draft/rejected → pending，**同事务**入队 {@code kyb_verification_request} + 追加 outbox。
     *
     * <p>三者原子是关键：此前只改状态 + 发事件，审核行从不创建，admin 队列恒空、审核不可达。
     */
    @PostMapping("/submit")
    public Mono<ResponseEntity<Map<String, Object>>> submit(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        lockOrganization(orgId).then(profiles.findById(orgId))
                                .switchIfEmpty(Mono.error(new IdentityException(404, "商家资料不存在")))
                                .flatMap(profile -> {
                                    MerchantProfileStatus status = MerchantProfileStatus.fromDb(profile.status());
                                    if (status.isUnderReview()) {
                                        return Mono.<MerchantProfile>error(
                                                new IdentityException(409, "资料已在审核中"));
                                    }
                                    if (!status.canSubmit()) {
                                        return Mono.<MerchantProfile>error(
                                                new IdentityException(409, "资料已通过审核，无需重复提交"));
                                    }
                                    KybSubmissionService.requireCompleteProfile(profile);
                                    return attachments.findDocumentTypes(orgId).collectList()
                                            .doOnNext(KybSubmissionService::requireDocuments)
                                            .thenReturn(profile);
                                })
                                .flatMap(profile -> attachments.findIdsByOrganization(orgId).collectList()
                                        .flatMap(materialIds -> profiles.updateStatus(
                                                        orgId, MerchantProfileStatus.PENDING.dbValue(),
                                                        Instant.now(), null, null, null)
                                                .flatMap(updated -> submissions.enqueue(
                                                                KybVerificationType.MERCHANT_PROFILE, orgId,
                                                                UUID.fromString(orgId), account.id(), materialIds)
                                                        .flatMap(req -> outbox.append(submittedEvent(orgId, req))
                                                                .thenReturn(updated)))))))
                .map(profile -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(profile))));
    }

    private Mono<Void> lockOrganization(String orgId) {
        return organizations.findByIdForUpdate(orgId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                .then();
    }

    /**
     * 提交事件 payload。**刻意不带 legalName/USCC**：
     * ① 此前用 {@code Map.of} 装这两个可空列，空草稿提交直接 NPE → 500；
     * ② 按 D-10 最小化 PII——outbox 会被多个消费者读到，工商名称与信用代码不该外扩。
     */
    private EventEnvelope submittedEvent(String orgId, KybVerificationRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", orgId);
        payload.put("requestId", req.id().toString());
        return new EventEnvelope(UUID.randomUUID().toString(), "MerchantProfileSubmitted", "MerchantProfile",
                orgId, 1, Instant.now(), null, payload);
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
        // 身份证号只回末 4 位掩码，完整明文永不出响应体（D-10）。
        m.put("legalPersonIdNumberMasked", crypto.maskTail4(profile.legalPersonIdNumber()));
        m.put("registeredCapitalCents", profile.registeredCapitalCents());
        m.put("establishmentDate", profile.establishmentDate());
        m.put("businessAddress", profile.businessAddress());
        m.put("contactPhone", profile.contactPhone());
        m.put("contactEmail", profile.contactEmail());
        m.put("status", profile.status());
        m.put("submittedAt", profile.submittedAt() == null ? null : profile.submittedAt().toString());
        m.put("reviewedAt", profile.reviewedAt() == null ? null : profile.reviewedAt().toString());
        m.put("reviewerAccountId", profile.reviewerAccountId());
        m.put("reviewNote", profile.reviewNote());
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
            BusinessAddress businessAddress,
            String contactPhone,
            String contactEmail
    ) {}

    /**
     * 经营地址。对应 {@code merchant_profile.business_address jsonb}。
     *
     * <p>此前请求契约把它当扁平 {@code String} 直接 {@code CAST(... AS jsonb)}——传任何普通文本地址都是
     * {@code 22P02 invalid input syntax for type json} → 500。改成结构化对象，由服务端序列化，
     * 客户端不需要自己拼 JSON 字符串。
     *
     * <p>经纬度归 store_profile 的地理能力（本轮不做），此处只留行政区划 + 街道地址。
     */
    public record BusinessAddress(
            String province,
            String city,
            String district,
            String address
    ) {}
}
