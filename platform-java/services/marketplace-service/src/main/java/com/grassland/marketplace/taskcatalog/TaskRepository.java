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
 * <p>
 * 草场 Epic 4 Slice 4A（4B 名额、4F 赏金）+ GL-P1-TASK-001 Stage 1 生命周期：
 * <ul>
 * <li>{@link #create} 仍是 immediate-publish（{@code POST /api/tasks} 兼容路径）：落
 * published + published_at + v1 快照；</li>
 * <li>{@link #createDraft}/{@link #updateDraft}/{@link #publish}/{@link #close}/{@link #cancel}
 * 是生命周期命令， 全部
 * guarded-UPDATE-with-RETURNING（{@code WHERE status=:from AND version=:expected}），version+1，0
 * 行 → empty（调用方判 409）；</li>
 * <li>{@link #publish} 同事务落 {@code task_version} 不可变快照（HLD §5.3）；</li>
 * <li>{@link #countActiveByOrganization} 改
 * {@code status='published'}、{@link #countCreatedThisMonthByOrganization} 改按
 * {@code published_at} （draft 创建不占发布额度）。</li>
 * </ul>
 */
@Component
public class TaskRepository {

	private static final String SELECT_COLS = "id::text, owner_account_id::text, organization_id::text, store_id::text, title, description, status,"
			+ " content_form, platform, max_slots, bounty_cents, created_at, updated_at,"
			+ " version, application_deadline, published_at, cancelled_at, min_recommender_level,"
			+ " requirements::text, auto_accept_min_level, freebie_deposit_cents," + " question_text, question_ref,"
			+ " commerce_package_id::text";

	/**
	 * {@link #SELECT_COLS} 的 {@code t.} 限定版本，供 LATERAL join task_review
	 * 的行查复用（与裸列同字段集）。
	 */
	private static final String SELECT_COLS_REVIEW = "t.id::text, t.owner_account_id::text, t.organization_id::text, t.store_id::text, t.title,"
			+ " t.description, t.status, t.content_form, t.platform, t.max_slots, t.bounty_cents,"
			+ " t.created_at, t.updated_at, t.version, t.application_deadline, t.published_at, t.cancelled_at,"
			+ " t.min_recommender_level, t.requirements::text, t.auto_accept_min_level, t.freebie_deposit_cents,"
			+ " t.question_text, t.question_ref, t.commerce_package_id::text";

	/** LATERAL 取每任务最新一条 task_review 记录（无记录时 LEFT 保行，字段归 null）。 */
	private static final String LATEST_REVIEW_JOIN = " LEFT JOIN LATERAL (SELECT action, review_note, created_at FROM task_review"
			+ " WHERE task_id = t.id ORDER BY created_at DESC LIMIT 1) tr ON true";

	/**
	 * #26 D6/D7 满员谓词（feed 与自动接受扫描同口径共用）：counter occupied≥max 不展示/不扫描—— 与 apply
	 * 端「名额已满」同口径；closed 任务已被 status 谓词排除，counter 谓词兜住资金型 reserving
	 * 瞬态与漏网路径；max_slots NULL 恒展示。
	 */
	private static final String SLOTS_AVAILABLE_PREDICATE = " AND (task.max_slots IS NULL OR NOT EXISTS (SELECT 1 FROM task_acceptance_counter counter"
			+ " WHERE counter.task_id = task.id AND counter.occupied_slots >= task.max_slots))";

	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	private final DatabaseClient db;

	public TaskRepository(DatabaseClient db) {
		this.db = db;
	}

	/** 任务大厅筛选条件（GL-P1-TASK-001 Stage 2）。字段均可空（null=不过滤该维度）。 */
	public record FeedFilter(String platform, String contentForm, Long minBountyCents, int recommenderLevel,
			List<String> nearbyStoreIds, String query) {
	}

	/**
	 * 创建即提交审核（GL-P2-ADMIN-003 全审政策）：status=pending_review，不设 published_at（审核通过时才设），
	 * 不落快照（审核通过时才落）。由 controller 包进「INSERT task + outbox」同一 R2DBC 事务。
	 */
	public Mono<Task> create(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel) {
		return create(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, null, TaskRequirements.empty(), null);
	}

	public Mono<Task> create(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements) {
		return create(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, storeId, requirements, null);
	}

	public Mono<Task> create(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel) {
		return create(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, storeId, requirements, autoAcceptMinLevel, null);
	}

	public Mono<Task> create(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents) {
		return create(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, storeId, requirements, autoAcceptMinLevel,
				freebieDepositCents, TaskQuestion.none());
	}

	/** 任务书 #62：带目标问题的发布（question 随后由 publish 冻进 task_version）。 */
	public Mono<Task> create(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents, TaskQuestion question) {
		return create(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, storeId, requirements, autoAcceptMinLevel,
				freebieDepositCents, question, null);
	}

	/** 任务书 #75：带套餐推广关联的发布（commercePackageId 非空 = 套餐推广任务，资金字段契约层已拦 0/0）。 */
	public Mono<Task> create(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents, TaskQuestion question, String commercePackageId) {
		String id = UUID.randomUUID().toString();
		var spec = db.sql("""
				INSERT INTO task(id, owner_account_id, organization_id, store_id, title, description, status,
				                 content_form, platform, max_slots, bounty_cents, application_deadline,
				                 min_recommender_level, requirements, auto_accept_min_level, freebie_deposit_cents,
				                 question_text, question_ref, commerce_package_id)
				VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), CAST(:store AS uuid), :title,
				        :desc, 'pending_review', :contentForm, :platform, :maxSlots, :bountyCents,
				        :deadline, :minLevel, CAST(:requirements AS jsonb), :autoAcceptMinLevel, :freebieDeposit,
				        :questionText, :questionRef, CAST(:packageId AS uuid))
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("owner", ownerAccountId).bind("org", organizationId)
				.bind("title", title);
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
		spec = bindQuestion(spec, question);
		spec = bindNullable(spec, "packageId", commercePackageId);
		return spec.map(TaskRepository::map).one();
	}

	/** 创建草稿（status=draft, version=0）。draft 创建不占发布额度、不需资金权限。 */
	public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel) {
		return createDraft(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
				bountyCents, applicationDeadline, minRecommenderLevel, null, TaskRequirements.empty(), null);
	}

	public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements) {
		return createDraft(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
				bountyCents, applicationDeadline, minRecommenderLevel, storeId, requirements, null);
	}

	public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel) {
		return createDraft(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
				bountyCents, applicationDeadline, minRecommenderLevel, storeId, requirements, autoAcceptMinLevel, null);
	}

	public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents) {
		return createDraft(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
				bountyCents, applicationDeadline, minRecommenderLevel, storeId, requirements, autoAcceptMinLevel,
				freebieDepositCents, TaskQuestion.none());
	}

	/** 任务书 #62：带目标问题的草稿。 */
	public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents, TaskQuestion question) {
		return createDraft(ownerAccountId, organizationId, title, description, contentForm, platform, maxSlots,
				bountyCents, applicationDeadline, minRecommenderLevel, storeId, requirements, autoAcceptMinLevel,
				freebieDepositCents, question, null);
	}

	/** 任务书 #75：带套餐推广关联的草稿。 */
	public Mono<Task> createDraft(String ownerAccountId, String organizationId, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, String storeId, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents, TaskQuestion question, String commercePackageId) {
		String id = UUID.randomUUID().toString();
		var spec = db.sql("""
				INSERT INTO task(id, owner_account_id, organization_id, store_id, title, description, status,
				                 content_form, platform, max_slots, bounty_cents, version, application_deadline,
				                 min_recommender_level, requirements, auto_accept_min_level, freebie_deposit_cents,
				                 question_text, question_ref, commerce_package_id)
				VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), CAST(:store AS uuid), :title,
				        :desc, 'draft', :contentForm, :platform, :maxSlots, :bountyCents, 0, :deadline, :minLevel,
				        CAST(:requirements AS jsonb), :autoAcceptMinLevel, :freebieDeposit,
				        :questionText, :questionRef, CAST(:packageId AS uuid))
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("owner", ownerAccountId).bind("org", organizationId)
				.bind("title", title);
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
		spec = bindQuestion(spec, question);
		spec = bindNullable(spec, "packageId", commercePackageId);
		return spec.map(TaskRepository::map).one();
	}

	/**
	 * 编辑草稿字段（仅 draft 态；guarded by status+version，version+1）。0 行（非 draft / 版本冲突）→
	 * empty。
	 */
	public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description, String contentForm,
			String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements) {
		return updateDraft(id, expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, requirements, null);
	}

	public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description, String contentForm,
			String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, Integer autoAcceptMinLevel) {
		return updateDraft(id, expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, requirements, autoAcceptMinLevel, null);
	}

	public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description, String contentForm,
			String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents) {
		return updateDraft(id, expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, requirements, autoAcceptMinLevel, freebieDepositCents,
				TaskQuestion.none());
	}

	/** 任务书 #62：带目标问题的草稿编辑（question 整份覆盖，null=清空，与 title 同语义）。 */
	public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description, String contentForm,
			String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents, TaskQuestion question) {
		return updateDraft(id, expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, requirements, autoAcceptMinLevel, freebieDepositCents,
				question, null);
	}

	/** 任务书 #75：带套餐推广关联的草稿编辑（draft 态无人报名，改绑无既有报名约束）。 */
	public Mono<Task> updateDraft(String id, int expectedVersion, String title, String description, String contentForm,
			String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, Integer autoAcceptMinLevel,
			Long freebieDepositCents, TaskQuestion question, String commercePackageId) {
		var spec = db.sql("""
				UPDATE task SET title = :title, description = :desc, content_form = :contentForm,
				                platform = :platform, max_slots = :maxSlots, bounty_cents = :bountyCents,
				                application_deadline = :deadline, min_recommender_level = :minLevel,
				                requirements = COALESCE(CAST(:requirements AS jsonb), requirements),
				                auto_accept_min_level = :autoAcceptMinLevel,
				                freebie_deposit_cents = :freebieDeposit,
				                question_text = :questionText, question_ref = :questionRef,
				                commerce_package_id = CAST(:packageId AS uuid),
				                version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'draft' AND version = :expected
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("expected", expectedVersion).bind("title", title);
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
		spec = bindQuestion(spec, question);
		spec = bindNullable(spec, "packageId", commercePackageId);
		return spec.map(TaskRepository::map).one();
	}

	/**
	 * 提交审核（GL-P2-ADMIN-003 全审政策）：draft→pending_review，version+1。 不设
	 * published_at（审核通过时才设），不落快照。0 行（非 draft / 版本冲突）→ empty。
	 */
	public Mono<Task> publish(String id, int expectedVersion) {
		return db.sql("""
				UPDATE task SET status = 'pending_review', version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'draft' AND version = :expected
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("expected", expectedVersion).map(TaskRepository::map)
				.one();
	}

	/** 关闭报名（published→closed，version+1）。既有履约不受影响。0 行（非 published / 版本冲突）→ empty。 */
	public Mono<Task> close(String id, int expectedVersion) {
		return transition(id, expectedVersion, "published", "closed");
	}

	/**
	 * #26 满员自动关闭（D3）：accepted 计数达到 max_slots 时 published→closed。无版本守卫（系统迁移不参与
	 * 用户乐观锁）；0 行 = 无操作（无上限/未满/已非 published——可能被并发手动 close 或 cancel 抢先），绝不报错。
	 *
	 * <p>
	 * FOR NO KEY UPDATE 前置锁串行化并发激活的满员判定（READ COMMITTED 快照互盲修复）：资金型接受 claim
	 * 在独立事务先提交，两个 saga 激活事务各 UPDATE 不同 task_application 行后并发判定；条件 UPDATE 谓词 不匹配时不取
	 * task 行锁，两边的 count 子查询各只见自己那条已提交的 accepted（READ COMMITTED 语句快照 互盲），均判未满双双
	 * no-op → 任务满员却仍 published。先同事务锁定 task 行：空 = 已非 published / 无上限 →
	 * 直接无操作（顺带省去无上限任务的 UPDATE）；非空 = 后到者阻塞至先者提交后以新语句快照重评 count，可见 对方已提交的 accepted
	 * 行。两步须于同一调用方事务内执行（所有调用方均已如此，见 TaskFullAutoCloser 契约）。
	 *
	 * <p>
	 * 锁强度取 FOR NO KEY UPDATE（而非 FOR UPDATE）有二因：① task_acceptance_command.task_id 有
	 * FK→task， 每笔 claim 事务的 INSERT 都对 task 行持 FOR KEY SHARE——FOR UPDATE 与之冲突，会与「持
	 * KEY SHARE 等 counter 行锁的并发 claim 事务」成环死锁（40P01）；FOR NO KEY UPDATE 兼容 KEY
	 * SHARE，且与并发 closeIfFull 互斥（NO KEY UPDATE 彼此冲突），串行化效果不变。② 与收口 UPDATE 自身（不改键列 →
	 * NO KEY UPDATE）同强度，取锁不超出必要。
	 */
	public Mono<Task> closeIfFull(String taskId) {
		return db
				.sql("SELECT id::text FROM task"
						+ " WHERE id = CAST(:id AS uuid) AND status = 'published' AND max_slots IS NOT NULL"
						+ " FOR NO KEY UPDATE")
				.bind("id", taskId).map(row -> row.get("id", String.class)).one().flatMap(ignored -> db.sql("""
						UPDATE task SET status = 'closed', version = version + 1, updated_at = now()
						WHERE id = CAST(:id AS uuid) AND status = 'published' AND max_slots IS NOT NULL
						  AND (SELECT count(*) FROM task_application a
						       WHERE a.task_id = task.id AND a.status = 'accepted') >= task.max_slots
						RETURNING %s
						""".formatted(SELECT_COLS)).bind("id", taskId).map(TaskRepository::map).one());
	}

	/**
	 * 修订已发布任务（GL-P1-TASK-001：编辑出新版本）。
	 *
	 * <p>
	 * <b>报名守卫（PRD §2.3）</b>：已有人报名成功（accepted / reserving 任一存在）即不可修订—— UPDATE 的
	 * WHERE 内联 NOT EXISTS，与行锁同句原子判定，堵住控制器计数与落库之间的 TOCTOU 窗口 （控制器另有一道带人数的
	 * 409，负责给出友好文案；此处兜底竞态）。仅剩 pending 报名不阻塞修订， 推荐官被接受那一刻冻结 {@code bounty_cents}
	 * 快照（V14 snapshot-pinning），按快照结算。
	 *
	 * <p>
	 * guarded
	 * {@code WHERE status='published' AND version=:expected}，version+1，同事务落新
	 * {@code task_version} 快照。 0 行（非 published / 版本冲突 / 有人报名成功）→ empty，调用方映射 409。
	 */
	public Mono<Task> revisePublished(String id, int expectedVersion, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, String revisedBy) {
		return revisePublished(id, expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, requirements, revisedBy, null);
	}

	public Mono<Task> revisePublished(String id, int expectedVersion, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, String revisedBy, Integer autoAcceptMinLevel) {
		return revisePublished(id, expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, requirements, revisedBy, autoAcceptMinLevel, null);
	}

	public Mono<Task> revisePublished(String id, int expectedVersion, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, String revisedBy, Integer autoAcceptMinLevel,
			Long freebieDepositCents) {
		return revisePublished(id, expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, requirements, revisedBy, autoAcceptMinLevel,
				freebieDepositCents, TaskQuestion.none());
	}

	/** 任务书 #62：带目标问题的修订（新版本快照随之携带 question）。 */
	public Mono<Task> revisePublished(String id, int expectedVersion, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, String revisedBy, Integer autoAcceptMinLevel,
			Long freebieDepositCents, TaskQuestion question) {
		return revisePublished(id, expectedVersion, title, description, contentForm, platform, maxSlots, bountyCents,
				applicationDeadline, minRecommenderLevel, requirements, revisedBy, autoAcceptMinLevel,
				freebieDepositCents, question, null);
	}

	/**
	 * 任务书 #75：带套餐推广关联的修订。改绑 packageId 的修订冻结与全字段同位——UPDATE 内联 NOT EXISTS
	 * 已拦「有人报名成功」（accepted/reserving），不存在「有人 accepted 后仍可改 packageId」的路径。
	 */
	public Mono<Task> revisePublished(String id, int expectedVersion, String title, String description,
			String contentForm, String platform, Integer maxSlots, Long bountyCents, Instant applicationDeadline,
			Integer minRecommenderLevel, TaskRequirements requirements, String revisedBy, Integer autoAcceptMinLevel,
			Long freebieDepositCents, TaskQuestion question, String commercePackageId) {
		var spec = db.sql("""
				UPDATE task SET title = :title, description = :desc, content_form = :contentForm,
				                platform = :platform, max_slots = :maxSlots, bounty_cents = :bountyCents,
				                application_deadline = :deadline, min_recommender_level = :minLevel,
				                requirements = COALESCE(CAST(:requirements AS jsonb), requirements),
				                auto_accept_min_level = :autoAcceptMinLevel,
				                freebie_deposit_cents = :freebieDeposit,
				                question_text = :questionText, question_ref = :questionRef,
				                commerce_package_id = CAST(:packageId AS uuid),
				                version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'published' AND version = :expected
				  AND NOT EXISTS (SELECT 1 FROM task_application a
				                  WHERE a.task_id = task.id AND a.status IN ('reserving', 'accepted'))
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("expected", expectedVersion).bind("title", title);
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
		spec = bindQuestion(spec, question);
		spec = bindNullable(spec, "packageId", commercePackageId);
		return spec.map(TaskRepository::map).one().flatMap(task -> appendVersion(task, revisedBy).thenReturn(task));
	}

	/**
	 * 取消任务（draft|published|pending_review→cancelled，cancelled_at=now，version+1）。0 行
	 * → empty。
	 */
	public Mono<Task> cancel(String id, int expectedVersion) {
		return db
				.sql("""
						UPDATE task SET status = 'cancelled', cancelled_at = now(), version = version + 1, updated_at = now()
						WHERE id = CAST(:id AS uuid) AND status IN ('draft', 'published', 'pending_review') AND version = :expected
						RETURNING %s
						"""
						.formatted(SELECT_COLS))
				.bind("id", id).bind("expected", expectedVersion).map(TaskRepository::map).one();
	}

	/**
	 * 审核通过（GL-P2-ADMIN-003
	 * 全审政策）：pending_review→published，published_at=now，version+1， 同事务落 v1
	 * task_version 快照（审核通过 = 正式上架，published_at 才反映真正上架时刻）。 0 行（非 pending_review /
	 * 版本冲突）→ empty。
	 */
	public Mono<Task> reviewApprove(String id, int expectedVersion, String approvedBy) {
		return db.sql("""
				UPDATE task SET status = 'published', published_at = now(), version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'pending_review' AND version = :expected
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("expected", expectedVersion).map(TaskRepository::map)
				.one().flatMap(task -> appendVersion(task, approvedBy).thenReturn(task));
	}

	/**
	 * 审核驳回（全审政策）：pending_review→draft（退回让商家修改后重新提交），version+1。 0 行（非 pending_review
	 * / 版本冲突）→ empty。
	 */
	public Mono<Task> reviewReject(String id, int expectedVersion) {
		return db.sql("""
				UPDATE task SET status = 'draft', version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'pending_review' AND version = :expected
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("expected", expectedVersion).map(TaskRepository::map)
				.one();
	}

	/** 列出待审核任务（内容审核员队列），按提交时间正序。 */
	public reactor.core.publisher.Flux<Task> findPendingReview(int limit) {
		return db
				.sql("SELECT " + SELECT_COLS
						+ " FROM task WHERE status = 'pending_review' ORDER BY created_at LIMIT :limit")
				.bind("limit", Math.max(1, Math.min(limit, 200))).map(TaskRepository::map).all();
	}

	/**
	 * SLA queue uses updated_at because every resubmission refreshes it and
	 * pending_review has no other writes.
	 */
	public reactor.core.publisher.Flux<Task> findPendingReviewBefore(Instant cutoff, int limit) {
		return db
				.sql("SELECT " + SELECT_COLS + " FROM task WHERE status = 'pending_review' AND updated_at <= :cutoff"
						+ " ORDER BY updated_at, id LIMIT :limit FOR UPDATE SKIP LOCKED")
				.bind("cutoff", cutoff.atOffset(ZoneOffset.UTC)).bind("limit", Math.max(1, Math.min(limit, 200)))
				.map(TaskRepository::map).all();
	}

	/**
	 * Operational review queue with status, organization, platform, SLA and offset
	 * filters. 任务书 #53：{@code status=rejected} 走专用视图（最新决定为驳回的 draft 任务），其余分支不变。
	 */
	public reactor.core.publisher.Flux<Task> findReviewQueue(String status, String organizationId, String platform,
			boolean overdue, int limit, int offset, String query) {
		int safeLimit = Math.max(1, Math.min(limit, 200));
		int safeOffset = Math.max(0, offset);
		if ("rejected".equalsIgnoreCase(status)) {
			return findRejectedQueue(organizationId, platform, overdue, query, safeLimit, safeOffset);
		}
		StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS).append(" FROM task WHERE 1=1");
		if (status != null && !status.isBlank())
			sql.append(" AND status = :status");
		sql.append(reviewQueuePredicates("", organizationId, platform, overdue, query));
		sql.append(" ORDER BY updated_at ASC, id LIMIT :limit OFFSET :offset");
		var spec = db.sql(sql.toString()).bind("limit", safeLimit).bind("offset", safeOffset);
		if (status != null && !status.isBlank())
			spec = spec.bind("status", status);
		spec = bindReviewQueueFilters(spec, organizationId, platform, query);
		return spec.map(TaskRepository::map).all();
	}

	/**
	 * 任务书 #53 rejected 视图：<b>最新一条</b> task_review 记录 action='rejected' 的 draft 任务。
	 *
	 * <p>
	 * 语义红线：这是「最新决定为驳回」而非「历史上被驳回过」——商家重新提交后最新记录变 submitted， 任务自然移出本视图；审核通过后任务不再是
	 * draft，同样移出（历史驳回不泄漏）。 谓词与 {@link #countReviewQueue} 共用
	 * {@link #reviewQueuePredicates} 防口径漂移。
	 */
	private Flux<Task> findRejectedQueue(String organizationId, String platform, boolean overdue, String query,
			int limit, int offset) {
		String sql = "SELECT " + SELECT_COLS_REVIEW
				+ ", tr.action AS last_review_action, tr.review_note AS last_review_note,"
				+ " tr.created_at AS last_review_at" + " FROM task t"
				+ " JOIN LATERAL (SELECT action, review_note, created_at FROM task_review"
				+ " WHERE task_id = t.id ORDER BY created_at DESC LIMIT 1) tr ON true"
				+ " WHERE t.status = 'draft' AND tr.action = 'rejected'"
				+ reviewQueuePredicates("t.", organizationId, platform, overdue, query)
				+ " ORDER BY tr.created_at DESC, t.id LIMIT :limit OFFSET :offset";
		var spec = bindReviewQueueFilters(db.sql(sql), organizationId, platform, query).bind("limit", limit)
				.bind("offset", offset);
		return spec.map(TaskRepository::mapWithReview).all();
	}

	/**
	 * 与 {@link #findReviewQueue} 同 WHERE 口径的 COUNT（不含 ORDER BY / LIMIT /
	 * OFFSET）——信封 total 用。
	 */
	public Mono<Integer> countReviewQueue(String status, String organizationId, String platform, boolean overdue,
			String query) {
		if ("rejected".equalsIgnoreCase(status)) {
			String sql = "SELECT COUNT(*)::int AS c FROM task t"
					+ " JOIN LATERAL (SELECT action, review_note, created_at FROM task_review"
					+ " WHERE task_id = t.id ORDER BY created_at DESC LIMIT 1) tr ON true"
					+ " WHERE t.status = 'draft' AND tr.action = 'rejected'"
					+ reviewQueuePredicates("t.", organizationId, platform, overdue, query);
			return bindReviewQueueFilters(db.sql(sql), organizationId, platform, query)
					.map(r -> r.get("c", Integer.class)).one();
		}
		StringBuilder sql = new StringBuilder("SELECT COUNT(*)::int AS c FROM task WHERE 1=1");
		if (status != null && !status.isBlank())
			sql.append(" AND status = :status");
		sql.append(reviewQueuePredicates("", organizationId, platform, overdue, query));
		var spec = db.sql(sql.toString());
		if (status != null && !status.isBlank())
			spec = spec.bind("status", status);
		spec = bindReviewQueueFilters(spec, organizationId, platform, query);
		return spec.map(r -> r.get("c", Integer.class)).one();
	}

	/**
	 * 审核队列共用谓词片段（行查与 COUNT 同口径，防漂移）。prefix 为列限定符（"" 或 "t."）； org/platform/query
	 * 参数仅在对应子句出现时由 {@link #bindReviewQueueFilters} 绑定。
	 */
	private static String reviewQueuePredicates(String prefix, String organizationId, String platform, boolean overdue,
			String query) {
		StringBuilder sql = new StringBuilder();
		if (organizationId != null && !organizationId.isBlank()) {
			sql.append(" AND ").append(prefix).append("organization_id = CAST(:org AS uuid)");
		}
		if (platform != null && !platform.isBlank()) {
			sql.append(" AND ").append(prefix).append("platform = :platform");
		}
		if (overdue) {
			sql.append(" AND ").append(prefix).append("status = 'pending_review'").append(" AND ").append(prefix)
					.append("updated_at < now() - interval '24 hours'");
		}
		if (query != null) {
			sql.append(" AND lower(coalesce(").append(prefix).append("title,'') || ' ' || coalesce(").append(prefix)
					.append("description,'')) LIKE lower(:query) ESCAPE E'\\\\'");
		}
		return sql.toString();
	}

	/** 只绑定谓词中实际出现的命名参数（缺失标识符会抛 NoSuchElementException，见 {@link #findFeed} 注释）。 */
	private static GenericExecuteSpec bindReviewQueueFilters(GenericExecuteSpec spec, String organizationId,
			String platform, String query) {
		if (organizationId != null && !organizationId.isBlank())
			spec = spec.bind("org", organizationId);
		if (platform != null && !platform.isBlank())
			spec = spec.bind("platform", platform);
		if (query != null)
			spec = spec.bind("query", query);
		return spec;
	}

	public Mono<Task> findById(String id) {
		return db.sql("SELECT " + SELECT_COLS + " FROM task WHERE id = CAST(:id AS uuid)").bind("id", id)
				.map(TaskRepository::map).one();
	}

	/**
	 * 全局任务大厅（GL-P1-TASK-001 Stage 2）：跨组织 feed，仅 published 且未截止，可选筛选 + keyset 游标分页。
	 *
	 * <p>
	 * 排序 {@code (created_at DESC, id DESC)} 稳定；游标 = 上一页最后一行的
	 * {@code (created_at, id)}，
	 * 取「更老」的下一页（{@code (created_at, id) < (cursorTs, cursorId)}，DESC 下即游标之后的行）。
	 * {@code cursor} 为 null 时首页（不带 keyset 谓词，避免 null/值混合绑定的类型歧义）。 {@code limit} 已
	 * +1（调用方据此判 hasMore）；返回行数 ≤ limit。
	 */
	public Flux<Task> findFeed(FeedFilter filter, Instant cursorTs, String cursorId, int limit) {
		boolean firstPage = cursorTs == null;
		String predicate = "status = 'published'"
				+ " AND (application_deadline IS NULL OR application_deadline > now())"
				+ " AND min_recommender_level <= :recommenderLevel"
				// #26 D6：满员任务不展示（SLOTS_AVAILABLE_PREDICATE，与自动接受扫描同口径）
				+ SLOTS_AVAILABLE_PREDICATE + (filter.platform() != null ? " AND platform = :platform" : "")
				+ (filter.contentForm() != null ? " AND content_form = :contentForm" : "")
				+ (filter.minBountyCents() != null
						? " AND bounty_cents IS NOT NULL AND bounty_cents >= :minBountyCents"
						: "")
				+ (filter.nearbyStoreIds() != null ? " AND store_id::text IN (:nearbyStoreIds)" : "")
				+ (filter.query() != null
						? " AND lower(coalesce(title,'') || ' ' || coalesce(description,''))"
								+ " LIKE lower(:query) ESCAPE E'\\\\'"
						: "")
				+ (firstPage
						? ""
						: " AND (created_at, id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid))");
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
		if (filter.query() != null) {
			spec = spec.bind("query", filter.query());
		}
		if (!firstPage) {
			spec = spec.bind("cursorTs", cursorTs.atOffset(ZoneOffset.UTC)).bind("cursorId", cursorId);
		}
		return spec.map(TaskRepository::map).all();
	}

	/**
	 * 列某 org 的组织级任务（不含 store-scoped 行）；status 为空则不限。
	 *
	 * <p>
	 * 任务书 #53：LEFT JOIN LATERAL 带出每任务最新一条 task_review（无审核记录时字段归 null，行不丢）；
	 * controller 的 toBody 仅在「任务仍 draft 且最新记录为 rejected」时暴露商家端驳回字段， 避免已上架任务泄漏历史驳回。
	 */
	public Flux<Task> findByOrganization(String organizationId, String status, String query) {
		return listByOrganization(organizationId, status, query, true);
	}

	/**
	 * 商家 owner 全量视角：该 org 的组织级 + 全部门店任务一并返回（status 为空则不限）。
	 *
	 * <p>
	 * 门店任务的管理入口在主体工作台（发布表单的「资源范围」决定挂主体还是挂店）， 列表不能按资源范围隐式过滤，否则门店任务「凭空消失」；同时沿用 review
	 * join， 让被驳回的门店草稿也能回带真实驳回原因。
	 */
	public Flux<Task> findAllScopesByOrganization(String organizationId, String status, String query) {
		return listByOrganization(organizationId, status, query, false);
	}

	private Flux<Task> listByOrganization(String organizationId, String status, String query, boolean orgLevelOnly) {
		String scopePredicate = orgLevelOnly ? " AND t.store_id IS NULL" : "";
		String search = query == null
				? ""
				: " AND lower(coalesce(t.title,'') || ' ' || coalesce(t.description,''))"
						+ " LIKE lower(:query) ESCAPE E'\\\\'";
		String reviewCols = ", tr.action AS last_review_action, tr.review_note AS last_review_note,"
				+ " tr.created_at AS last_review_at";
		if (status == null || status.isBlank()) {
			var spec = db.sql("SELECT " + SELECT_COLS_REVIEW + reviewCols + " FROM task t" + LATEST_REVIEW_JOIN
					+ " WHERE t.organization_id = CAST(:org AS uuid)" + scopePredicate + search
					+ " ORDER BY t.created_at DESC").bind("org", organizationId);
			if (query != null)
				spec = spec.bind("query", query);
			return spec.map(TaskRepository::mapWithReview).all();
		}
		var spec = db.sql("SELECT " + SELECT_COLS_REVIEW + reviewCols + " FROM task t" + LATEST_REVIEW_JOIN
				+ " WHERE t.organization_id = CAST(:org AS uuid)" + scopePredicate + " AND t.status = :status" + search
				+ " ORDER BY t.created_at DESC").bind("org", organizationId).bind("status", status);
		if (query != null)
			spec = spec.bind("query", query);
		return spec.map(TaskRepository::mapWithReview).all();
	}

	/** 列某一门店的任务；调用方必须先完成 Identity 门店授权。 */
	public Flux<Task> findByStore(String organizationId, String storeId, String status, String query) {
		String statusPredicate = status == null || status.isBlank() ? "" : " AND status = :status";
		String search = query == null
				? ""
				: " AND lower(coalesce(title,'') || ' ' || coalesce(description,''))"
						+ " LIKE lower(:query) ESCAPE E'\\\\'";
		var spec = db
				.sql("SELECT " + SELECT_COLS + " FROM task"
						+ " WHERE organization_id = CAST(:org AS uuid) AND store_id = CAST(:store AS uuid)"
						+ statusPredicate + search + " ORDER BY created_at DESC")
				.bind("org", organizationId).bind("store", storeId);
		if (!statusPredicate.isEmpty()) {
			spec = spec.bind("status", status);
		}
		if (query != null)
			spec = spec.bind("query", query);
		return spec.map(TaskRepository::map).all();
	}

	/**
	 * 任务书 #27：查找已发布且开启了自动通过门槛的任务（未截止、未关闭）。dispatcher 每轮扫描用。 #26
	 * D7：满员任务不再被扫描——省去每轮空转出 slots_full 的拒绝（SLOTS_AVAILABLE_PREDICATE，与 feed 同口径）。
	 */
	public Flux<Task> findAutoAcceptEnabled(int limit) {
		return db
				.sql("SELECT " + SELECT_COLS
						+ " FROM task WHERE status = 'published' AND auto_accept_min_level IS NOT NULL"
						+ " AND (application_deadline IS NULL OR application_deadline > now())"
						+ SLOTS_AVAILABLE_PREDICATE + " ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED")
				.bind("limit", Math.max(1, Math.min(limit, 200))).map(TaskRepository::map).all();
	}

	/**
	 * 某 org 当前 published 的任务数——发布限额执行用（Stage 1：只 published 算活跃，draft/cancelled 不占）。
	 */
	public Mono<Integer> countActiveByOrganization(String organizationId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM task"
						+ " WHERE organization_id = CAST(:org AS uuid) AND status = 'published'")
				.bind("org", organizationId).map(r -> r.get("c", Integer.class)).one();
	}

	/**
	 * 某 org <b>本自然月</b>发布的任务数（按 published_at；draft 创建不计）——月度发布限额执行用（D-05）。 月份边界按 DB
	 * 时区 {@code date_trunc('month', now())}；跨月自动重置。
	 */
	public Mono<Integer> countCreatedThisMonthByOrganization(String organizationId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM task"
						+ " WHERE organization_id = CAST(:org AS uuid) AND published_at IS NOT NULL"
						+ " AND published_at >= date_trunc('month', now())")
				.bind("org", organizationId).map(r -> r.get("c", Integer.class)).one();
	}

	// ---------- 任务书 #75：套餐推广任务 ----------

	/** 套餐当前是否已被进行中推广任务占用（excludeTaskId=编辑场景排除自身）。 */
	public Mono<Integer> countActivePromotionsByPackage(String packageId, String excludeTaskId) {
		var spec = db.sql("SELECT COUNT(*)::int AS c FROM task" + " WHERE commerce_package_id = CAST(:pkg AS uuid)"
				+ " AND status IN ('draft', 'pending_review', 'published')"
				+ (excludeTaskId == null ? "" : " AND id <> CAST(:exclude AS uuid)")).bind("pkg", packageId);
		if (excludeTaskId != null) {
			spec = spec.bind("exclude", excludeTaskId);
		}
		return spec.map(r -> r.get("c", Integer.class)).one();
	}

	/**
	 * 下架套餐联动（任务书 #75 D1 派生 2）：把该套餐的进行中推广任务转为截止终态——等价商家手动 close，
	 * 无版本守卫（系统联动不参与用户乐观锁）；0 行 = 无进行中任务（不 409 打断商家下架）。
	 */
	public Mono<Task> closeActivePromotionByPackage(String packageId) {
		return db.sql("""
				UPDATE task SET status = 'closed', version = version + 1, updated_at = now()
				WHERE commerce_package_id = CAST(:pkg AS uuid)
				  AND status IN ('draft', 'pending_review', 'published')
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("pkg", packageId).map(TaskRepository::map).one();
	}

	/** 套餐当前进行中（published）推广任务 id——下单快照/归因闸用；无则 empty。 */
	public Mono<String> findPublishedPromotionTaskId(String packageId) {
		return db
				.sql("SELECT id::text FROM task"
						+ " WHERE commerce_package_id = CAST(:pkg AS uuid) AND status = 'published'"
						+ " ORDER BY created_at DESC LIMIT 1")
				.bind("pkg", packageId).map(row -> row.get("id", String.class)).one();
	}

	/** 归因资格闸（D4/D5）：该推荐官是否持有「此套餐进行中推广任务」的 accepted 报名。 */
	public Mono<Boolean> hasAcceptedPromotionApplication(String packageId, String recommenderAccountId) {
		return db.sql("""
				SELECT 1 AS ok
				FROM task_application a JOIN task t ON t.id = a.task_id
				WHERE t.commerce_package_id = CAST(:pkg AS uuid)
				  AND t.status = 'published'
				  AND a.recommender_account_id = CAST(:rec AS uuid)
				  AND a.status = 'accepted'
				LIMIT 1
				""").bind("pkg", packageId).bind("rec", recommenderAccountId).map(row -> true).one()
				.defaultIfEmpty(false);
	}

	/**
	 * Serializes quota check + publish for one organization inside the caller's
	 * transaction. The UUID text is hashed to a stable PostgreSQL advisory-lock
	 * key; the lock is released on commit/rollback.
	 */
	public Mono<Void> acquireOrganizationPublishLock(String organizationId) {
		return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:organizationId, 0))")
				.bind("organizationId", organizationId).then();
	}

	/**
	 * 落一行不可变 task_version 快照（HLD §5.3）。version 取 task 当前 version（publish 后已 +1）。
	 */
	private Mono<Void> appendVersion(Task task, String publishedBy) {
		var spec = db.sql("""
				INSERT INTO task_version(task_id, version, store_id, title, description, content_form, platform,
				                         max_slots, bounty_cents, application_deadline, published_at, published_by,
				                         min_recommender_level, requirements, freebie_deposit_cents,
				                         question_text, question_ref)
				VALUES (CAST(:taskId AS uuid), :version, CAST(:store AS uuid), :title, :desc, :contentForm, :platform,
				        :maxSlots, :bountyCents, :deadline, COALESCE(:publishedAt, now()), CAST(:publishedBy AS uuid),
				        :minLevel, CAST(:requirements AS jsonb), :freebieDeposit, :questionText, :questionRef)
				""").bind("taskId", task.id()).bind("version", task.version()).bind("title", task.title());
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
		spec = bindQuestion(spec, task.question());
		return spec.then();
	}

	private Mono<Task> transition(String id, int expectedVersion, String fromStatus, String toStatus) {
		return db.sql("""
				UPDATE task SET status = :status, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = :from AND version = :expected
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("expected", expectedVersion).bind("from", fromStatus)
				.bind("status", toStatus).map(TaskRepository::map).one();
	}

	/**
	 * 绑目标问题两列（任务书 #62）。null 归一为 {@link TaskQuestion#none()}，两列各自 允许 null——R2DBC 的
	 * null 必须走 {@code bindNull}，直接 bind(null) 会 NPE。
	 */
	private static GenericExecuteSpec bindQuestion(GenericExecuteSpec spec, TaskQuestion question) {
		TaskQuestion value = TaskQuestion.orNone(question);
		spec = bindNullable(spec, "questionText", value.text());
		return bindNullable(spec, "questionRef", value.ref());
	}

	private static Task map(Readable row) {
		return mapBase(row, null, null, null);
	}

	/** 带最新审核记录列的行映射（仅用于 LATERAL join task_review 的查询）。 */
	private static Task mapWithReview(Readable row) {
		return mapBase(row, row.get("last_review_action", String.class), row.get("last_review_note", String.class),
				toInstant(row.get("last_review_at", OffsetDateTime.class)));
	}

	private static Task mapBase(Readable row, String lastReviewAction, String lastReviewNote, Instant lastReviewAt) {
		return new Task(row.get("id", String.class), row.get("owner_account_id", String.class),
				row.get("organization_id", String.class), row.get("title", String.class),
				row.get("description", String.class), row.get("status", String.class),
				row.get("content_form", String.class), row.get("platform", String.class),
				row.get("max_slots", Integer.class), row.get("bounty_cents", Long.class),
				toInstant(row.get("created_at", OffsetDateTime.class)),
				toInstant(row.get("updated_at", OffsetDateTime.class)), value(row.get("version", Integer.class), 1),
				toInstant(row.get("application_deadline", OffsetDateTime.class)),
				toInstant(row.get("published_at", OffsetDateTime.class)),
				toInstant(row.get("cancelled_at", OffsetDateTime.class)),
				value(row.get("min_recommender_level", Integer.class), 1), row.get("store_id", String.class),
				requirements(row.get("requirements", String.class)), row.get("auto_accept_min_level", Integer.class),
				row.get("freebie_deposit_cents", Long.class), lastReviewAction, lastReviewNote, lastReviewAt,
				new TaskQuestion(row.get("question_text", String.class), row.get("question_ref", String.class)),
				row.get("commerce_package_id", String.class));
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
