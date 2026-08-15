package com.grassland.finance.aicredits;

import com.grassland.finance.security.FinanceException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 积分包 SKU 仓储（AI 套餐 v1 Slice A）。
 *
 * <p>版本化语义（「配置不篡改历史」，镜像 marketplace package_version 范式）：
 * {@code credits_package} 是运营可变壳，价格/面值住在不可变的 {@code credits_package_version}；
 * 调价 = 追加新版本行并原子切换 current 指针，历史版本永不被 UPDATE。
 */
@Component
public class CreditsPackageRepository {

    /** 状态机：draft→active、active→retired、retired→active（重新上架）；其余迁移 409。 */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "draft", Set.of("active"),
            "active", Set.of("retired"),
            "retired", Set.of("active"));

    private final DatabaseClient db;
    private final TransactionalOperator transactions;

    public CreditsPackageRepository(DatabaseClient db, TransactionalOperator transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    public Flux<PackageView> listAll() {
        return query("TRUE").map(PackageView::fromRow).all();
    }

    public Flux<PackageView> listActive() {
        return query("p.status = 'active'").map(PackageView::fromRow).all();
    }

    public Mono<PackageView> findById(String packageId) {
        return query("p.id = :packageId::uuid")
                .bind("packageId", packageId)
                .map(PackageView::fromRow)
                .one();
    }

    /** 创建：package + v1 version + current 指针回填，单事务。 */
    public Mono<PackageView> create(String name, String description, long priceCents,
                                    int creditsAmount, String note) {
        UUID packageId = UUID.randomUUID();
        Mono<PackageView> work = db.sql("""
                        INSERT INTO credits_package(id, name, description, status)
                        VALUES (:id, :name, :description, 'draft')
                        """)
                .bind("id", packageId)
                .bind("name", name)
                .bind("description", description)
                .then()
                .then(insertVersion(packageId, 1, priceCents, creditsAmount, note)
                        .then(bindCurrent(packageId.toString())))
                .then(findViewById(packageId.toString()));
        return transactions.transactional(work);
    }

    /** 调价：仅 draft/active 可调；追加 v(n+1) 并切 current，单事务。 */
    public Mono<PackageView> newVersion(String packageId, long priceCents, int creditsAmount, String note) {
        Mono<PackageView> work = requireEditable(packageId)
                .flatMap(pkg -> db.sql("""
                                SELECT coalesce(max(version), 0) + 1 AS next FROM credits_package_version
                                WHERE package_id = :packageId::uuid
                                """)
                        .bind("packageId", packageId)
                        .map(row -> row.get("next", Long.class)).one()
                        .flatMap(next -> insertVersion(UUID.fromString(packageId), next, priceCents,
                                        creditsAmount, note)
                                .then(bindCurrent(packageId.toString())))
                        .then(findViewById(packageId)));
        return transactions.transactional(work);
    }

    public Mono<PackageView> setStatus(String packageId, String target) {
        Mono<PackageView> work = findViewByIdRow(packageId)
                .flatMap(current -> {
                    if (!ALLOWED_TRANSITIONS.getOrDefault(current.status(), Set.of()).contains(target)) {
                        return Mono.error(new FinanceException(409,
                                "积分包状态不允许从 " + current.status() + " 变更为 " + target));
                    }
                    return db.sql("UPDATE credits_package SET status = :status, updated_at = now() WHERE id = :id::uuid")
                            .bind("status", target)
                            .bind("id", packageId)
                            .then()
                            .then(findViewById(packageId.toString()));
                });
        return transactions.transactional(work);
    }

    // ---------------- helpers ----------------

    private DatabaseClient.GenericExecuteSpec query(String condition) {
        return db.sql("""
                        SELECT p.id::text AS package_id, p.name, p.description, p.status,
                               v.id::text AS version_id, v.version, v.price_cents, v.credits_amount, v.note
                        FROM credits_package p
                        JOIN credits_package_version v ON v.id = p.current_version_id
                        WHERE """ + " " + condition + " ORDER BY p.created_at DESC");
    }

    private Mono<Void> insertVersion(UUID packageId, long version, long priceCents,
                                     int creditsAmount, String note) {
        return db.sql("""
                        INSERT INTO credits_package_version(id, package_id, version, price_cents, credits_amount, note)
                        VALUES (gen_random_uuid(), :packageId, :version, :priceCents, :creditsAmount, :note)
                        """)
                .bind("packageId", packageId)
                .bind("version", version)
                .bind("priceCents", priceCents)
                .bind("creditsAmount", creditsAmount)
                .bind("note", note)
                .then();
    }

    private Mono<Void> bindCurrent(String packageId) {
        return db.sql("""
                        UPDATE credits_package SET current_version_id =
                              (SELECT id FROM credits_package_version
                               WHERE package_id = :packageId::uuid ORDER BY version DESC LIMIT 1),
                              updated_at = now()
                        WHERE id = :packageId::uuid
                        """)
                .bind("packageId", packageId)
                .then();
    }

    private Mono<PackageView> findViewById(String packageId) {
        return query("p.id = :packageId::uuid")
                .bind("packageId", packageId)
                .map(PackageView::fromRow)
                .one();
    }

    private Mono<PackageRow> findViewByIdRow(String packageId) {
        return db.sql("SELECT id::text AS id, status FROM credits_package WHERE id = :packageId::uuid")
                .bind("packageId", packageId)
                .map(row -> new PackageRow(row.get("id", String.class), row.get("status", String.class)))
                .one();
    }

    private Mono<PackageRow> requireEditable(String packageId) {
        return findViewByIdRow(packageId)
                .switchIfEmpty(Mono.error(new FinanceException(404, "积分包不存在")))
                .map(pkg -> {
                    if ("retired".equals(pkg.status())) {
                        throw new FinanceException(409, "已下架积分包不能调价，请先重新上架");
                    }
                    return pkg;
                });
    }

    private record PackageRow(String id, String status) {}

    /** 积分包对外视图：壳字段 + current 版本的价格/面值。 */
    public record PackageView(
            String id, String versionId, String name, String description, String status,
            long version, long priceCents, int creditsAmount, String note) {

        static PackageView fromRow(io.r2dbc.spi.Readable row) {
            return new PackageView(
                    row.get("package_id", String.class),
                    row.get("version_id", String.class),
                    row.get("name", String.class),
                    row.get("description", String.class),
                    row.get("status", String.class),
                    row.get("version", Long.class),
                    row.get("price_cents", Long.class),
                    row.get("credits_amount", Integer.class),
                    row.get("note", String.class));
        }
    }
}
