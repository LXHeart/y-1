package com.grassland.marketplace.taskcatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * task 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，风格同 identity 各 repository）。
 *
 * <p>草场 Epic 4 Slice 4A（4B 名额、4F 赏金）+ GL-P1-TASK-001 Stage 1 生命周期：
 * <ul>
 *   <li>{@link #create} 仍是 immediate-publish（{@code POST /api/tasks} 兼容路径）：落 published + published_at + v1 快照；</li>
 *   <li>{@link #createDraft}/{@link #updateDraft}/{@link #publish}/{@link #close}/{@link #cancel} 是生命周期命令，
 *       全部 guarded-UPDATE-with-RETURNING（{@code WHERE status=:from AND version=:expected}），version+1，0 行 → empty（调用方判 409）；</li>
 *   <li>{@link #publish} 同事务落 {@code task_version} 不可变快照（HLD §5.3）；</li>
 *   <li>{@link #countActiveByOrganization} 改 {@code status='published'}、{@link #countCreatedThisMonthByOrganization} 改按 {@code published_at}
 *       （draft 创建不占发布额度）。</li>
 * </ul>
 */
@Component
public class TaskRepository {

    private static final String SELECT_COLS =
            "id::text, owner_account_id::text, organization_id::text, store_id::text, title, description, status,"
            + " content_form, platform, max_slots, bounty_cents, created_at, updated_at,"
            + " version, application_deadline, published_at, cancelled_at, min_recommender_level,"
            + " requirements::text, auto_accept_min_level, freebie_deposit_cents";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DatabaseClient db;

    public TaskRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 任务大厅筛选条件（GL-P1-TASK-001 Stage 2）。字段均可空（null=不过滤该维度）。 */
    public record FeedFilter(String platform, String contentForm, Long minBountyCents,
                             int recommenderLevel, List<String> nearbyStoreIds) {}

    /**
     * 创建即提交审核（GL-P2-ADMIN-003 全审政策）：status=pending_review，不设 published_at（审核通过时才设），
     * 不落快照（审核通过时才落）。由 controller 包进「INSERT task + outbox」同一 R2DBC 事务。
     */
    public Mono<Task> create(String ownerAccountId, String organizationId, String title,
                             String description, String contentForm, String platform, Integer maxSlots,
                             Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel) {
        return create(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, null, TaskRequirements.empty(), null);
    }

    public Mono<Task> create(String ownerAccountId, String organizationId, String title,
                             String description, String contentForm, String platform, Integer maxSlots,
                             Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
                             String storeId, TaskRequirements requirements) {
        return create(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, storeId, requirements, null);
    }

    public Mono<Task> create(String ownerAccountId, String organizationId, String title,
                             String description, String contentForm, String platform, Integer maxSlots,
                             Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
                             String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel) {
        return create(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, storeId, requirements,
                autoAcceptMinLevel, null);
    }

    public Mono<Task> create(String ownerAccountId, String organizationId, String title,
                             String description, String contentForm, String platform, Integer maxSlots,
                             Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
                             String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
                             Long freebieDepositCents) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, store_id, title, description, status,
                                 content_form, platform, max_slots, bounty_cents, application_deadline,
                                 min_recommender_level, requirements, auto_accept_min_level, freebie_deposit_cents)
                VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), CAST(:store AS uuid), :title,
                        :desc, 'pending_review', :contentForm, :platform, :maxSlots, :bountyCents,
                        :deadline, :minLevel, CAST(:requirements AS jsonb), :autoAcceptMinLevel, :freebieDeposit)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("owner", ownerAccountId).bind("org", organizationId).bind("title", title);
        spec = bindNullable(spec, "desc", description);
        spec = bindNullable(spec, "contentForm", contentForm);
        spec = bindNullable(spec, "platform", platform);
        spec = bindNullableInt(spec, "maxSlots", maxSlots);
        spec = bindNullableLong(spec, "bountyCents", bountyCents);
        spec = bindNullableDeadline(spec, "deadline", applicationDeadline);
        spec = bindNullable(spec, "store", storeId);
        spec = spec.bind("minLevel", normalizeMinimumLevel(minRecommenderLevel));
        spec = spec.bind("requirements", json(TaskRequirements.normalize(requirements)));
        spec = bindNullableInt(spec, "autoAcceptMinLevel", autoAcceptMinLevel);
        spec = bindNullableLong(spec, "freebieDeposit", freebieDepositCents);
        return spec.map(TaskRepository::map).one();
    }

    /** 创建草稿（status=draft, version=0）。draft 创建不占发布额度、不需资金权限。 */
    public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title,
                                  String description, String contentForm, String platform, Integer maxSlots,
                                  Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel) {
        return createDraft(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, null, TaskRequirements.empty(), null);
    }

    public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title,
                                  String description, String contentForm, String platform, Integer maxSlots,
                                  Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
                                  String storeId, TaskRequirements requirements) {
        return createDraft(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, storeId, requirements, null);
    }

    public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title,
                                  String description, String contentForm, String platform, Integer maxSlots,
                                  Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
                                  String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel) {
        return createDraft(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, storeId, requirements,
                autoAcceptMinLevel, null);
    }

    public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title,
                                  String description, String contentForm, String platform, Integer maxSlots,
                                  Long bountyCents, Instant applicationDeadline, Integer minRecommenderLevel,
                                  String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
                                  Long freebieDepositCents) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO task(id, owner_account_id, organization_id, store_id, title, description, status,
                                 content_form, platform, max_slots, bounty_cents, version, application_deadline,
                                 min_recommender_level, requirements, auto_accept_min_level, freebie_deposit_cents)
                VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), CAST(:store AS uuid), :title,
                        :desc, 'draft', :contentForm, :platform, :maxSlots, :bountyCents, 0, :deadline, :minLevel,
                        CAST(:requirements AS jsonb), :autoAcceptMinLevel, :freebieDeposit)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("owner", ownerAccountId).bind("org", organizationId).bind("title", title);
        spec = bindNullable(spec, "desc", description);
        spec = bindNullable(spec, "contentForm", contentForm);
        spec = bindNullable(spec, "platform", platform);
        spec = bindNullableInt(spec, "maxSlots", maxSlots);
        spec = bindNullableLong(spec, "bountyCents", bountyCents);
        spec = bindNullableDeadline(spec, "deadline", applicationDeadline);
        spec = bindNullable(spec, "store", storeId);
        spec = spec.bind("minLevel", normalizeMinimumLevel(minRecommenderLevel));
        spec = spec.bind("requirements", json(TaskRequirements.normalize(requirements)));
        spec = bindNullableInt(spec, "autoAcceptMinLevel", autoAcceptMinLevel);
        spec = bindNullableLong(spec, "freebieDeposit", freebieDepositCents);
        return spec.map(TaskRepository::map).one();
    }

    /** 编辑草稿字段（仅 draft 态；guarded by status+version，version+1）。0 行（非 draft / 版本冲突）→ empty。 */
    public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description,
                                  String contentForm, String platform, Integer maxSlots, Long bountyCents,
                                  Instant applicationDeadline, Integer minRecommenderLevel,
                                  TaskRequirements requirements) {
        return updateDraft(id, expectedVersion, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, requirements, null);
    }

    public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description,
                                  String contentForm, String platform, Integer maxSlots, Long bountyCents,
                                  Instant applicationDeadline, Integer minRecommenderLevel,
                                  TaskRequirements requirements, Integer autoAcceptMinLevel) {
        return updateDraft(id, expectedVersion, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, requirements, autoAcceptMinLevel, null);
    }

    public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description,
                                  String contentForm, String platform, Integer maxSlots, Long bountyCents,
                                  Instant applicationDeadline, Integer minRecommenderLevel,
                                  TaskRequirements requirements, Integer autoAcceptMinLevel,
                                  Long freebieDepositCents) {
        var spec = db.sql("""
                UPDATE task SET title = :title, description = :desc, content_form = :contentForm,
                                platform = :platform, max_slots = :maxSlots, bounty_cents = :bountyCents,
                                application_deadline = :deadline, min_recommender_level = :minLevel,
                                requirements = COALESCE(CAST(:requirements AS jsonb), requirements),
                                auto_accept_min_level = :autoAcceptMinLevel,
                                freebie_deposit_cents = :freebieDeposit,
                                version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'draft' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion).bind("title", title);
        spec = bindNullable(spec, "desc", description);
        spec = bindNullable(spec, "contentForm", contentForm);
        spec = bindNullable(spec, "platform", platform);
        spec = bindNullableInt(spec, "maxSlots", maxSlots);
        spec = bindNullableLong(spec, "bountyCents", bountyCents);
        spec = bindNullableDeadline(spec, "deadline", applicationDeadline);
        spec = spec.bind("minLevel", normalizeMinimumLevel(minRecommenderLevel));
        spec = bindNullable(spec, "requirements", requirements == null ? null : json(requirements));
        spec = bindNullableInt(spec, "autoAcceptMinLevel", autoAcceptMinLevel);
        spec = bindNullableLong(spec, "freebieDeposit", freebieDepositCents);
        return spec.map(TaskRepository::map).one();
    }

    /**
     * 提交审核（GL-P2-ADMIN-003 全审政策）：draft→pending_review，version+1。
     * 不设 published_at（审核通过时才设），不落快照。0 行（非 draft / 版本冲突）→ empty。
     */
    public Mono<Task> publish(String id, int expectedVersion) {
        return db.sql("""
                UPDATE task SET status = 'pending_review', version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'draft' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion)
                .map(TaskRepository::map).one();
    }

    /** 关闭报名（published→closed，version+1）。既有履约不受影响。0 行（非 published / 版本冲突）→ empty。 */
    public Mono<Task> close(String id, int expectedVersion) {
        return transition(id, expectedVersion, "published", "closed");
    }

    /**
     * 修订已发布任务（GL-P1-TASK-001：编辑出新版本，全字段）。
     *
     * <p>全字段可改——accept/结算已读 {@code task_application.bounty_cents} 快照（V14 snapshot-pinning），故修订 task
     * 赏金/平台只影响<b>新报名</b>（新 app 冻新值），已 accept 的履约仍按其 accept 时快照结算（HLD §2.3「配置不篡改历史」）。
     *
     * <p>guarded {@code WHERE status='published' AND version=:expected}，version+1，同事务落新 {@code task_version} 快照。
     * 0 行（非 published / 版本冲突）→ empty，调用方映射 409。
     */
    public Mono<Task> revisePublished(String id, int expectedVersion, String title, String description,
                                      String contentForm, String platform, Integer maxSlots, Long bountyCents,
                                      Instant applicationDeadline, Integer minRecommenderLevel,
                                      TaskRequirements requirements, String revisedBy) {
        return revisePublished(id, expectedVersion, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, requirements, revisedBy, null);
    }

    public Mono<Task> revisePublished(String id, int expectedVersion, String title, String description,
                                      String contentForm, String platform, Integer maxSlots, Long bountyCents,
                                      Instant applicationDeadline, Integer minRecommenderLevel,
                                      TaskRequirements requirements, String revisedBy,
                                      Integer autoAcceptMinLevel) {
        return revisePublished(id, expectedVersion, title, description, contentForm, platform, maxSlots,
                bountyCents, applicationDeadline, minRecommenderLevel, requirements, revisedBy,
                autoAcceptMinLevel, null);
    }

    public Mono<Task> revisePublished(String id, int expectedVersion, String title, String description,
                                      String contentForm, String platform, Integer maxSlots, Long bountyCents,
                                      Instant applicationDeadline, Integer minRecommenderLevel,
                                      TaskRequirements requirements, String revisedBy,
                                      Integer autoAcceptMinLevel, Long freebieDepositCents) {
        var spec = db.sql("""
                UPDATE task SET title = :title, description = :desc, content_form = :contentForm,
                                platform = :platform, max_slots = :maxSlots, bounty_cents = :bountyCents,
                                application_deadline = :deadline, min_recommender_level = :minLevel,
                                requirements = COALESCE(CAST(:requirements AS jsonb), requirements),
                                auto_accept_min_level = :autoAcceptMinLevel,
                                freebie_deposit_cents = :freebieDeposit,
                                version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'published' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion).bind("title", title);
        spec = bindNullable(spec, "desc", description);
        spec = bindNullable(spec, "contentForm", contentForm);
        spec = bindNullable(spec, "platform", platform);
        spec = bindNullableInt(spec, "maxSlots", maxSlots);
        spec = bindNullableLong(spec, "bountyCents", bountyCents);
        spec = bindNullableDeadline(spec, "deadline", applicationDeadline);
        spec = spec.bind("minLevel", normalizeMinimumLevel(minRecommenderLevel));
        spec = bindNullable(spec, "requirements", requirements == null ? null : json(requirements));
        spec = bindNullableInt(spec, "autoAcceptMinLevel", autoAcceptMinLevel);
        spec = bindNullableLong(spec, "freebieDeposit", freebieDepositCents);
        return spec.map(TaskRepository::map).one()
                .flatMap(task -> appendVersion(task, revisedBy).thenReturn(task));
    }

    /** 取消任务（draft|published|pending_review→cancelled，cancelled_at=now，version+1）。0 行 → empty。 */
    public Mono<Task> cancel(String id, int expectedVersion) {
        return db.sql("""
                UPDATE task SET status = 'cancelled', cancelled_at = now(), version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status IN ('draft', 'published', 'pending_review') AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion)
                .map(TaskRepository::map).one();
    }

    /**
     * 审核通过（GL-P2-ADMIN-003 全审政策）：pending_review→published，published_at=now，version+1，
     * 同事务落 v1 task_version 快照（审核通过 = 正式上架，published_at 才反映真正上架时刻）。
     * 0 行（非 pending_review / 版本冲突）→ empty。
     */
    public Mono<Task> reviewApprove(String id, int expectedVersion, String approvedBy) {
        return db.sql("""
                UPDATE task SET status = 'published', published_at = now(), version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'pending_review' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion)
                .map(TaskRepository::map).one()
                .flatMap(task -> appendVersion(task, approvedBy).thenReturn(task));
    }

    /**
     * 审核驳回（全审政策）：pending_review→draft（退回让商家修改后重新提交），version+1。
     * 0 行（非 pending_review / 版本冲突）→ empty。
     */
    public Mono<Task> reviewReject(String id, int expectedVersion) {
        return db.sql("""
                UPDATE task SET status = 'draft', version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'pending_review' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion)
                .map(TaskRepository::map).one();
    }

    /** 列出待审核任务（内容审核员队列），按提交时间正序。 */
    public reactor.core.publisher.Flux<Task> findPendingReview(int limit) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM task WHERE status = 'pending_review' ORDER BY created_at LIMIT :limit")
                .bind("limit", Math.max(1, Math.min(limit, 200)))
                .map(TaskRepository::map).all();
    }

    /** SLA queue uses updated_at because every resubmission refreshes it and pending_review has no other writes. */
    public reactor.core.publisher.Flux<Task> findPendingReviewBefore(Instant cutoff, int limit) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM task WHERE status = 'pending_review' AND updated_at <= :cutoff"
                        + " ORDER BY updated_at, id LIMIT :limit FOR UPDATE SKIP LOCKED")
                .bind("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .bind("limit", Math.max(1, Math.min(limit, 200)))
                .map(TaskRepository::map).all();
    }

    /** Operational review queue with status, organization, platform, SLA and offset filters. */
    public reactor.core.publisher.Flux<Task> findReviewQueue(
            String status, String organizationId, String platform, boolean overdue, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS).append(" FROM task WHERE 1=1");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        if (organizationId != null && !organizationId.isBlank()) sql.append(" AND organization_id = CAST(:org AS uuid)");
        if (platform != null && !platform.isBlank()) sql.append(" AND platform = :platform");
        if (overdue) sql.append(" AND status = 'pending_review' AND updated_at < now() - interval '24 hours'");
        sql.append(" ORDER BY updated_at ASC, id LIMIT :limit OFFSET :offset");
        var spec = db.sql(sql.toString()).bind("limit", Math.max(1, Math.min(limit, 200)))
                .bind("offset", Math.max(0, offset));
        if (status != null && !status.isBlank()) spec = spec.bind("status", status);
        if (organizationId != null && !organizationId.isBlank()) spec = spec.bind("org", organizationId);
        if (platform != null && !platform.isBlank()) spec = spec.bind("platform", platform);
        return spec.map(TaskRepository::map).all();
    }

    public Mono<Task> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM task WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(TaskRepository::map).one();
    }

    /**
     * 全局任务大厅（GL-P1-TASK-001 Stage 2）：跨组织 feed，仅 published 且未截止，可选筛选 + keyset 游标分页。
     *
     * <p>排序 {@code (created_at DESC, id DESC)} 稳定；游标 = 上一页最后一行的 {@code (created_at, id)}，
     * 取「更老」的下一页（{@code (created_at, id) < (cursorTs, cursorId)}，DESC 下即游标之后的行）。
     * {@code cursor} 为 null 时首页（不带 keyset 谓词，避免 null/值混合绑定的类型歧义）。
     * {@code limit} 已 +1（调用方据此判 hasMore）；返回行数 ≤ limit。
     */
    public Flux<Task> findFeed(FeedFilter filter, Instant cursorTs, String cursorId, int limit) {
        boolean firstPage = cursorTs == null;
        String predicate = "status = 'published'"
                + " AND (application_deadline IS NULL OR application_deadline > now())"
                + " AND min_recommender_level <= :recommenderLevel"
                + (filter.platform() != null ? " AND platform = :platform" : "")
                + (filter.contentForm() != null ? " AND content_form = :contentForm" : "")
                + (filter.minBountyCents() != null ? " AND bounty_cents IS NOT NULL AND bounty_cents >= :minBountyCents" : "")
                + (filter.nearbyStoreIds() != null ? " AND store_id::text IN (:nearbyStoreIds)" : "")
                + (firstPage ? "" : " AND (created_at, id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid))");
        String sql = "SELECT " + SELECT_COLS + " FROM task WHERE " + predicate
                + " ORDER BY created_at DESC, id DESC LIMIT :limit";
        var spec = db.sql(sql).bind("limit", limit).bind("recommenderLevel", filter.recommenderLevel());
        // 只绑定 SQL 中实际出现的命名参数：r2dbc-postgresql 对 SQL 里不存在的标识符 bind/bindNull 会抛
        // NoSuchElementException，故筛选子句省略时连 bindNull 都不能调（controller 已把空白归一为 null）。
        if (filter.platform() != null) {
            spec = spec.bind("platform", filter.platform());
        }
        if (filter.contentForm() != null) {
            spec = spec.bind("contentForm", filter.contentForm());
        }
        if (filter.minBountyCents() != null) {
            spec = spec.bind("minBountyCents", filter.minBountyCents());
        }
        if (filter.nearbyStoreIds() != null) {
            spec = spec.bind("nearbyStoreIds", filter.nearbyStoreIds());
        }
        if (!firstPage) {
            spec = spec.bind("cursorTs", cursorTs.atOffset(ZoneOffset.UTC)).bind("cursorId", cursorId);
        }
        return spec.map(TaskRepository::map).all();
    }

    /** 列某 org 的组织级任务（不含 store-scoped 行）；status 为空则不限。 */
    public Flux<Task> findByOrganization(String organizationId, String status) {
        if (status == null || status.isBlank()) {
            return db.sql("SELECT " + SELECT_COLS
                    + " FROM task WHERE organization_id = CAST(:org AS uuid) AND store_id IS NULL ORDER BY created_at DESC")
                    .bind("org", organizationId)
                    .map(TaskRepository::map).all();
        }
        return db.sql("SELECT " + SELECT_COLS
                + " FROM task WHERE organization_id = CAST(:org AS uuid) AND store_id IS NULL"
                + " AND status = :status ORDER BY created_at DESC")
                .bind("org", organizationId).bind("status", status)
                .map(TaskRepository::map).all();
    }

    /** 列某一门店的任务；调用方必须先完成 Identity 门店授权。 */
    public Flux<Task> findByStore(String organizationId, String storeId, String status) {
        String statusPredicate = status == null || status.isBlank() ? "" : " AND status = :status";
        var spec = db.sql("SELECT " + SELECT_COLS + " FROM task"
                        + " WHERE organization_id = CAST(:org AS uuid) AND store_id = CAST(:store AS uuid)"
                        + statusPredicate + " ORDER BY created_at DESC")
                .bind("org", organizationId)
                .bind("store", storeId);
        if (!statusPredicate.isEmpty()) {
            spec = spec.bind("status", status);
        }
        return spec.map(TaskRepository::map).all();
    }

    /** 任务书 #27：查找已发布且开启了自动通过门槛的任务（未截止、未关闭）。dispatcher 每轮扫描用。 */
    public Flux<Task> findAutoAcceptEnabled(int limit) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM task WHERE status = 'published' AND auto_accept_min_level IS NOT NULL"
                        + " AND (application_deadline IS NULL OR application_deadline > now())"
                        + " ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED")
                .bind("limit", Math.max(1, Math.min(limit, 200)))
                .map(TaskRepository::map).all();
    }

    /** 某 org 当前 published 的任务数——发布限额执行用（Stage 1：只 published 算活跃，draft/cancelled 不占）。 */
    public Mono<Integer> countActiveByOrganization(String organizationId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task"
                + " WHERE organization_id = CAST(:org AS uuid) AND status = 'published'")
                .bind("org", organizationId)
                .map(r -> r.get("c", Integer.class)).one();
    }

    /** 某 org <b>本自然月</b>发布的任务数（按 published_at；draft 创建不计）——月度发布限额执行用（D-05）。
     *  月份边界按 DB 时区 {@code date_trunc('month', now())}；跨月自动重置。 */
    public Mono<Integer> countCreatedThisMonthByOrganization(String organizationId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task"
                + " WHERE organization_id = CAST(:org AS uuid) AND published_at IS NOT NULL"
                + " AND published_at >= date_trunc('month', now())")
                .bind("org", organizationId)
                .map(r -> r.get("c", Integer.class)).one();
    }

    /**
     * Serializes quota check + publish for one organization inside the caller's transaction.
     * The UUID text is hashed to a stable PostgreSQL advisory-lock key; the lock is released on commit/rollback.
     */
    public Mono<Void> acquireOrganizationPublishLock(String organizationId) {
        return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:organizationId, 0))")
                .bind("organizationId", organizationId)
                .then();
    }

    /** 落一行不可变 task_version 快照（HLD §5.3）。version 取 task 当前 version（publish 后已 +1）。 */
    private Mono<Void> appendVersion(Task task, String publishedBy) {
        var spec = db.sql("""
                INSERT INTO task_version(task_id, version, store_id, title, description, content_form, platform,
                                         max_slots, bounty_cents, application_deadline, published_at, published_by,
                                         min_recommender_level, requirements, freebie_deposit_cents)
                VALUES (CAST(:taskId AS uuid), :version, CAST(:store AS uuid), :title, :desc, :contentForm, :platform,
                        :maxSlots, :bountyCents, :deadline, COALESCE(:publishedAt, now()), CAST(:publishedBy AS uuid),
                        :minLevel, CAST(:requirements AS jsonb), :freebieDeposit)
                """)
                .bind("taskId", task.id()).bind("version", task.version()).bind("title", task.title());
        spec = spec.bind("minLevel", task.minRecommenderLevel());
        spec = spec.bind("requirements", json(task.requirements()));
        spec = bindNullable(spec, "store", task.storeId());
        spec = bindNullable(spec, "publishedBy", publishedBy);
        spec = bindNullable(spec, "desc", task.description());
        spec = bindNullable(spec, "contentForm", task.contentForm());
        spec = bindNullable(spec, "platform", task.platform());
        spec = bindNullableInt(spec, "maxSlots", task.maxSlots());
        spec = bindNullableLong(spec, "bountyCents", task.bountyCents());
        spec = bindNullableDeadline(spec, "deadline", task.applicationDeadline());
        spec = bindNullableDeadline(spec, "publishedAt", task.publishedAt());
        spec = bindNullableLong(spec, "freebieDeposit", task.freebieDepositCents());
        return spec.then();
    }

    private Mono<Task> transition(String id, int expectedVersion, String fromStatus, String toStatus) {
        return db.sql("""
                UPDATE task SET status = :status, version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = :from AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion)
                .bind("from", fromStatus).bind("status", toStatus)
                .map(TaskRepository::map).one();
    }

    private static Task map(Readable row) {
        return new Task(
                row.get("id", String.class),
                row.get("owner_account_id", String.class),
                row.get("organization_id", String.class),
                row.get("title", String.class),
                row.get("description", String.class),
                row.get("status", String.class),
                row.get("content_form", String.class),
                row.get("platform", String.class),
                row.get("max_slots", Integer.class),
                row.get("bounty_cents", Long.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                value(row.get("version", Integer.class), 1),
                toInstant(row.get("application_deadline", OffsetDateTime.class)),
                toInstant(row.get("published_at", OffsetDateTime.class)),
                toInstant(row.get("cancelled_at", OffsetDateTime.class)),
                value(row.get("min_recommender_level", Integer.class), 1),
                row.get("store_id", String.class),
                requirements(row.get("requirements", String.class)),
                row.get("auto_accept_min_level", Integer.class),
                row.get("freebie_deposit_cents", Long.class)
        );
    }

    private String json(TaskRequirements value) {
        try {
            return MAPPER.writeValueAsString(TaskRequirements.normalize(value));
        } catch (Exception error) {
            throw new IllegalArgumentException("任务要求格式不合法", error);
        }
    }

    private static TaskRequirements requirements(String value) {
        try {
            return value == null ? TaskRequirements.empty() : MAPPER.readValue(value, TaskRequirements.class);
        } catch (Exception error) {
            throw new IllegalStateException("任务要求数据损坏", error);
        }
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static int normalizeMinimumLevel(Integer value) {
        return value == null ? 1 : value;
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableInt(GenericExecuteSpec spec, String name, Integer value) {
        return value == null ? spec.bindNull(name, Integer.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableLong(GenericExecuteSpec spec, String name, Long value) {
        return value == null ? spec.bindNull(name, Long.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableDeadline(GenericExecuteSpec spec, String name, Instant value) {
        return value == null
                ? spec.bindNull(name, OffsetDateTime.class)
                : spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }
}
