package com.grassland.identity.organization.subaccount;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.Membership;
import com.grassland.identity.membership.MembershipRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.OrganizationRepository;
import com.grassland.identity.security.Argon2PasswordHasher;
import com.grassland.identity.store.StoreMembershipRepository;
import com.grassland.identity.store.StoreRepository;
import com.grassland.identity.user.AccountFlagRepository;
import com.grassland.identity.user.UserLookup;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 商家主体子账号服务（任务书 #48；#49 单一模型改造）。
 *
 * <p><b>建号</b>（D1/D2；#49 D4/D6）：owner/admin 直建任意角色；店长仅能代建本店 staff
 * （D6 开关决定 active / pending_review）。一次事务完成「插 app_users（占位邮箱）+ 写
 * account_username 登录名 + 插成员行 + 置首登改密标记 + outbox」。登录名 =
 * {@code 主体前缀-登录名}（各段 ^[a-z0-9]{3,24}$）；#49 起建号不填邮箱，撞名 409 换名，
 * #48 的 confirmBindExisting 关联既有账号分支已随挂靠通路下线删除。
 *
 * <p><b>状态真相源 = app_users.status</b>（D4）：{@code active / suspended / pending_review /
 * rejected}。停用即时生效依赖平台两道既有闸（edge 每请求 {@code lower(status)='active'} 过滤、
 * identity resolver 403），本服务只负责改值与守卫。恢复只接受 suspended → active；
 * rejected 是终态，restore 无法复活。
 *
 * <p><b>四守卫</b>（D8）：①每店最后一个 active MANAGER 不可停；②org OWNER 账号不可被经此停用；
 * ③不可操作自己；④重复成员关系 409（沿用 UNIQUE 冲突口径）。
 */
@Service
public class OrgSubAccountService {

    /** 登录名各段规则（D4）：仅小写字母数字、3–24 位；输入大写先归一。 */
    static final java.util.regex.Pattern LOGIN_NAME_PATTERN = java.util.regex.Pattern.compile("^[a-z0-9]{3,24}$");

    /** 占位邮箱域（D6）：RFC 保留 TLD，不与真实邮箱冲突；外部邮件外发在 MailOutboxEnqueuer 短路。 */
    static final String PLACEHOLDER_EMAIL_SUFFIX = "@sub.grassland.invalid";

    private final DatabaseClient db;
    private final TransactionalOperator transactions;
    private final Argon2PasswordHasher argon2Hasher;
    private final OutboxRepository outbox;
    private final OrganizationRepository organizations;
    private final MembershipRepository orgMemberships;
    private final StoreMembershipRepository storeMemberships;
    private final StoreRepository stores;
    private final OrgAuthorization orgAuthz;
    private final AccountFlagRepository flags;
    private final UserLookup users;

    public OrgSubAccountService(DatabaseClient db, TransactionalOperator transactions,
            Argon2PasswordHasher argon2Hasher, OutboxRepository outbox, OrganizationRepository organizations,
            MembershipRepository orgMemberships, StoreMembershipRepository storeMemberships,
            StoreRepository stores, OrgAuthorization orgAuthz, AccountFlagRepository flags, UserLookup users) {
        this.db = db;
        this.transactions = transactions;
        this.argon2Hasher = argon2Hasher;
        this.outbox = outbox;
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.storeMemberships = storeMemberships;
        this.stores = stores;
        this.orgAuthz = orgAuthz;
        this.flags = flags;
        this.users = users;
    }

    // ---------- 建号 ----------

    /** owner/admin 直建：任意角色，永不 pending（D6）。role=member 时不得带 storeId。 */
    public Mono<CreatedSubAccount> createByOrg(String operatorAccountId, String organizationId,
            CreateSubAccountRequest req) {
        return prepareTarget(organizationId, req)
                .flatMap(target -> ensureStoreInOrg(organizationId, target)
                        .then(doCreate(operatorAccountId, organizationId, target, "active")));
    }

    /** 店长代建本店 staff：审核开关 on → pending_review，off → active（D6）。 */
    public Mono<CreatedSubAccount> createStaffByManager(String operatorAccountId, String organizationId,
            String storeId, CreateSubAccountRequest req) {
        return prepareTarget(organizationId, req).flatMap(input -> {
            RoleTarget fixedStaff = new RoleTarget("staff", input.loginName(), input.displayName(), storeId);
            Mono<String> statusMono = organizations.selectMemberReviewRequired(organizationId)
                    .defaultIfEmpty(Boolean.FALSE)
                    .map(reviewRequired -> Boolean.TRUE.equals(reviewRequired) ? "pending_review" : "active");
            return ensureStoreInOrg(organizationId, fixedStaff)
                    .then(statusMono.flatMap(status -> doCreate(operatorAccountId, organizationId, fixedStaff,
                            status)));
        });
    }

    /** 先读主体前缀再拼完整登录名（D4/D5）：前缀缺失是异常态（V43 NOT NULL 兜底），建号直接 409。 */
    private Mono<RoleTarget> prepareTarget(String organizationId, CreateSubAccountRequest req) {
        String loginName = normalizedLoginName(req.loginName());
        if (req.role() == null || !java.util.Set.of("member", "manager", "staff").contains(req.role())) {
            return Mono.error(new IdentityException(400, "role 仅支持 member/manager/staff"));
        }
        if (req.displayName() == null || req.displayName().isBlank() || req.displayName().length() > 64) {
            return Mono.error(new IdentityException(400, "显示名必填且不超过 64 字"));
        }
        return organizations.selectAccountPrefix(organizationId)
                .switchIfEmpty(Mono.error(new IdentityException(409, "主体账号前缀缺失，请联系平台方")))
                .map(prefix -> new RoleTarget(req.role(), prefix + "-" + loginName, req.displayName(),
                        req.storeId()));
    }

    private Mono<Void> ensureStoreInOrg(String organizationId, RoleTarget target) {
        if (!target.requiresStore()) {
            if (target.storeId() != null && !target.storeId().isBlank()) {
                return Mono.error(new IdentityException(400, "member 角色不需要指定门店"));
            }
            return Mono.empty();
        }
        if (target.storeId() == null || target.storeId().isBlank()) {
            return Mono.error(new IdentityException(400, "manager/staff 角色必须指定门店"));
        }
        return stores.findByOrganizationAndId(organizationId, target.storeId())
                .switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在")))
                .then();
    }

    /**
     * #49 单条建号路径（挂靠关联分支已删）：占位邮箱插 app_users + 登录名旁表 + 成员行 +
     * 首登改密标记 + outbox，全程一个事务。登录名撞（同主体或跨主体）→ 409 换名。
     */
    private Mono<CreatedSubAccount> doCreate(String operatorAccountId, String organizationId, RoleTarget target,
            String status) {
        String userId = UUID.randomUUID().toString();
        String initialPassword = PasswordGenerator.generate();
        String placeholderEmail = target.loginName() + PLACEHOLDER_EMAIL_SUFFIX;
        // 与 RegisterController 同款约束：Argon2 是 64MB/3 轮重操作，必须离开 Netty 事件循环。
        return Mono.fromCallable(() -> argon2Hasher.hash(initialPassword))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(hash -> transactions.transactional(db
                        .sql("""
                                INSERT INTO app_users(id, email, password_hash, display_name, role, status)
                                VALUES (CAST(:id AS uuid), :email, :hash, :name, 'user', :status)
                                ON CONFLICT (email) DO NOTHING RETURNING id::text
                                """)
                        .bind("id", userId).bind("email", placeholderEmail).bind("hash", hash)
                        .bind("name", target.displayName()).bind("status", status)
                        .map(r -> r.get(0, String.class)).one()
                        // 0 行 = 占位邮箱已存在（同登录名并发建号）→ 409 换名
                        .switchIfEmpty(Mono.error(new IdentityException(409, "该登录名已被使用，请换一个")))
                        .flatMap(uid -> db.sql("""
                                INSERT INTO account_username(account_id, username)
                                VALUES (CAST(:id AS uuid), :username)
                                """)
                                .bind("id", uid).bind("username", target.loginName())
                                .fetch().rowsUpdated()
                                // username UNIQUE 冲突（与 app_users 占位冲突同源，事务序不同到达）→ 409 换名
                                .onErrorMap(DataIntegrityViolationException.class,
                                        e -> new IdentityException(409, "该登录名已被使用，请换一个"))
                                .then(grantMembership(organizationId, uid, target)
                                        .onErrorMap(DataIntegrityViolationException.class,
                                                e -> new IdentityException(409, "该账号已是该范围的成员")))
                                .then(flags.markMustChangePassword(uid))
                                .then(appendCreatedEvent(organizationId, uid, target.role(), target.storeId(),
                                        operatorAccountId, status, target.loginName()))
                                .thenReturn(new CreatedSubAccount(uid, target.loginName(), target.displayName(),
                                        target.role(), status, initialPassword)))));
    }

    private Mono<String> grantMembership(String organizationId, String accountId, RoleTarget target) {
        if (target.requiresStore()) {
            return storeMemberships.create(target.storeId(), accountId, target.role()).map(m -> m.id());
        }
        return orgMemberships.create(organizationId, accountId, "member").map(Membership::id);
    }

    private Mono<Void> appendCreatedEvent(String organizationId, String accountId, String role, String storeId,
            String createdBy, String status, String username) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("accountId", accountId);
        payload.put("username", username);
        payload.put("role", role);
        if (storeId != null && !storeId.isBlank()) {
            payload.put("storeId", storeId);
        }
        payload.put("createdBy", createdBy);
        payload.put("status", status);
        EventEnvelope event = new EventEnvelope(UUID.randomUUID().toString(), "OrgSubAccountCreated",
                "OrganizationSubAccount", accountId, 1, Instant.now(), null, payload);
        return outbox.append(event);
    }

    // ---------- 停用 / 恢复（D7）----------

    public Mono<Void> suspend(String operatorAccountId, String organizationId, String targetAccountId) {
        return changeSuspension(operatorAccountId, organizationId, targetAccountId, "suspended");
    }

    public Mono<Void> restore(String operatorAccountId, String organizationId, String targetAccountId) {
        return changeSuspension(operatorAccountId, organizationId, targetAccountId, "active");
    }

    private Mono<Void> changeSuspension(String operatorAccountId, String organizationId, String targetAccountId,
            String action) {
        if (operatorAccountId.equals(targetAccountId)) {
            return Mono.error(new IdentityException(403, "不能操作自己的账号"));
        }
        return organizations.findById(organizationId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                .flatMap(org -> {
                    if ("suspended".equals(action) && targetAccountId.equals(org.ownerAccountId())) {
                        return Mono.error(new IdentityException(403, "商家主体所有者的账号不可被停用"));
                    }
                    // D8：deleted 是终态，restore 必须给出明确 409（先于关系守卫——关系已清时
                    // ensureTargetInOrg 会 404，误导操作者以为查错了组织）
                    return rejectDeletedTerminal(targetAccountId, "账号已删除，不可恢复")
                            .then(requireOperatorAuthority(operatorAccountId, organizationId, targetAccountId))
                            // 状态变更与审计/知会事件同事务（D12）：中途失败不能留下「已停用却无事件」的状态
                            .then(transactions.transactional(
                                    executeSuspension(organizationId, targetAccountId, action)
                                            .then(notifySuspensionChanged(organizationId, targetAccountId,
                                                    operatorAccountId, action))));
                });
    }

    /** deleted 终态预检：非 deleted 直接放行（empty）；deleted → 409 专用文案。 */
    private Mono<Void> rejectDeletedTerminal(String targetAccountId, String message) {
        return db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", targetAccountId)
                .map(row -> row.get("status", String.class)).one()
                .switchIfEmpty(Mono.error(new IdentityException(404, "账号不存在")))
                .flatMap(status -> "deleted".equalsIgnoreCase(status)
                        ? Mono.error(new IdentityException(409, message))
                        : Mono.<Void>empty());
    }

    /**
     * 操作者权限（D7）：org ADMIN+/owner 可动任意成员（含经理）；其余「在本组织有门店身份」者按
     * 纯门店 MANAGER 规则——只能动在本组织仅有 staff 角色的目标。目标与本组织无关 → 404
     * （跨主体隔离，对齐 StoreAuthorization 风格）。
     */
    private Mono<Void> requireOperatorAuthority(String operatorAccountId, String organizationId,
            String targetAccountId) {
        // hasElement() 把判定收敛成恒发射的 Boolean：Void 链的成功空信号绝不能作为分支依据，
        // 否则会被下游 switchIfEmpty 误判为「无角色」而重入店长分支（实测踩坑）。
        Mono<Boolean> adminPlus = orgAuthz.roleOfAccount(operatorAccountId, organizationId)
                .filter(role -> role.isAtLeast(MembershipRole.ADMIN))
                .hasElement();
        return ensureTargetInOrg(targetAccountId, organizationId)
                .then(adminPlus.flatMap(isAdminPlus -> isAdminPlus
                        ? Mono.<Void>empty()
                        : managerOnlyAuthority(operatorAccountId, organizationId, targetAccountId)));
    }

    private Mono<Void> managerOnlyAuthority(String operatorAccountId, String organizationId,
            String targetAccountId) {
        return storeMemberships.findDistinctRolesByAccountInOrg(operatorAccountId, organizationId)
                .collectList()
                .flatMap(operatorRoles -> operatorRoles.contains("manager")
                        ? Mono.empty()
                        : Mono.<Void>error(new IdentityException(403, "权限不足")))
                .then(storeMemberships.findRolesByAccountInOrg(targetAccountId, organizationId).collectList())
                .flatMap(targetRoles -> !targetRoles.isEmpty() && targetRoles.stream().allMatch("staff"::equals)
                        ? Mono.<Void>empty()
                        : Mono.error(new IdentityException(403, "店长只能操作本组织的员工账号")));
    }

    private Mono<Void> executeSuspension(String organizationId, String targetAccountId, String action) {
        if ("suspended".equals(action)) {
            return guardLastActiveManager(organizationId, targetAccountId)
                    .then(guardedStatusUpdate(targetAccountId, "suspended", "active", "当前状态不可停用"));
        }
        return guardedStatusUpdate(targetAccountId, "active", "suspended", "当前状态不可恢复");
    }

    /** 守卫①（D8）：目标在本组织内担任经理的每家店，扣除目标后仍须剩至少一名 active 经理。 */
    private Mono<Void> guardLastActiveManager(String organizationId, String targetAccountId) {
        return storeMemberships.findManagerStoreIdsByAccountInOrg(targetAccountId, organizationId)
                .flatMap(storeId -> storeMemberships.countManagersExcluding(storeId, targetAccountId)
                        .filter(count -> count >= 1)
                        .switchIfEmpty(Mono.error(new IdentityException(409, "不能停用最后一个可用门店经理"))))
                .then();
    }

    /** 单边胜出的 guarded UPDATE：只允许 from→to 迁移；0 行=状态不符，回查现值给可读错误。 */
    private Mono<Void> guardedStatusUpdate(String targetAccountId, String to, String from, String conflictMessage) {
        return db.sql("UPDATE app_users SET status = :to, updated_at = NOW()"
                        + " WHERE id = CAST(:id AS uuid) AND status = :from")
                .bind("to", to).bind("id", targetAccountId).bind("from", from)
                .fetch().rowsUpdated()
                .flatMap(rows -> rows > 0 ? Mono.<Void>empty()
                        : db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
                                .bind("id", targetAccountId)
                                .map(row -> row.get("status", String.class)).one()
                                .switchIfEmpty(Mono.error(new IdentityException(404, "账号不存在")))
                                .<Void>flatMap(current -> Mono.error(
                                        new IdentityException(409, current.equals(to) ? "已是该状态"
                                                : "deleted".equals(current) ? "账号已删除，不可恢复"
                                                        : conflictMessage))));
    }

    private Mono<Void> notifySuspensionChanged(String organizationId, String targetAccountId,
            String operatorAccountId, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("accountId", targetAccountId);
        payload.put("operatorAccountId", operatorAccountId);
        payload.put("action", action);
        EventEnvelope event = new EventEnvelope(UUID.randomUUID().toString(), "MemberSuspensionChanged",
                "OrganizationSubAccount", targetAccountId, 1, Instant.now(), null, payload);
        return outbox.append(event);
    }

    // ---------- 删除（任务书 #49 D8：永久作废，逻辑删除留痕）----------

    /**
     * 删除成员 = 解除本主体下全部组织/门店成员关系 + 账号转 {@code deleted}（{@code deleted_at}
     * 留痕，不物理删行），同事务写 outbox {@code OrgSubAccountDeleted}。不可恢复——restore
     * 对 deleted 一律 409。守卫同停用（自己/owner/最后 active 经理/权限分档）。
     */
    public Mono<Void> deleteSubAccount(String operatorAccountId, String organizationId, String targetAccountId) {
        if (operatorAccountId.equals(targetAccountId)) {
            return Mono.error(new IdentityException(403, "不能删除自己的账号"));
        }
        return organizations.findById(organizationId)
                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                .flatMap(org -> {
                    if (targetAccountId.equals(org.ownerAccountId())) {
                        return Mono.error(new IdentityException(403, "商家主体所有者的账号不可被删除"));
                    }
                    // D8：重删给出明确 409（同 restore 的终态预检理由——关系已清时关系守卫只会 404）
                    return rejectDeletedTerminal(targetAccountId, "账号已删除，不可重复删除")
                            .then(requireOperatorAuthority(operatorAccountId, organizationId, targetAccountId))
                            .then(transactions.transactional(
                                    guardLastActiveManager(organizationId, targetAccountId)
                                            .then(guardedDelete(organizationId, targetAccountId))
                                            .then(appendDeletedEvent(organizationId, targetAccountId,
                                                    operatorAccountId))));
                });
    }

    /** 删除态迁移：仅接受非 deleted 现值；deleted 重删 → 409 终态文案。 */
    private Mono<Void> guardedDelete(String organizationId, String targetAccountId) {
        return db.sql("""
                        UPDATE app_users SET status = 'deleted', deleted_at = NOW(), updated_at = NOW()
                        WHERE id = CAST(:id AS uuid) AND status <> 'deleted'
                        """)
                .bind("id", targetAccountId).fetch().rowsUpdated()
                .flatMap(rows -> rows > 0 ? Mono.<Void>empty()
                        : db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
                                .bind("id", targetAccountId)
                                .map(row -> row.get("status", String.class)).one()
                                .switchIfEmpty(Mono.error(new IdentityException(404, "账号不存在")))
                                .<Void>flatMap(current -> Mono.error(new IdentityException(409,
                                        "deleted".equals(current) ? "账号已删除，不可重复删除" : "当前状态不可删除"))))
                // 关系解除放在状态迁移之后同事务：迁移被守卫拦下时不动任何关系
                .then(db.sql("DELETE FROM organization_membership"
                        + " WHERE organization_id = CAST(:org AS uuid) AND account_id = CAST(:acct AS uuid)")
                        .bind("org", organizationId).bind("acct", targetAccountId).then())
                .then(db.sql("DELETE FROM store_membership WHERE account_id = CAST(:acct AS uuid)"
                        + " AND store_id IN (SELECT id FROM store WHERE organization_id = CAST(:org AS uuid))")
                        .bind("org", organizationId).bind("acct", targetAccountId).then());
    }

    private Mono<Void> appendDeletedEvent(String organizationId, String targetAccountId, String deletedBy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("accountId", targetAccountId);
        payload.put("deletedBy", deletedBy);
        EventEnvelope event = new EventEnvelope(UUID.randomUUID().toString(), "OrgSubAccountDeleted",
                "OrganizationSubAccount", targetAccountId, 1, Instant.now(), null, payload);
        return outbox.append(event);
    }

    // ---------- 审核（D6）----------

    public Mono<Void> review(String operatorAccountId, String organizationId, String targetAccountId,
            SubAccountReviewRequest req) {
        boolean decisionKnown = req.decision() != null
                && (req.isApprove() || "reject".equalsIgnoreCase(req.decision()));
        if (!decisionKnown) {
            return Mono.error(new IdentityException(400, "decision 仅支持 approve/reject"));
        }
        boolean approve = req.isApprove();
        if (operatorAccountId.equals(targetAccountId)) {
            return Mono.error(new IdentityException(403, "不能审批自己"));
        }
        return ensureTargetInOrg(targetAccountId, organizationId)
                .then(requireAdminPlus(operatorAccountId, organizationId))
                // 迁移与审计事件同事务，理由同 changeSuspension
                .then(transactions.transactional(approve
                        ? guardedStatusUpdate(targetAccountId, "active", "pending_review", "账号不在待审状态")
                                .then(appendReviewedEvent(organizationId, targetAccountId, operatorAccountId, true))
                        : guardedStatusUpdate(targetAccountId, "rejected", "pending_review", "账号不在待审状态")
                                .then(appendReviewedEvent(organizationId, targetAccountId, operatorAccountId,
                                        false))));
    }

    /** 账号显式版 ADMIN+ 门禁（内部调用无 request 可解析，与 {@code OrgAuthorization.requireRole} 同判据）。 */
    private Mono<Void> requireAdminPlus(String operatorAccountId, String organizationId) {
        return orgAuthz.roleOfAccount(operatorAccountId, organizationId)
                .filter(role -> role.isAtLeast(MembershipRole.ADMIN))
                .switchIfEmpty(Mono.error(new IdentityException(403, "权限不足")))
                .then();
    }

    private Mono<Void> appendReviewedEvent(String organizationId, String targetAccountId, String reviewedBy,
            boolean approved) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("accountId", targetAccountId);
        payload.put("reviewedBy", reviewedBy);
        payload.put("decision", approved ? "approved" : "rejected");
        EventEnvelope event = new EventEnvelope(UUID.randomUUID().toString(), "StaffCreationReviewed",
                "OrganizationSubAccount", targetAccountId, 1, Instant.now(), null, payload);
        return outbox.append(event);
    }

    // ---------- 密码重置（D3）----------

    /** 管理员重置成员密码：新一次性密码 + 重新置首登强制改密，旧密码即刻失效。 */
    public Mono<CreatedSubAccount> resetPassword(String operatorAccountId, String organizationId,
            String targetAccountId) {
        if (operatorAccountId.equals(targetAccountId)) {
            return Mono.error(new IdentityException(403, "不能重置自己的密码，请走修改密码"));
        }
        return ensureTargetInOrg(targetAccountId, organizationId)
                .then(requireAdminPlus(operatorAccountId, organizationId))
                .then(users.findById(targetAccountId)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "账号不存在")))
                        // 展示名取登录名（子账号），非子账号回退 email（存量挂靠清理前的过渡态）
                        .flatMap(account -> users.findUsernameById(targetAccountId)
                                .defaultIfEmpty(account.email())
                                .flatMap(displayName -> {
                            String freshPassword = PasswordGenerator.generate();
                            return Mono.fromCallable(() -> argon2Hasher.hash(freshPassword))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    // 密码置换与首登改密旗标同事务：只换密码不置旗标会留下
                                    // 「新密码已生效却不强制改密」的窗口（D3 意图落空）
                                    .flatMap(hash -> transactions.transactional(db.sql(
                                                    "UPDATE app_users SET password_hash = :hash, updated_at = NOW()"
                                                            + " WHERE id = CAST(:id AS uuid)")
                                            .bind("hash", hash).bind("id", targetAccountId)
                                            .fetch().rowsUpdated()
                                            .then(flags.markMustChangePassword(targetAccountId)))
                                            .thenReturn(new CreatedSubAccount(targetAccountId, displayName,
                                                    account.displayName(), null, account.status(),
                                                    freshPassword)));
                                })));
    }

    // ---------- 公共小件 ----------

    private Mono<Void> ensureTargetInOrg(String targetAccountId, String organizationId) {
        return orgAuthz.roleOfAccount(targetAccountId, organizationId).hasElement()
                .flatMap(inOrgAsMemberOrOwner -> inOrgAsMemberOrOwner ? Mono.<Void>empty()
                        : storeMemberships.existsByAccountAndOrganization(targetAccountId, organizationId)
                                .flatMap(exists -> exists ? Mono.<Void>empty()
                                        : Mono.error(new IdentityException(404, "账号不属于该组织"))));
    }

    /** 登录名归一（D4）：小写化后必须 ^[a-z0-9]{3,24}$，非法直接 400。 */
    private static String normalizedLoginName(String loginName) {
        if (loginName == null) {
            throw new IdentityException(400, "登录名必填（3-24 位字母或数字）");
        }
        String normalized = loginName.trim().toLowerCase();
        if (!LOGIN_NAME_PATTERN.matcher(normalized).matches()) {
            throw new IdentityException(400, "登录名仅支持 3-24 位字母或数字");
        }
        return normalized;
    }

    /**
     * 建号目标（#49）：loginName 存<b>完整账号名</b>（前缀-登录名，由 {@link #prepareTarget} 拼好），
     * role/displayName 在构造期校验（record 收敛约束）；店长路径绕过用户输入的 role、锁死 staff。
     */
    record RoleTarget(String role, String loginName, String displayName, String storeId) {

        RoleTarget {
            if (role == null || !java.util.Set.of("member", "manager", "staff").contains(role)) {
                throw new IdentityException(400, "role 仅支持 member/manager/staff");
            }
            if (displayName == null || displayName.isBlank() || displayName.length() > 64) {
                throw new IdentityException(400, "显示名必填且不超过 64 字");
            }
        }

        boolean requiresStore() {
            return "manager".equals(role) || "staff".equals(role);
        }
    }

    /**
     * 建号结果；{@code initialPassword} 只在本次响应出现一次（D2）。
     * {@code username} 是完整账号名（前缀-登录名），重置密码路径回填目标账号的用户名。
     */
    public record CreatedSubAccount(String accountId, String username, String displayName, String role,
            String status, String initialPassword) {
    }
}
