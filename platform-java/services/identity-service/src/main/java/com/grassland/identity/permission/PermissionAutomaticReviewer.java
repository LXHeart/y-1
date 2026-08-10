package com.grassland.identity.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.kyb.MerchantAttachment;
import com.grassland.identity.kyb.MerchantAttachmentRepository;
import com.grassland.identity.organization.PermissionTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Deterministic admission pre-check. OCR/provider output is advisory; a human remains the authority
 * for finance and regulated industries. This class never grants a tier by itself.
 */
@Component
public class PermissionAutomaticReviewer {

    private final MerchantAttachmentRepository attachments;
    private final ObjectMapper mapper = new ObjectMapper();

    public PermissionAutomaticReviewer(MerchantAttachmentRepository attachments) {
        this.attachments = attachments;
    }

    public Mono<PermissionAutoReview> evaluate(String organizationId, PermissionTier tier, Industry industry,
                                               List<String> attachmentIds) {
        List<UUID> ids = parseIds(attachmentIds);
        if (ids.isEmpty()) {
            return Mono.just(PermissionAutoReview.manual("needs_review", risk(tier, industry),
                    json(Map.of("reason", "attachments_not_submitted", "humanReviewRequired", true))));
        }
        return attachments.findByOrganization(organizationId)
                .filter(item -> ids.contains(item.id()))
                .collectList()
                .flatMap(found -> {
                    if (found.size() != ids.size()) {
                        return Mono.error(new IllegalArgumentException("附件不存在或不属于该组织"));
                    }
                    List<String> pending = new ArrayList<>();
                    List<String> failed = new ArrayList<>();
                    List<String> needsReview = new ArrayList<>();
                    List<String> requiredDocumentTypes = new ArrayList<>();
                    requiredDocumentTypes.add("business_license");
                    if (tier == PermissionTier.FINANCE_TRANSACTION) {
                        requiredDocumentTypes.addAll(List.of("legal_person_id_front", "legal_person_id_back",
                                "financial_qualification"));
                    }
                    if (industry.requiresIndustryLicense()) {
                        requiredDocumentTypes.add("industry_license");
                    }
                    for (String required : requiredDocumentTypes) {
                        if (found.stream().noneMatch(item -> required.equals(item.attachmentType()))) {
                            failed.add(required + ":missing");
                        }
                    }
                    for (MerchantAttachment attachment : found) {
                        // Extra KYB evidence must not block a narrower permission request. For example,
                        // a pending ID-card OCR is irrelevant to BASIC_PUBLISH once the business licence passed.
                        if (!requiredDocumentTypes.contains(attachment.attachmentType())) {
                            continue;
                        }
                        if ("pending".equals(attachment.ocrStatus()) || "processing".equals(attachment.ocrStatus())) {
                            pending.add(attachment.attachmentType());
                        } else if ("failed".equals(attachment.ocrStatus())) {
                            failed.add(attachment.attachmentType());
                        } else if ("needs_review".equals(attachment.ocrStatus())) {
                            needsReview.add(attachment.attachmentType());
                        } else if (!"passed".equals(attachment.ocrStatus())) {
                            needsReview.add(attachment.attachmentType() + ":not_verified");
                        }
                    }
                    String status = !failed.isEmpty() ? "failed"
                            : !pending.isEmpty() ? "pending"
                            : !needsReview.isEmpty() ? "needs_review" : "passed";
                    boolean manual = tier == PermissionTier.FINANCE_TRANSACTION
                            || industry.requiresIndustryLicense() || industry.isRestricted();
                    if (manual && "passed".equals(status)) {
                        status = "needs_review";
                    }
                    String mode = !manual && "passed".equals(status) ? "auto_recommendation" : "manual";
                    return Mono.just(new PermissionAutoReview(status, mode, risk(tier, industry),
                            json(Map.of("attachmentCount", found.size(), "pendingTypes", pending,
                                    "failedTypes", failed, "needsReviewTypes", needsReview,
                                    "humanReviewRequired", manual,
                                    "boundary", "OCR is advisory; finance and regulated industries require human review"))));
                });
    }

    private static List<UUID> parseIds(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream().filter(id -> id != null && !id.isBlank()).map(id -> {
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("附件 ID 无效: " + id);
            }
        }).distinct().toList();
    }

    private static String risk(PermissionTier tier, Industry industry) {
        if (industry.isProhibited() || tier == PermissionTier.FINANCE_TRANSACTION) return "high";
        if (industry.isRestricted()) return "elevated";
        return "standard";
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("自动核验结果序列化失败", e);
        }
    }
}
