package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.OrganizationRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
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
 * 商家附件 HTTP 入口。GL-P3-MERCHANT-001。
 *
 * <ul>
 *   <li>POST — 上传附件，需 ADMIN 及以上角色。证件类（business_license 等）每种只能有一个。</li>
 *   <li>GET — 列出附件，需 MEMBER 及以上。</li>
 *   <li>DELETE — 删除附件，需 ADMIN 及以上。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/merchant-attachments")
public class MerchantAttachmentController {

    private static final Logger log = LoggerFactory.getLogger(MerchantAttachmentController.class);

    private final OrgAuthorization authz;
    private final OrganizationRepository organizations;
    private final MerchantAttachmentRepository attachments;
    private final MerchantProfileRepository profiles;
    private final KybMediaClient mediaClient;
    private final KybMediaValidator mediaValidator;
    private final KybMediaRetentionCommandRepository retentionCommands;
    private final KybMediaRetentionProperties retentionProperties;
    private final KybDocumentAnalysisJobRepository documentAnalysisJobs;
    private final TransactionalOperator transactions;

    public MerchantAttachmentController(
            OrgAuthorization authz,
            OrganizationRepository organizations,
            MerchantAttachmentRepository attachments,
            MerchantProfileRepository profiles,
            KybMediaClient mediaClient,
            KybMediaValidator mediaValidator,
            KybMediaRetentionCommandRepository retentionCommands,
            KybMediaRetentionProperties retentionProperties,
            KybDocumentAnalysisJobRepository documentAnalysisJobs,
            TransactionalOperator transactions) {
        this.authz = authz;
        this.organizations = organizations;
        this.attachments = attachments;
        this.profiles = profiles;
        this.mediaClient = mediaClient;
        this.mediaValidator = mediaValidator;
        this.retentionCommands = retentionCommands;
        this.retentionProperties = retentionProperties;
        this.documentAnalysisJobs = documentAnalysisJobs;
        this.transactions = transactions;
    }

    /** 由 identity 的组织鉴权上下文代申请票据，避免浏览器当前活动组织污染媒体归属。 */
    @PostMapping(path = "/upload-ticket", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createUploadTicket(
            @PathVariable String orgId,
            @RequestBody CreateKybUploadTicketRequest body,
            ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> Mono.defer(() -> {
                    if (body == null || body.sizeBytes() == null || body.sizeBytes() < 1) {
                        return Mono.error(new IdentityException(400, "文件大小无效"));
                    }
                    MerchantAttachmentType type = body.attachmentType() == null
                                    || body.attachmentType().isBlank()
                            ? MerchantAttachmentType.OTHER
                            : MerchantAttachmentType.fromRequest(body.attachmentType());
                    String contentType = mediaValidator.requireAllowedMime(body.contentType());
                    return requireAttachmentEditable(orgId, type)
                            .then(mediaClient.createUploadTicket(
                                    orgId, account.id(), contentType, body.sizeBytes()));
                }))
                .map(ticket -> ResponseEntity.ok(Map.of("success", true, "data", ticket)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable String orgId,
                                                              @RequestBody CreateAttachmentRequest body,
                                                              ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> Mono.defer(() -> {
                    if (body == null) {
                        return Mono.error(new IdentityException(400, "附件请求不能为空"));
                    }
                    MerchantAttachmentType type = MerchantAttachmentType.fromRequest(body.attachmentType());
                    UUID mediaRefId = KybSubmissionService.parseUuid(body.mediaReferenceId(), "媒体引用 ID");
                    return transactions.transactional(lockOrganization(orgId)
                            .then(requireAttachmentEditable(orgId, type))
                            .then(Mono.defer(() -> {
                            // 证件类附件唯一约束校验
                            if (type.isDocumentType()) {
                                return attachments.findByOrganizationAndType(orgId, type.dbValue())
                                        .flatMap(existing -> Mono.<Map<String, Object>>error(
                                                new IdentityException(409, "该类型附件已存在，请先删除后再上传")))
                                        .switchIfEmpty(attachments.findByOrganizationAndMediaReference(orgId, mediaRefId)
                                                .flatMap(existing -> Mono.<Map<String, Object>>error(
                                                        new IdentityException(409,
                                                                "同一媒体不能重复作为多种审核证件")))
                                                .switchIfEmpty(Mono.defer(() -> createAttachment(
                                                        orgId, type, mediaRefId, account.id()))));
                            }
                            return createAttachment(orgId, type, mediaRefId, account.id());
                        })));
                }))
                .onErrorMap(DataIntegrityViolationException.class,
                        error -> new IdentityException(409, "附件类型或媒体已存在"))
                .map(result -> ResponseEntity.status(201).body(Map.of("success", true, "data", result)));
    }

    /**
     * 落库。**写入的是 {@code type.dbValue()} 而非请求原文**——原文大小写/空格未归一化时，
     * 既绕过 {@code uq_merchant_attachment_org_type} 唯一索引（'Business_License' 与 'business_license'
     * 被当成两种），也让提交前的材料齐备校验按 dbValue 比对时漏判。
     */
    private Mono<Map<String, Object>> createAttachment(String orgId, MerchantAttachmentType type,
                                                        UUID mediaRefId, String accountId) {
        UUID attachmentId = UUID.randomUUID();
        return mediaClient.requireUsable(mediaRefId, orgId, accountId)
                .flatMap(media -> mediaClient.acquireLease(
                                mediaRefId, orgId, attachmentId, "attachment",
                                retentionProperties.liveLeaseSeconds())
                        .flatMap(receipt -> attachments.create(
                                        attachmentId, orgId, type.dbValue(), mediaRefId,
                                        media.mimeType(), media.sizeBytes(), accountId)
                                .flatMap(attachment -> retentionCommands.upsertLive(
                                                mediaRefId, attachmentId, orgId, "attachment",
                                                receipt.leaseUntil())
                                        .thenReturn(attachment))
                                .flatMap(attachment -> type.isDocumentType()
                                        ? documentAnalysisJobs.enqueue(attachment.id()).thenReturn(attachment)
                                        : Mono.just(attachment))
                                // 外部租约成功而本地事务失败时立即释放；进程崩溃则由有限租约自动收敛。
                                .onErrorResume(error -> mediaClient.release(mediaRefId, orgId, attachmentId)
                                        .onErrorResume(releaseError -> Mono.empty())
                                        .then(Mono.error(error)))))
                .map(this::toBody);
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> attachments.findByOrganization(orgId).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
    }

    /**
     * 删除附件。谓词按 {@code (id, orgId)} 双限定——只按 id 删会让 A 商家的 ADMIN
     * 猜到 id 就删掉 B 商家的营业执照（跨租户删除）。
     *
     * <p>不再用 {@code onErrorResume} 把未知错误吞成 {@code 200 {"deleted": false}}：
     * 那会让连接失败/约束错误看起来像「删了但没删掉」，掩盖真实故障。
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String orgId,
                                                           @PathVariable String id,
                                                           ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        lockOrganization(orgId).then(Mono.defer(() -> {
                                    UUID attachmentId = KybSubmissionService.parseUuid(id, "附件 ID");
                                    return attachments.findByIdAndOrganization(attachmentId, orgId)
                                            .switchIfEmpty(Mono.error(new IdentityException(404, "附件不存在")))
                                            .flatMap(attachment -> {
                                                MerchantAttachmentType type = MerchantAttachmentType.fromDb(
                                                        attachment.attachmentType());
                                                Mono<Void> referenceGuard = type.isPermissionSupplement()
                                                        ? attachments.isReferencedByOpenPermissionRequest(
                                                                        orgId, attachmentId)
                                                                .flatMap(referenced -> referenced
                                                                        ? Mono.<Void>error(new IdentityException(409,
                                                                                "附件已被待审权限申请引用，暂不可删除"))
                                                                        : Mono.empty())
                                                        : Mono.empty();
                                                return requireAttachmentEditable(orgId, type)
                                                        .then(referenceGuard)
                                                        .then(attachments.deleteByIdAndOrganization(
                                                                attachmentId, orgId))
                                                        .flatMap(deleted -> deleted > 0
                                                                ? retentionCommands.markReleased(
                                                                                attachment.mediaReferenceId(),
                                                                                attachment.id(), orgId)
                                                                        .thenReturn(attachment)
                                                                : Mono.error(new IdentityException(404,
                                                                        "附件不存在")));
                                            });
                                }))))
                // 本地删除和 desired_state=released 已原子提交；同步释放只用于加速，失败由 worker 重试。
                .flatMap(attachment -> mediaClient.release(attachment.mediaReferenceId(), orgId, attachment.id())
                        .then(retentionCommands.markReleasedSynced(
                                attachment.mediaReferenceId(), attachment.id(), orgId))
                        .onErrorResume(error -> {
                            log.warn("KYB attachment retention release deferred: attachmentId={}", attachment.id());
                            return Mono.empty();
                        })
                        .thenReturn(ResponseEntity.ok(Map.of(
                                "success", true, "data", Map.of("deleted", true)))));
    }

    private Mono<Void> requireAttachmentEditable(String orgId, MerchantAttachmentType type) {
        return profiles.findById(orgId)
                .flatMap(profile -> {
                    MerchantProfileStatus status = MerchantProfileStatus.fromDb(profile.status());
                    if (status.isUnderReview()) {
                        return Mono.error(new IdentityException(409, "资料审核中，暂不可变更附件"));
                    }
                    if (type.isPermissionSupplement()) {
                        return Mono.empty();
                    }
                    if (!status.isEditable()) {
                        return Mono.error(new IdentityException(409, "资料已通过审核，暂不可变更附件"));
                    }
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> lockOrganization(String orgId) {
        return organizations.findByIdForUpdate(orgId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                .then();
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(MerchantAttachment attachment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", attachment.id());
        m.put("organizationId", attachment.organizationId());
        m.put("attachmentType", attachment.attachmentType());
        m.put("mediaReferenceId", attachment.mediaReferenceId());
        m.put("mimeType", attachment.mimeType());
        m.put("sizeBytes", attachment.sizeBytes());
        m.put("uploadedAt", attachment.uploadedAt() == null ? null : attachment.uploadedAt().toString());
        m.put("ocrStatus", attachment.ocrStatus());
        m.put("ocrAnalyzedAt", attachment.ocrAnalyzedAt() == null
                ? null : attachment.ocrAnalyzedAt().toString());
        m.put("ocrFailureCode", attachment.ocrFailureCode());
        return m;
    }

    public record CreateAttachmentRequest(
            String attachmentType,
            String mediaReferenceId,
            String mimeType,
            Long sizeBytes
    ) {}

    public record CreateKybUploadTicketRequest(
            String contentType,
            Long sizeBytes,
            String attachmentType
    ) {}
}
