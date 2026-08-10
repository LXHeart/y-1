package com.grassland.identity.kyb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.store.StoreProfile;
import com.grassland.identity.store.StoreProfileRepository;
import java.util.ArrayList;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** 管理员 KYB 审核详情、证据快照和受控下载。 */
@Service
public class KybReviewDetailService {

    private final MerchantProfileRepository merchantProfiles;
    private final MerchantAttachmentRepository attachments;
    private final WithdrawalAccountRepository withdrawalAccounts;
    private final StoreProfileRepository storeProfiles;
    private final KybFieldCrypto crypto;
    private final KybEvidenceService evidence;
    private final KybMediaClient mediaClient;
    private final ObjectMapper json = new ObjectMapper();

    public KybReviewDetailService(
            MerchantProfileRepository merchantProfiles,
            MerchantAttachmentRepository attachments,
            WithdrawalAccountRepository withdrawalAccounts,
            StoreProfileRepository storeProfiles,
            KybFieldCrypto crypto,
            KybEvidenceService evidence,
            KybMediaClient mediaClient) {
        this.merchantProfiles = merchantProfiles;
        this.attachments = attachments;
        this.withdrawalAccounts = withdrawalAccounts;
        this.storeProfiles = storeProfiles;
        this.crypto = crypto;
        this.evidence = evidence;
        this.mediaClient = mediaClient;
    }

    public Mono<ReviewDetail> load(KybVerificationRequest request) {
        return switch (KybVerificationType.fromDb(request.verificationType())) {
            case MERCHANT_PROFILE -> snapshotAttachments(request)
                    .flatMap(items -> evidence.requireCurrent(request.organizationId(), items)
                            .then(merchantSubject(request))
                            .map(subject -> new ReviewDetail(subject,
                                    items.stream().map(this::attachmentSummary).toList())));
            case WITHDRAWAL_ACCOUNT -> withdrawalSubject(request)
                    .map(subject -> new ReviewDetail(subject, List.of()));
            case STORE_PROFILE -> storeSubject(request)
                    .map(subject -> new ReviewDetail(subject, List.of()));
        };
    }

    public Mono<Void> requireCurrentEvidence(KybVerificationRequest request) {
        if (KybVerificationType.fromDb(request.verificationType()) != KybVerificationType.MERCHANT_PROFILE) {
            return Mono.empty();
        }
        return snapshotAttachments(request)
                .flatMap(items -> evidence.requireCurrent(request.organizationId(), items));
    }

    public int evidenceCount(KybVerificationRequest request) {
        return KybVerificationType.fromDb(request.verificationType()) == KybVerificationType.MERCHANT_PROFILE
                ? parseMaterials(request.materials()).size() : 0;
    }

    public Mono<KybMediaDownload> issueDownload(KybVerificationRequest request, UUID attachmentId) {
        if (KybVerificationType.fromDb(request.verificationType()) != KybVerificationType.MERCHANT_PROFILE) {
            return Mono.error(new IdentityException(404, "审核材料不存在"));
        }
        return snapshotAttachments(request)
                .flatMap(items -> Mono.justOrEmpty(items.stream()
                                .filter(item -> item.id().equals(attachmentId)).findFirst())
                        .switchIfEmpty(Mono.error(new IdentityException(404, "审核材料不存在"))))
                .flatMap(item -> mediaClient.issueDownloadUrl(
                        item.mediaReferenceId(), request.organizationId()));
    }

    private Mono<Map<String, Object>> merchantSubject(KybVerificationRequest request) {
        if (request.targetId() == null
                || !request.organizationId().equals(request.targetId().toString())) {
            return targetChanged();
        }
        return merchantProfiles.findById(request.organizationId())
                .switchIfEmpty(targetChanged())
                .map(profile -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("type", KybVerificationType.MERCHANT_PROFILE.dbValue());
                    body.put("organizationId", profile.organizationId());
                    body.put("legalName", profile.legalName());
                    body.put("unifiedSocialCreditCode", profile.unifiedSocialCreditCode());
                    body.put("businessType", profile.businessType());
                    body.put("legalPersonName", profile.legalPersonName());
                    body.put("legalPersonIdNumberMasked", crypto.maskTail4(profile.legalPersonIdNumber()));
                    body.put("registeredCapitalCents", profile.registeredCapitalCents());
                    body.put("establishmentDate", profile.establishmentDate() == null
                            ? null : profile.establishmentDate().toString());
                    body.put("businessAddress", profile.businessAddress());
                    body.put("contactPhone", profile.contactPhone());
                    body.put("contactEmail", profile.contactEmail());
                    body.put("status", profile.status());
                    return body;
                });
    }

    private Mono<Map<String, Object>> withdrawalSubject(KybVerificationRequest request) {
        if (request.targetId() == null) {
            return targetChanged();
        }
        return withdrawalAccounts.findByIdAndOrganization(request.targetId(), request.organizationId())
                .switchIfEmpty(targetChanged())
                .map(account -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("type", KybVerificationType.WITHDRAWAL_ACCOUNT.dbValue());
                    body.put("id", account.id());
                    body.put("organizationId", account.organizationId());
                    body.put("accountType", account.accountType());
                    body.put("accountName", account.accountName());
                    body.put("accountNumberMasked", crypto.maskTail4(account.accountNumberEncrypted()));
                    body.put("bankName", account.bankName());
                    body.put("branchName", account.branchName());
                    body.put("status", account.status());
                    return body;
                });
    }

    private Mono<Map<String, Object>> storeSubject(KybVerificationRequest request) {
        if (request.targetId() == null) {
            return targetChanged();
        }
        return storeProfiles.findByOrganizationAndId(
                        request.organizationId(), request.targetId().toString())
                .switchIfEmpty(targetChanged())
                .map(this::storeBody);
    }

    private Map<String, Object> storeBody(StoreProfile profile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", KybVerificationType.STORE_PROFILE.dbValue());
        body.put("storeId", profile.storeId());
        body.put("address", profile.address());
        body.put("phone", profile.phone());
        body.put("businessHours", profile.businessHours());
        body.put("description", profile.description());
        body.put("status", profile.status());
        return body;
    }

    private Mono<List<MerchantAttachment>> snapshotAttachments(KybVerificationRequest request) {
        List<MaterialSnapshot> snapshot = parseMaterials(request.materials());
        if (snapshot.isEmpty()) {
            return Mono.error(new IdentityException(409, "审核材料快照为空"));
        }
        if (new HashSet<>(snapshot.stream().map(MaterialSnapshot::id).toList()).size() != snapshot.size()) {
            return Mono.error(new IdentityException(409, "审核材料快照无效"));
        }
        return attachments.findByOrganization(request.organizationId()).collectList()
                .flatMap(current -> {
                    Map<UUID, MerchantAttachment> byId = new LinkedHashMap<>();
                    current.forEach(item -> byId.put(item.id(), item));
                    List<MerchantAttachment> ordered = snapshot.stream()
                            .map(item -> item.complete()
                                    ? item.toAttachment(request.organizationId())
                                    : byId.get(item.id()))
                            .toList();
                    if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
                        return Mono.error(new IdentityException(409, "审核材料已缺失"));
                    }
                    return Mono.just(ordered);
                });
    }

    private List<MaterialSnapshot> parseMaterials(String materials) {
        if (materials == null || materials.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = json.readTree(materials);
            if (!root.isArray()) {
                throw new IllegalArgumentException("materials is not an array");
            }
            List<MaterialSnapshot> snapshots = new ArrayList<>();
            for (JsonNode item : root) {
                if (item.isTextual()) {
                    snapshots.add(MaterialSnapshot.legacy(UUID.fromString(item.textValue())));
                    continue;
                }
                if (!item.isObject()) {
                    throw new IllegalArgumentException("material snapshot is invalid");
                }
                JsonNode size = item.get("sizeBytes");
                JsonNode uploadedAt = item.get("uploadedAt");
                snapshots.add(new MaterialSnapshot(
                        UUID.fromString(requiredText(item, "id")),
                        requiredText(item, "attachmentType"),
                        UUID.fromString(requiredText(item, "mediaReferenceId")),
                        optionalText(item, "mimeType"),
                        size == null || size.isNull() ? null : size.longValue(),
                        uploadedAt == null || uploadedAt.isNull()
                                ? null : Instant.parse(uploadedAt.textValue()),
                        requiredText(item, "uploadedByAccountId")));
            }
            return List.copyOf(snapshots);
        } catch (Exception error) {
            throw new IdentityException(409, "审核材料快照无效");
        }
    }

    private static String requiredText(JsonNode object, String field) {
        String value = optionalText(object, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value;
    }

    private static String optionalText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()
                ? null : value.textValue();
    }

    private Map<String, Object> attachmentSummary(MerchantAttachment attachment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", attachment.id());
        body.put("attachmentType", attachment.attachmentType());
        body.put("mimeType", attachment.mimeType());
        body.put("sizeBytes", attachment.sizeBytes());
        body.put("uploadedAt", attachment.uploadedAt() == null ? null : attachment.uploadedAt().toString());
        body.put("ocrStatus", attachment.ocrStatus());
        body.put("ocrResult", attachment.ocrResult());
        body.put("ocrProvider", attachment.ocrProvider());
        body.put("ocrModel", attachment.ocrModel());
        body.put("ocrAnalyzedAt", attachment.ocrAnalyzedAt() == null
                ? null : attachment.ocrAnalyzedAt().toString());
        body.put("ocrFailureCode", attachment.ocrFailureCode());
        return body;
    }

    private static <T> Mono<T> targetChanged() {
        return Mono.error(new IdentityException(409, "审核目标不存在或状态已变化"));
    }

    public record ReviewDetail(Map<String, Object> subject,
                               List<Map<String, Object>> attachments) {}

    private record MaterialSnapshot(
            UUID id,
            String attachmentType,
            UUID mediaReferenceId,
            String mimeType,
            Long sizeBytes,
            Instant uploadedAt,
            String uploadedByAccountId) {

        static MaterialSnapshot legacy(UUID id) {
            return new MaterialSnapshot(id, null, null, null, null, null, null);
        }

        boolean complete() {
            return attachmentType != null && mediaReferenceId != null && uploadedByAccountId != null;
        }

        MerchantAttachment toAttachment(String organizationId) {
            return new MerchantAttachment(id, organizationId, attachmentType, mediaReferenceId,
                    mimeType, sizeBytes, null, "pending", null, null, null, null, null,
                    uploadedAt, uploadedByAccountId);
        }
    }
}
