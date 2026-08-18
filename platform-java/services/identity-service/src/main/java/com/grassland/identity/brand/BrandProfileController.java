package com.grassland.identity.brand;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.OrganizationRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
 * 组织品牌资料 HTTP 入口（#32 D3/D4/D6/D7）。仅商家后台读写，无公开接口（D9）。
 *
 * <ul>
 *   <li>GET — 查询资料，MEMBER 及以上（member 只读）；无行回 version=0 的空资料；
 *       {@code logoUrl} 经 {@link BrandLogoMediaClient#logoUrlFailSoft} 解析，失败置 null（fail-soft）。</li>
 *   <li>PUT — 整份覆盖保存，ADMIN 及以上；校验链：长度帽 → 经营分类枚举 → Logo 归属 fail-closed
 *       → {@code save} 的 version CAS，empty 一律 409「品牌资料已变更，请刷新后重试」。
 *       写路径包 {@code lockOrganization} 事务，与商家资料/附件按同 org 串行化。</li>
 *   <li>POST /logo/upload-ticket — ADMIN 及以上代开 Logo 上传票据（ownerAccountId=操作者，
 *       组织上下文只取服务断言，D6）；上游 4xx 同码透传中文错误。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/brand-profile")
public class BrandProfileController {

    private final OrgAuthorization authz;
    private final OrganizationRepository organizations;
    private final OrganizationBrandProfileRepository profiles;
    private final BrandLogoMediaClient mediaClient;
    private final TransactionalOperator transactions;

    public BrandProfileController(
            OrgAuthorization authz,
            OrganizationRepository organizations,
            OrganizationBrandProfileRepository profiles,
            BrandLogoMediaClient mediaClient,
            TransactionalOperator transactions) {
        this.authz = authz;
        this.organizations = organizations;
        this.profiles = profiles;
        this.mediaClient = mediaClient;
        this.transactions = transactions;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String orgId,
                                                          ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> profiles.find(orgId)
                        .flatMap(profile -> brandBody(profile, orgId))
                        .defaultIfEmpty(toBody(emptyProfile(orgId), null)))
                .map(data -> ResponseEntity.ok(envelope(data)));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String orgId,
                                                             @RequestBody UpdateBrandProfileRequest body,
                                                             ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> Mono.defer(() -> {
                    BrandProfileDraft draft = draftFrom(body);
                    return transactions.transactional(
                            lockOrganization(orgId)
                                    .then(requireUsableLogoUrl(orgId, draft.logoMediaReferenceId()))
                                    .flatMap(logoUrl -> profiles.save(
                                                    orgId, draft.brandName(), draft.logoMediaReferenceId(),
                                                    draft.description(), draft.industry(), draft.expectedVersion())
                                            .switchIfEmpty(Mono.error(new IdentityException(
                                                    409, "品牌资料已变更，请刷新后重试")))
                                            // 校验通过的 Logo 直接复用 usableLogoUrl 下发的展示 URL。
                                            .map(saved -> toBody(saved, logoUrl.orElse(null)))));
                }))
                .map(data -> ResponseEntity.ok(envelope(data)));
    }

    /** identity 完成 ADMIN+ 授权后代申请品牌 Logo 上传票据；ownerAccountId=操作者，组织只取服务断言。 */
    @PostMapping(path = "/logo/upload-ticket", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createLogoUploadTicket(
            @PathVariable String orgId,
            @RequestBody CreateBrandLogoTicketRequest body,
            ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> Mono.defer(() -> {
                    if (body == null || body.sizeBytes() == null || body.sizeBytes() < 1) {
                        return Mono.error(new IdentityException(400, "文件大小无效"));
                    }
                    return mediaClient.createTicket(orgId, account.id(), body.contentType(), body.sizeBytes());
                }))
                .map(ticket -> ResponseEntity.ok(envelope(ticket)));
    }

    /** 写前归一化 + 校验链前段（长度 → 枚举 → 引用 ID 格式）；违例 400。 */
    private BrandProfileDraft draftFrom(UpdateBrandProfileRequest body) {
        if (body == null) {
            throw new IdentityException(400, "品牌资料请求不能为空");
        }
        if (body.expectedVersion() == null || body.expectedVersion() < 0) {
            throw new IdentityException(400, "版本号无效，请刷新后重试");
        }
        return new BrandProfileDraft(
                BrandProfileFields.brandName(body.brandName()),
                parseLogoReferenceId(body.brandLogoMediaReferenceId()),
                BrandProfileFields.description(body.description()),
                BrandProfileFields.industry(body.industry()),
                body.expectedVersion());
    }

    /** Logo 非空时 fail-closed 复验归属/可用性：404 → 400，上游故障 → 503（D7）。 */
    private Mono<Optional<String>> requireUsableLogoUrl(String orgId, String logoMediaReferenceId) {
        if (logoMediaReferenceId == null) {
            return Mono.just(Optional.empty());
        }
        return mediaClient.usableLogoUrl(logoMediaReferenceId, orgId)
                .map(Optional::of)
                .switchIfEmpty(Mono.error(
                        new IdentityException(400, "品牌 Logo 媒体不可用或类型不符")));
    }

    private String parseLogoReferenceId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new IdentityException(400, "品牌 Logo 媒体不可用或类型不符");
        }
    }

    /** 行存在且带 Logo 时解析展示 URL（fail-soft，失败/空置 null），资料本体始终可读。 */
    private Mono<Map<String, Object>> brandBody(OrganizationBrandProfile profile, String orgId) {
        if (profile.brandLogoMediaReferenceId() == null) {
            return Mono.just(toBody(profile, null));
        }
        return mediaClient.logoUrlFailSoft(profile.brandLogoMediaReferenceId(), orgId)
                .map(logoUrl -> toBody(profile, logoUrl))
                .defaultIfEmpty(toBody(profile, null));
    }

    private OrganizationBrandProfile emptyProfile(String orgId) {
        return new OrganizationBrandProfile(orgId, null, null, null, null, 0, null, null);
    }

    private Mono<Void> lockOrganization(String orgId) {
        return organizations.findByIdForUpdate(orgId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                .then();
    }

    /** 响应包装。用 LinkedHashMap 而非 {@code Map.of}——字段可空。 */
    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private Map<String, Object> toBody(OrganizationBrandProfile profile, String logoUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizationId", profile.organizationId());
        m.put("brandName", profile.brandName());
        m.put("brandLogoMediaReferenceId", profile.brandLogoMediaReferenceId());
        m.put("logoUrl", logoUrl);
        m.put("description", profile.description());
        m.put("industry", profile.industry());
        m.put("version", profile.version());
        return m;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 写请求：全字段可空（清空语义）；expectedVersion 必填（首次创建须为 0，D3）。 */
    public record UpdateBrandProfileRequest(
            String brandName,
            String brandLogoMediaReferenceId,
            String description,
            String industry,
            Integer expectedVersion
    ) {}

    public record CreateBrandLogoTicketRequest(
            String contentType,
            Long sizeBytes
    ) {}

    private record BrandProfileDraft(
            String brandName,
            String logoMediaReferenceId,
            String description,
            String industry,
            int expectedVersion
    ) {}
}
