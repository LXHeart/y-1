package com.grassland.identity.kyb;

import java.time.Instant;
import java.util.UUID;

/**
 * 商家附件（KYB 材料）。GL-P3-MERCHANT-001。
 *
 * <p>镜像 {@code merchant_attachment} 表全字段。引用 {@code intelligence.media_reference}，跨服务无 FK。
 */
public record MerchantAttachment(
        UUID id,
        String organizationId,
        String attachmentType,                // business_license / legal_person_id_front / legal_person_id_back / store_photo / other
        UUID mediaReferenceId,                // 引用 intelligence.media_reference
        String mimeType,                      // 快照：media 删除后仍可展示类型
        Long sizeBytes,                      // 快照：字节大小
        String ocrResult,                    // OCR 识别结果（JSONB）
        Instant uploadedAt,
        String uploadedByAccountId
) {}
