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
                    + " ocr_result::text, uploaded_at, uploaded_by_account_id::text";

    private final DatabaseClient db;

    public MerchantAttachmentRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建附件记录。*/
    public Mono<MerchantAttachment> create(String organizationId, String attachmentType,
                                           UUID mediaReferenceId, String mimeType, Long sizeBytes,
                                           String uploadedByAccountId) {
        UUID id = UUID.randomUUID();
        return db.sql("""
                INSERT INTO merchant_attachment(id, organization_id, attachment_type, media_reference_id,
                        mime_type, size_bytes, uploaded_by_account_id)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), :type, CAST(:mediaRef AS uuid),
                        :mime, :size, CAST(:uploadedBy AS uuid))
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("type", attachmentType)
                .bind("mediaRef", mediaReferenceId).bind("mime", mimeType)
                .bind("size", sizeBytes != null ? sizeBytes : 0L).bind("uploadedBy", uploadedByAccountId)
                .map(MerchantAttachmentRepository::map).one();
    }

    /** 查询附件。*/
    public Mono<MerchantAttachment> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM merchant_attachment WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
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

    /** 删除附件。*/
    public Mono<Long> deleteById(UUID id) {
        return db.sql("DELETE FROM merchant_attachment WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .fetch().rowsUpdated();
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
                toInstant(row.get("uploaded_at", OffsetDateTime.class)),
                row.get("uploaded_by_account_id", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
