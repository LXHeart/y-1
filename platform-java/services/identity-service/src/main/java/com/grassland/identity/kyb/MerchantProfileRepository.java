package com.grassland.identity.kyb;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * merchant_profile 数据访问（R2DBC {@link DatabaseClient} 手写 SQL）。
 * GL-P3-MERCHANT-001。
 */
@Component
public class MerchantProfileRepository {

    private static final String SELECT_COLS =
            "organization_id::text, legal_name, unified_social_credit_code, business_type, legal_person_name,"
                    + " legal_person_id_number, registered_capital_cents, establishment_date, business_address::text,"
                    + " contact_phone, contact_email, status, submitted_at, reviewed_at, reviewer_account_id::text,"
                    + " review_note, created_at, updated_at";

    private final DatabaseClient db;

    public MerchantProfileRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建或更新商家资料（upsert，基于 organization_id）。*/
    public Mono<MerchantProfile> upsert(
            String organizationId, String legalName, String unifiedSocialCreditCode, String businessType,
            String legalPersonName, String legalPersonIdNumber, Long registeredCapitalCents,
            LocalDate establishmentDate, String businessAddress, String contactPhone, String contactEmail,
            String status, Instant submittedAt, Instant reviewedAt, String reviewerAccountId, String reviewNote) {
        var spec = db.sql("""
                INSERT INTO merchant_profile(organization_id, legal_name, unified_social_credit_code, business_type,
                        legal_person_name, legal_person_id_number, registered_capital_cents, establishment_date,
                        business_address, contact_phone, contact_email, status, submitted_at, reviewed_at,
                        reviewer_account_id, review_note)
                VALUES (CAST(:org AS uuid), :legalName, :uscc, :businessType, :legalPersonName, :legalPersonId,
                        :capitalCents, :establishDate, CAST(:businessAddr AS jsonb), :phone, :email,
                        :status, :submittedAt, :reviewedAt, CAST(:reviewer AS uuid), :reviewNote)
                ON CONFLICT (organization_id) DO UPDATE SET
                    legal_name = EXCLUDED.legal_name,
                    unified_social_credit_code = EXCLUDED.unified_social_credit_code,
                    business_type = EXCLUDED.business_type,
                    legal_person_name = EXCLUDED.legal_person_name,
                    legal_person_id_number = EXCLUDED.legal_person_id_number,
                    registered_capital_cents = EXCLUDED.registered_capital_cents,
                    establishment_date = EXCLUDED.establishment_date,
                    business_address = EXCLUDED.business_address,
                    contact_phone = EXCLUDED.contact_phone,
                    contact_email = EXCLUDED.contact_email,
                    status = EXCLUDED.status,
                    submitted_at = EXCLUDED.submitted_at,
                    reviewed_at = EXCLUDED.reviewed_at,
                    reviewer_account_id = EXCLUDED.reviewer_account_id,
                    review_note = EXCLUDED.review_note,
                    updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("org", organizationId);
        spec = bindNullable(spec, "legalName", legalName);
        spec = bindNullable(spec, "uscc", unifiedSocialCreditCode);
        spec = bindNullable(spec, "businessType", businessType);
        spec = bindNullable(spec, "legalPersonName", legalPersonName);
        spec = bindNullable(spec, "legalPersonId", legalPersonIdNumber);
        spec = bindNullable(spec, "capitalCents", registeredCapitalCents);
        spec = bindNullableDate(spec, "establishDate", establishmentDate);
        spec = bindNullable(spec, "businessAddr", businessAddress);
        spec = bindNullable(spec, "phone", contactPhone);
        spec = bindNullable(spec, "email", contactEmail);
        spec = bind(spec, "status", status != null ? status : "draft");
        spec = bindNullable(spec, "submittedAt", submittedAt);
        spec = bindNullable(spec, "reviewedAt", reviewedAt);
        spec = bindNullable(spec, "reviewer", reviewerAccountId);
        spec = bindNullable(spec, "reviewNote", reviewNote);
        return spec.map(MerchantProfileRepository::map).one();
    }

    private static GenericExecuteSpec bindNullableDate(GenericExecuteSpec spec, String name, LocalDate value) {
        return (value == null) ? spec.bindNull(name, LocalDate.class) : spec.bind(name, value);
    }

    /** 查询商家资料。*/
    public Mono<MerchantProfile> findById(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM merchant_profile WHERE organization_id = CAST(:id AS uuid)")
                .bind("id", organizationId)
                .map(MerchantProfileRepository::map).one();
    }

    /** 更新状态（draft→pending, under_review→approved/rejected）。*/
    public Mono<MerchantProfile> updateStatus(String organizationId, String status,
                                               Instant submittedAt, Instant reviewedAt,
                                               String reviewerAccountId, String reviewNote) {
        var spec = db.sql("""
                UPDATE merchant_profile
                SET status = :status, submitted_at = :submittedAt, reviewed_at = :reviewedAt,
                    reviewer_account_id = CAST(:reviewer AS uuid), review_note = :reviewNote, updated_at = now()
                WHERE organization_id = CAST(:id AS uuid)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", organizationId).bind("status", status);
        spec = bindNullable(spec, "submittedAt", submittedAt);
        spec = bindNullable(spec, "reviewedAt", reviewedAt);
        spec = bindNullable(spec, "reviewer", reviewerAccountId);
        spec = bindNullable(spec, "reviewNote", reviewNote);
        return spec.map(MerchantProfileRepository::map).one();
    }

    /** Admin 审核队列：列 pending/under_review。*/
    public Flux<MerchantProfile> findPending() {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM merchant_profile WHERE status IN ('pending', 'under_review') ORDER BY submitted_at")
                .map(MerchantProfileRepository::map).all();
    }

    private static MerchantProfile map(Readable row) {
        return new MerchantProfile(
                row.get("organization_id", String.class),
                row.get("legal_name", String.class),
                row.get("unified_social_credit_code", String.class),
                row.get("business_type", String.class),
                row.get("legal_person_name", String.class),
                row.get("legal_person_id_number", String.class),
                row.get("registered_capital_cents", Long.class),
                toLocalDate(row.get("establishment_date", LocalDate.class)),
                row.get("business_address", String.class),
                row.get("contact_phone", String.class),
                row.get("contact_email", String.class),
                row.get("status", String.class),
                toInstant(row.get("submitted_at", OffsetDateTime.class)),
                toInstant(row.get("reviewed_at", OffsetDateTime.class)),
                row.get("reviewer_account_id", String.class),
                row.get("review_note", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static LocalDate toLocalDate(LocalDate value) {
        return value; // LocalDate 无需转换
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, Long value) {
        return (value == null) ? spec.bindNull(name, Long.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, Instant value) {
        if (value == null) {
            return spec.bindNull(name, OffsetDateTime.class);
        }
        return spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, LocalDate value) {
        return (value == null) ? spec.bindNull(name, LocalDate.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bind(GenericExecuteSpec spec, String name, String value) {
        return spec.bind(name, value);
    }
}
