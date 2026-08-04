package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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

    private final OrgAuthorization authz;
    private final MerchantAttachmentRepository attachments;
    private final TransactionalOperator transactions;

    public MerchantAttachmentController(
            OrgAuthorization authz,
            MerchantAttachmentRepository attachments,
            TransactionalOperator transactions) {
        this.authz = authz;
        this.attachments = attachments;
        this.transactions = transactions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable String orgId,
                                                              @RequestBody CreateAttachmentRequest body,
                                                              ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> {
                    MerchantAttachmentType type = MerchantAttachmentType.fromRequest(body.attachmentType());
                    // 证件类附件唯一约束校验
                    if (type.isDocumentType()) {
                        return attachments.findByOrganizationAndType(orgId, type.dbValue())
                                .flatMap(existing -> Mono.<Map<String, Object>>error(
                                        new IdentityException(409, "该类型附件已存在，请先删除后再上传")))
                                .switchIfEmpty(Mono.defer(() -> createAttachment(orgId, type, body, account.id())));
                    }
                    return createAttachment(orgId, type, body, account.id());
                })
                .map(result -> ResponseEntity.status(201).body(Map.of("success", true, "data", result)));
    }

    /**
     * 落库。**写入的是 {@code type.dbValue()} 而非请求原文**——原文大小写/空格未归一化时，
     * 既绕过 {@code uq_merchant_attachment_org_type} 唯一索引（'Business_License' 与 'business_license'
     * 被当成两种），也让提交前的材料齐备校验按 dbValue 比对时漏判。
     */
    private Mono<Map<String, Object>> createAttachment(String orgId, MerchantAttachmentType type,
                                                        CreateAttachmentRequest body, String accountId) {
        UUID mediaRefId = KybSubmissionService.parseUuid(body.mediaReferenceId(), "媒体引用 ID");
        return transactions.transactional(
                attachments.create(orgId, type.dbValue(), mediaRefId, body.mimeType(), body.sizeBytes(), accountId))
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
                        attachments.deleteByIdAndOrganization(
                                        KybSubmissionService.parseUuid(id, "附件 ID"), orgId)
                                .flatMap(deleted -> deleted > 0
                                        ? Mono.just(ResponseEntity.ok(Map.of("success", true,
                                                "data", Map.of("deleted", true))))
                                        : Mono.error(new IdentityException(404, "附件不存在")))));
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
        return m;
    }

    public record CreateAttachmentRequest(
            String attachmentType,
            String mediaReferenceId,
            String mimeType,
            Long sizeBytes
    ) {}
}
