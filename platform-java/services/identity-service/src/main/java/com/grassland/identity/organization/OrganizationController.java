package com.grassland.identity.organization;

import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.membership.Membership;
import com.grassland.identity.membership.MembershipRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 商家主体（Organization）HTTP 入口。草场身份域 Slice 2E；Slice 2F 加创建时种 OWNER 成员行。
 *
 * <ul>
 * <li>POST /api/organizations — 创建组织（当前 account 为 owner），种 OWNER 成员行，写 outbox
 * {@code OrganizationCreated} 事件。</li>
 * <li>GET /api/organizations/{id} — 取单个组织。</li>
 * <li>GET /api/organizations — 列出当前 account 名下组织（owner 或成员，含被邀请加入的主体）。</li>
 * </ul>
 *
 * <p>
 * 所有端点经 {@link CurrentAccountResolver} 鉴权（需登录 session）。
 *
 * <p>
 * <b>权限升级不在本 controller。</b>商家准入等级只能经 {@code PermissionRequestController}
 * 的审核工作流变更（org OWNER 提交申请 + 材料校验 → 平台 admin 审核 approve → 升 tier）。曾经存在的
 * {@code POST /{id}/permissions/grant} 是 Slice 2F 的 dev 地基， 允许 org owner
 * 无审核把自己单调升到最高 tier（GL-P0-SEC-002），已随审核流上线删除；不要重新引入。
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

	private final CurrentAccountResolver accounts;
	private final OrganizationRepository organizations;
	private final OrganizationRenameRepository renames;
	private final MembershipRepository memberships;
	private final com.grassland.identity.identityprofile.IdentityProfileRepository identityProfiles;
	private final com.grassland.identity.store.StoreRepository stores;
	private final OrgAuthorization authz;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	/** 更名冷却期（产品规则：一定周期内只能改一次；先取 30 天常量，运营配置化留待后续）。 */
	static final Duration RENAME_COOLDOWN = Duration.ofDays(30);

	public OrganizationController(CurrentAccountResolver accounts, OrganizationRepository organizations,
			OrganizationRenameRepository renames,
			MembershipRepository memberships, OrgAuthorization authz,
			OutboxRepository outbox, TransactionalOperator transactions,
			com.grassland.identity.identityprofile.IdentityProfileRepository identityProfiles,
			com.grassland.identity.store.StoreRepository stores) {
		this.accounts = accounts;
		this.organizations = organizations;
		this.renames = renames;
		this.memberships = memberships;
		this.authz = authz;
		this.outbox = outbox;
		this.transactions = transactions;
		this.identityProfiles = identityProfiles;
		this.stores = stores;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody CreateOrganizationRequest body,
			ServerHttpRequest request) {
		return accounts
				.resolve(
						request)
				.flatMap(
						owner -> transactions
								.transactional(
										// 产品规则（2026-08-23）：一个账号只能有一个商家主体关联——
										// 自建或被邀请加入任一存在即不可再建（用户实测：admin 成员仍能创建）。
										organizations.findForAccount(owner.id()).next()
												.flatMap(existing -> Mono.<Organization>error(
														new IdentityException(409, "一个账号只能有一个商家主体（自建或已加入），不可再创建")))
												.switchIfEmpty(organizations
														.create(owner.id(), body.name(), normalizeIndustry(body.industry())))
												.flatMap(org -> seedOwnerMembership(org, owner.id()).thenReturn(org))
									// 「登录先开通商家身份（不带 org）→ 工作台内建主体」序列的档案回填：
									// identity_profile.organization_id 之前永远无法经 API 补上（只有 create），
									// 断言从此不带 org、org 级任务整体 403。best-effort（无档案 0 行不阻断）；
									// marketplace 的 org 级授权另已改走服务端成员表，脏数据不再构成权限故障。
									.flatMap(org -> identityProfiles
											.bindOrganizationIfAbsent(owner.id(), org.id()).thenReturn(org))
												// 任务书 #50 D2：注册即开店——同事务自动建默认门店（名=主体名），
												// 单店商家从此不需要「建店」这个动作；StoreCreated 与手动建店同款（审计/下游一致）。
												.flatMap(org -> stores.create(org.id(), org.name())
														.flatMap(defaultStore -> outbox
																.append(new EventEnvelope(UUID.randomUUID().toString(),
																		"StoreCreated", "Store", defaultStore.id(), 1,
																		Instant.now(), null,
																		Map.of("storeId", defaultStore.id(),
																				"organizationId", org.id(),
																				"name", defaultStore.name(),
																				"defaultStore", true)))
																.thenReturn(org)))
												.flatMap(org -> outbox
														.append(new EventEnvelope(UUID.randomUUID().toString(),
																"OrganizationCreated", "Organization", org.id(), 1,
																Instant.now(), null,
																Map.of("organizationId", org.id(), "ownerAccountId",
																		owner.id(), "name", org.name())))
														.thenReturn(org)))
								.map(org -> ResponseEntity.status(201)
										.body(Map.of("success", true, "data", toBody(org)))));
	}

	/**
	 * 提交主体更名申请（V40）：OWNER/ADMIN 发起；平台审核通过才生效；30 天冷却
	 * （自创建或上次更名生效起算）；同一主体同时只有一份待审。
	 */
	@PostMapping("/{id}/rename-requests")
	public Mono<ResponseEntity<Map<String, Object>>> requestRename(@PathVariable String id,
			@RequestBody RenameRequestRequest body, ServerHttpRequest request) {
		if (body.name() == null || body.name().isBlank() || body.name().trim().length() > 80) {
			return Mono.just(ResponseEntity.badRequest().body(Map.of("success", false, "error", "新主体名称必填（80 字以内）")));
		}
		String requestedName = body.name().trim();
		return authz.requireRole(request, id, MembershipRole.ADMIN)
				.flatMap(caller -> renames.findPendingByOrg(id)
						.hasElement()
						.flatMap(pending -> pending
								? Mono.error(new IdentityException(409, "已有待审核的更名申请，请等待平台审核"))
								: organizations.findById(id)
										.switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
										.flatMap(org -> {
											if (org.name().equals(requestedName)) {
												return Mono.error(new IdentityException(400, "新名称与当前名称相同"));
											}
											return renames.findLatestApproved(id).map(approved -> approved.reviewedAt())
													.defaultIfEmpty(org.createdAt())
													.flatMap(lastChangeAt -> {
														Instant nextAllowed = lastChangeAt.plus(RENAME_COOLDOWN);
														if (Instant.now().isBefore(nextAllowed)) {
															return Mono.error(new IdentityException(409,
																	"商家主体更名冷却中（30 天一次），最早可于 "
																			+ formatDateTime(nextAllowed) + " 再次申请"));
														}
														return renames.insert(id, caller.id(), org.name(), requestedName)
																.flatMap(req -> outbox.append(new EventEnvelope(
																		UUID.randomUUID().toString(),
																		"OrganizationRenameRequested", "Organization", id, 1,
																		Instant.now(), null,
																		Map.of("organizationId", id,
																				"currentName", org.name(),
																				"requestedName", requestedName,
																				"requestedBy", caller.id())))
																		.thenReturn(req));
													});
										})))
				.map(req -> ResponseEntity.status(201).body(Map.of("success", true, "data", toRenameBody(req))));
	}

	/** 主体视角：申请历史（含待审），供工作台展示冷却/审核中状态。 */
	@GetMapping("/{id}/rename-requests")
	public Mono<ResponseEntity<Map<String, Object>>> listRenames(@PathVariable String id, ServerHttpRequest request) {
		return authz.requireRole(request, id, MembershipRole.MEMBER)
				.flatMap(caller -> renames.findRecentByOrg(id, 5).collectList()
						.map(list -> ResponseEntity.ok(Map.of("success", true,
								"data", list.stream().map(this::toRenameBody).toList()))));
	}

	private static String formatDateTime(Instant instant) {
		return ZonedDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"))
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
	}

	private Map<String, Object> toRenameBody(OrganizationRenameRepository.RenameRequest req) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", req.id());
		body.put("organizationId", req.organizationId());
		body.put("currentName", req.currentName());
		body.put("requestedName", req.requestedName());
		body.put("status", req.status());
		body.put("requestedAt", req.requestedAt() == null ? null : req.requestedAt().toString());
		body.put("reviewedAt", req.reviewedAt() == null ? null : req.reviewedAt().toString());
		body.put("reviewNote", req.reviewNote());
		return body;
	}

	public record RenameRequestRequest(String name) {
	}

	@GetMapping("/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerHttpRequest request) {
		return accounts.resolve(request)
				.flatMap(acc -> organizations.findById(id)
						.map(org -> ResponseEntity.ok(Map.of("success", true, "data", toBody(org))))
						.switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在"))));
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> listMine(ServerHttpRequest request) {
		return accounts.resolve(request).flatMap(acc -> organizations.findForAccount(acc.id()).collectList().map(
				list -> ResponseEntity.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
	}

	// ---------- 任务书 #49 D5：成员账号前缀 ----------

	/** 前缀读：本组织任意成员（建号表单预览用）；非成员 403。 */
	@GetMapping("/{id}/account-prefix")
	public Mono<ResponseEntity<Map<String, Object>>> getAccountPrefix(@PathVariable String id,
			ServerHttpRequest request) {
		return accounts.resolve(request)
				.flatMap(operator -> authz.roleOfAccount(operator.id(), id)
						.switchIfEmpty(Mono.error(new IdentityException(403, "无权访问该组织")))
						.then(organizations.selectAccountPrefix(id))
					 .map(prefix -> ResponseEntity.ok(Map.of("success", true, "data", Map.of("prefix", prefix)))));
	}

	// 任务书 #51：商家侧改前缀（PATCH /{id}/account-prefix）已删除。前缀自动生成、商家只读，
	// 改名是运营动作 —— 它会连带重写该主体下全部成员的登录名与占位邮箱，属平台侧处置，
	// 见 OrganizationPrefixAdminController（PATCH /api/admin/organizations/{id}/account-prefix）。

	/** best-effort 种 OWNER 成员行：失败不阻断 org 创建（鉴权兜底靠 owner_account_id）。 */
	private Mono<Membership> seedOwnerMembership(Organization org, String ownerId) {
		return memberships.create(org.id(), ownerId, MembershipRole.OWNER.dbValue()).onErrorResume(e -> Mono.empty());
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	private Map<String, Object> toBody(Organization org) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", org.id());
		m.put("ownerAccountId", org.ownerAccountId());
		m.put("name", org.name());
		m.put("status", org.status());
		m.put("permissionTier", org.permissionTier());
		m.put("industry", org.industry());
		m.put("createdAt", org.createdAt() == null ? null : org.createdAt().toString());
		return m;
	}

	/** 归一化行业：null/空 → other；否则小写。合法性留权限申请时校验（避免 organization↔permission 循环）。 */
	private static String normalizeIndustry(String industry) {
		return (industry == null || industry.isBlank()) ? "other" : industry.trim().toLowerCase();
	}
}
