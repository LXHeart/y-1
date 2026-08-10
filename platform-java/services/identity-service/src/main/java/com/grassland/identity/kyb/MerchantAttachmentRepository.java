package com.grassland.identity.kyb;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * merchant_attachment 数据访问（R2DBC {@link DatabaseClient} 手写 SQL）。
 * GL-P3-MERCHANT-001。
 */
@Component
public class MerchantAttachmentRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, attachment_type, media_reference_id::text, mime_type, size_bytes,"
                    + " ocr_result::text, ocr_status, ocr_provider, ocr_model, ocr_result_version,"
                    + " ocr_analyzed_at, ocr_failure_code, uploaded_at, uploaded_by_account_id::text";

    private final DatabaseClient db;

    public MerchantAttachmentRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建附件记录。*/
    public Mono<MerchantAttachment> create(String organizationId, String attachmentType,
                                           UUID mediaReferenceId, String mimeType, Long sizeBytes,
                                           String uploadedByAccountId) {
        return create(UUID.randomUUID(), organizationId, attachmentType, mediaReferenceId,
                mimeType, sizeBytes, uploadedByAccountId);
    }

    /**
     * 创建指定 ID 的附件记录。
     *
     * <p>附件 ID 同时作为 intelligence 的 retention token，因此必须由调用方在绑定媒体前生成并传入，
     * 才能让「媒体留存」与「附件记录」引用同一个不可变标识。
     */
    public Mono<MerchantAttachment> create(UUID id, String organizationId, String attachmentType,
                                           UUID mediaReferenceId, String mimeType, Long sizeBytes,
                                           String uploadedByAccountId) {
        return db.sql("""
                INSERT INTO merchant_attachment(id, organization_id, attachment_type, media_reference_id,
                        mime_type, size_bytes, uploaded_by_account_id, ocr_status)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), :type, CAST(:mediaRef AS uuid),
                        :mime, :size, CAST(:uploadedBy AS uuid), :ocrStatus)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("type", attachmentType)
                .bind("mediaRef", mediaReferenceId).bind("mime", mimeType)
                .bind("size", sizeBytes != null ? sizeBytes : 0L).bind("uploadedBy", uploadedByAccountId)
                .bind("ocrStatus", MerchantAttachmentType.fromDb(attachmentType).isDocumentType()
                        ? "pending" : "not_applicable")
                .map(MerchantAttachmentRepository::map).one();
    }

    /** 查询附件。*/
    public Mono<MerchantAttachment> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM merchant_attachment WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(MerchantAttachmentRepository::map).one();
    }

    public Mono<MerchantAttachment> findByIdAndOrganization(UUID id, String organizationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM merchant_attachment"
                        + " WHERE id = CAST(:id AS uuid) AND organization_id = CAST(:org AS uuid)")
                .bind("id", id).bind("org", organizationId)
                .map(MerchantAttachmentRepository::map).one();
    }

    public Mono<MerchantAttachment> findByOrganizationAndMediaReference(
            String organizationId, UUID mediaReferenceId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM merchant_attachment"
                        + " WHERE organization_id = CAST(:org AS uuid)"
                        + " AND media_reference_id = CAST(:media AS uuid)"
                        + " AND attachment_type IN ('business_license','legal_person_id_front','legal_person_id_back',"
                        + " 'industry_license','financial_qualification')")
                .bind("org", organizationId).bind("media", mediaReferenceId)
                .map(MerchantAttachmentRepository::map).one();
    }

    /** 列出组织下所有附件。*/
    public Flux<MerchantAttachment> findByOrganization(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM merchant_attachment WHERE organization_id = CAST(:org AS uuid) ORDER BY uploaded_at DESC")
                .bind("org", organizationId)
                .map(MerchantAttachmentRepository::map).all();
    }

    /** 查询组织下指定类型的附件（证件类唯一约束校验）。*/
    public Mono<MerchantAttachment> findByOrganizationAndType(String organizationId, String attachmentType) {
        return db.sql("""
                SELECT %s FROM merchant_attachment
                WHERE organization_id = CAST(:org AS uuid) AND attachment_type = :type
                """.formatted(SELECT_COLS))
                .bind("org", organizationId).bind("type", attachmentType)
                .map(MerchantAttachmentRepository::map).one();
    }

    /**
     * 删除附件，**按 org 作用域**。GL-P3-MERCHANT-001。
     *
     * <p>刻意不提供无 org 限定的 `deleteById`：路径上的 orgId 已由 `OrgAuthorization` 校验过角色，
     * 但附件 id 是独立 UUID——只按 id 删会让 A 商家的 ADMIN 猜到 id 就能删掉 B 商家的营业执照
     * （跨租户删除）。谓词必须同时命中 org。
     */
    public Mono<Long> deleteByIdAndOrganization(UUID id, String organizationId) {
        return db.sql("DELETE FROM merchant_attachment WHERE id = CAST(:id AS uuid)"
                + " AND organization_id = CAST(:org AS uuid)")
                .bind("id", id).bind("org", organizationId)
                .fetch().rowsUpdated();
    }

    /** 列出组织已上传的证件类附件类型（submit 前的材料齐备校验）。*/
    public Flux<String> findDocumentTypes(String organizationId) {
        return db.sql("SELECT DISTINCT attachment_type FROM merchant_attachment"
                + " WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", organizationId)
                .map(row -> row.get("attachment_type", String.class)).all();
    }

    /** 列出组织下附件 id（提交审核时快照进 materials）。*/
    public Flux<UUID> findIdsByOrganization(String organizationId) {
        return db.sql("SELECT id::text FROM merchant_attachment"
                + " WHERE organization_id = CAST(:org AS uuid) ORDER BY uploaded_at")
                .bind("org", organizationId)
                .map(row -> UUID.fromString(row.get("id", String.class))).all();
    }

    /** 权限补充证照一旦被开放申请快照引用，就不能删除或替换。 */
    public Mono<Boolean> isReferencedByOpenPermissionRequest(String organizationId, UUID attachmentId) {
        String attachmentJson = "[\"" + attachmentId + "\"]";
        return db.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM merchant_permission_request
                    WHERE organization_id = CAST(:org AS uuid)
                      AND status IN ('pending', 'under_review')
                      AND attachment_ids @> CAST(:attachmentJson AS jsonb)
                ) AS referenced
                """)
                .bind("org", organizationId)
                .bind("attachmentJson", attachmentJson)
                .map(row -> Boolean.TRUE.equals(row.get("referenced", Boolean.class)))
                .one().defaultIfEmpty(false);
    }

    private static MerchantAttachment map(Readable row) {
        return new MerchantAttachment(
                UUID.fromString(row.get("id", String.class)),
                row.get("organization_id", String.class),
                row.get("attachment_type", String.class),
                UUID.fromString(row.get("media_reference_id", String.class)),
                row.get("mime_type", String.class),
                row.get("size_bytes", Long.class),
                row.get("ocr_result", String.class),
                row.get("ocr_status", String.class),
                row.get("ocr_provider", String.class),
                row.get("ocr_model", String.class),
                row.get("ocr_result_version", Integer.class),
                toInstant(row.get("ocr_analyzed_at", OffsetDateTime.class)),
                row.get("ocr_failure_code", String.class),
                toInstant(row.get("uploaded_at", OffsetDateTime.class)),
                row.get("uploaded_by_account_id", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
