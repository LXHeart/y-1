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
 * 商家主体子账号服务（任务书 #48）。
 *
 * <p><b>建号</b>（D1/D2/D5）：owner/admin 直建任意角色；店长仅能代建本店 staff（D6 开关决定
 * active / pending_review）。一次事务完成「插 app_users + 插成员行 + 置首登改密标记 + outbox」。
 * 邮箱已存在时不静默绑定——409 要求管理员显式 {@code confirmBindExisting}，关联路径
 * <b>绝不触碰既有凭据</b>；店长代建不开放绑定（走既有邀请流）。
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
        RoleTarget target = RoleTarget.of(req);
        return ensureStoreInOrg(organizationId, target)
                .then(doCreate(operatorAccountId, organizationId, target, "active", true));
    }

    /** 店长代建本店 staff：审核开关 on → pending_review，off → active（D6）。 */
    public Mono<CreatedSubAccount> createStaffByManager(String operatorAccountId, String organizationId,
            String storeId, CreateSubAccountRequest req) {
        RoleTarget fixedStaff = new RoleTarget("staff", normalizedEmail(req.email()), req.displayName(),
                storeId, Boolean.FALSE);
        Mono<String> statusMono = organizations.selectMemberReviewRequired(organizationId)
                .defaultIfEmpty(Boolean.FALSE)
                .map(reviewRequired -> Boolean.TRUE.equals(reviewRequired) ? "pending_review" : "active");
        return ensureStoreInOrg(organizationId, fixedStaff)
                .then(statusMono.flatMap(status -> doCreate(operatorAccountId, organizationId, fixedStaff,
                        status, false)));
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

    private Mono<CreatedSubAccount> doCreate(String operatorAccountId, String organizationId, RoleTarget target,
            String status, boolean adminCreated) {
        return users.findByEmail(target.email())
                .flatMap(existing -> bindExistingPath(operatorAccountId, organizationId, target, existing,
                        adminCreated))
                // switchIfEmpty 参数在装配期求值：副作用必须包 defer，否则每次装配都会 hash 一次 Argon2。
                .switchIfEmpty(Mono.defer(() -> createNewAccount(operatorAccountId, organizationId, target, status)));
    }

    private Mono<CreatedSubAccount> bindExistingPath(String operatorAccountId, String organizationId,
            RoleTarget target, com.grassland.identity.user.LoginUser existing, boolean adminCreated) {
        if (!adminCreated || !Boolean.TRUE.equals(target.confirmBindExisting())) {
            return Mono.error(new IdentityException(409,
                    "该邮箱已是平台账号：换个邮箱重建，或由管理员确认后直接关联为成员"));
        }
        return transactions.transactional(
                grantMembership(organizationId, existing.id(), target)
                        .onErrorMap(DataIntegrityViolationException.class,
                                e -> new IdentityException(409, "该账号已是该范围的成员"))
                        .then(appendCreatedEvent(organizationId, existing.id(), target.role(), target.storeId(),
                                operatorAccountId, existing.status()))
                        .thenReturn(new CreatedSubAccount(existing.id(), existing.email(),
                                existing.displayName(), target.role(), existing.status(), null)));
    }

    private Mono<CreatedSubAccount> createNewAccount(String operatorAccountId, String organizationId,
            RoleTarget target, String status) {
        String userId = UUID.randomUUID().toString();
        String initialPassword = PasswordGenerator.generate();
        // 与 RegisterController 同款约束：Argon2 是 64MB/3 轮重操作，必须离开 Netty 事件循环。
        return Mono.fromCallable(() -> argon2Hasher.hash(initialPassword))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(hash -> transactions.transactional(db
                        .sql("""
                                INSERT INTO app_users(id, email, password_hash, display_name, role, status)
                                VALUES (CAST(:id AS uuid), :email, :hash, :name, 'user', :status)
                                ON CONFLICT (email) DO NOTHING RETURNING id::text
                                """)
                        .bind("id", userId).bind("email", target.email()).bind("hash", hash)
                        .bind("name", target.displayName()).bind("status", status)
                        .map(r -> r.get(0, String.class)).one()
                        // 存在性检查与 INSERT 不同事务，并发注册同一邮箱落到这里时对齐注册流的 409 口径。
                        .switchIfEmpty(Mono.error(new IdentityException(409, "该邮箱已被并发创建，请重试")))
                        .flatMap(uid -> grantMembership(organizationId, uid, target)
                                .onErrorMap(DataIntegrityViolationException.class,
                                        e -> new IdentityException(409, "该账号已是该范围的成员"))
                                .then(flags.markMustChangePassword(uid))
                                .then(appendCreatedEvent(organizationId, uid, target.role(), target.storeId(),
                                        operatorAccountId, status))
                                .thenReturn(new CreatedSubAccount(uid, target.email(), target.displayName(),
                                        target.role(), status, initialPassword)))));
    }

    private Mono<String> grantMembership(String organizationId, String accountId, RoleTarget target) {
        if (target.requiresStore()) {
            return storeMemberships.create(target.storeId(), accountId, target.role()).map(m -> m.id());
        }
        return orgMemberships.create(organizationId, accountId, "member").map(Membership::id);
    }

    private Mono<Void> appendCreatedEvent(String organizationId, String accountId, String role, String storeId,
            String createdBy, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("accountId", accountId);
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
                    return requireOperatorAuthority(operatorAccountId, organizationId, targetAccountId)
                            .then(executeSuspension(organizationId, targetAccountId, action))
                            .then(notifySuspensionChanged(organizationId, targetAccountId, operatorAccountId,
                                    action));
                });
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
                                        new IdentityException(409, current.equals(to) ? "已是该状态" : conflictMessage))));
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
                .then(approve
                        ? guardedStatusUpdate(targetAccountId, "active", "pending_review", "账号不在待审状态")
                                .then(appendReviewedEvent(organizationId, targetAccountId, operatorAccountId, true))
                        : guardedStatusUpdate(targetAccountId, "rejected", "pending_review", "账号不在待审状态")
                                .then(appendReviewedEvent(organizationId, targetAccountId, operatorAccountId, false)));
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
                        .flatMap(account -> {
                            String freshPassword = PasswordGenerator.generate();
                            return Mono.fromCallable(() -> argon2Hasher.hash(freshPassword))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(hash -> db.sql(
                                                    "UPDATE app_users SET password_hash = :hash, updated_at = NOW()"
                                                            + " WHERE id = CAST(:id AS uuid)")
                                            .bind("hash", hash).bind("id", targetAccountId)
                                            .fetch().rowsUpdated()
                                            .then(flags.markMustChangePassword(targetAccountId))
                                            .thenReturn(new CreatedSubAccount(targetAccountId, account.email(),
                                                    account.displayName(), null, account.status(), freshPassword)));
                        }));
    }

    // ---------- 公共小件 ----------

    private Mono<Void> ensureTargetInOrg(String targetAccountId, String organizationId) {
        return orgAuthz.roleOfAccount(targetAccountId, organizationId).hasElement()
                .flatMap(inOrgAsMemberOrOwner -> inOrgAsMemberOrOwner ? Mono.<Void>empty()
                        : storeMemberships.existsByAccountAndOrganization(targetAccountId, organizationId)
                                .flatMap(exists -> exists ? Mono.<Void>empty()
                                        : Mono.error(new IdentityException(404, "账号不属于该组织"))));
    }

    private static String normalizedEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 254 || !email.contains("@")) {
            throw new IdentityException(400, "邮箱格式不正确");
        }
        return email.trim().toLowerCase();
    }

    /**
     * 建号目标：role/displayName 在构造期校验（record 收敛约束）。email 由工厂方法先行规整，
     * 店长路径绕过用户输入的 role 字段、锁死 staff。
     */
    record RoleTarget(String role, String email, String displayName, String storeId, Boolean confirmBindExisting) {

        private static final java.util.Set<String> ALLOWED_ROLES = java.util.Set.of("member", "manager", "staff");

        static RoleTarget of(CreateSubAccountRequest req) {
            return new RoleTarget(req.role(), normalizedEmail(req.email()), req.displayName(), req.storeId(),
                    req.confirmBindExisting());
        }

        RoleTarget {
            if (role == null || !ALLOWED_ROLES.contains(role)) {
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

    /** 建号结果；{@code initialPassword} 仅新建时有值且只在本次响应出现一次（D2），绑定路径为 null。 */
    public record CreatedSubAccount(String accountId, String email, String displayName, String role,
            String status, String initialPassword) {
    }
}
