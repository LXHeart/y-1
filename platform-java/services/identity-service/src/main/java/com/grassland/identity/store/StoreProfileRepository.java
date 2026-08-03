package com.grassland.identity.store;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * store_profile 数据访问（R2DBC {@link DatabaseClient} 手写 SQL）。
 * GL-P3-MERCHANT-001。
 */
@Component
public class StoreProfileRepository {

    private static final String SELECT_COLS =
            "store_id::text, address::text, phone, business_hours::text, description, status, created_at, updated_at";

    private final DatabaseClient db;

    public StoreProfileRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建或更新门店资料（upsert，基于 store_id）。*/
    public Mono<StoreProfile> upsert(String storeId, String address, String phone,
                                      String businessHours, String description, String status) {
        var spec = db.sql("""
                INSERT INTO store_profile(store_id, address, phone, business_hours, description, status)
                VALUES (CAST(:id AS uuid), CAST(:addr AS jsonb), :phone, CAST(:hours AS jsonb), :desc, :status)
                ON CONFLICT (store_id) DO UPDATE SET
                    address = EXCLUDED.address,
                    phone = EXCLUDED.phone,
                    business_hours = EXCLUDED.business_hours,
                    description = EXCLUDED.description,
                    status = EXCLUDED.status,
                    updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", storeId);
        spec = bindNullable(spec, "addr", address);
        spec = bindNullable(spec, "phone", phone);
        spec = bindNullable(spec, "hours", businessHours);
        spec = bindNullable(spec, "desc", description);
        spec = bind(spec, "status", status != null ? status : "active");
        return spec.map(StoreProfileRepository::map).one();
    }

    /** 查询门店资料。*/
    public Mono<StoreProfile> findById(String storeId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM store_profile WHERE store_id = CAST(:id AS uuid)")
                .bind("id", storeId)
                .map(StoreProfileRepository::map).one();
    }

    /** 列出组织下所有门店资料。*/
    public Flux<StoreProfile> findByOrganization(String organizationId) {
        return db.sql("""
                SELECT sp.%s FROM store_profile sp
                INNER JOIN store s ON s.id = sp.store_id
                WHERE s.organization_id = CAST(:org AS uuid) ORDER BY sp.created_at
                """.formatted(SELECT_COLS.replace("store_id::text", "sp.store_id::text")
                        .replace("address::text", "sp.address::text").replace("business_hours::text", "sp.business_hours::text")))
                .bind("org", organizationId)
                .map(StoreProfileRepository::map).all();
    }

    private static StoreProfile map(Readable row) {
        return new StoreProfile(
                row.get("store_id", String.class),
                row.get("address", String.class),
                row.get("phone", String.class),
                row.get("business_hours", String.class),
                row.get("description", String.class),
                row.get("status", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bind(GenericExecuteSpec spec, String name, String value) {
        return spec.bind(name, value);
    }
}
