package com.grassland.identity.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.kyb.KybSubmissionService;
import com.grassland.identity.kyb.KybVerificationRequest;
import com.grassland.identity.kyb.KybVerificationType;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.DeleteMapping;

/**
 * 门店 HTTP 入口。草场身份域 Slice 2F。挂 {@code /api/organizations/{orgId}/stores}（门店属于
 * org，RESTful 嵌套）。
 *
 * <ul>
 * <li>POST — 建门店，需 org 内 ADMIN 及以上角色，写 outbox {@code StoreCreated} 事件。</li>
 * <li>GET — 列 org 下门店，需 MEMBER 及以上。</li>
 * <li>GET /{storeId} — 单查，需 MEMBER 及以上；跨 org 或不存在返回 404。</li>
 * <li>GL-P3-MERCHANT-001 新增：</li>
 * <li>POST /{storeId}/profile — 创建/更新门店详细资料（独立门店 KYB：门店 MANAGER，org OWNER/ADMIN
 * 隐式）。</li>
 * <li>GET /{storeId}/profile — 查询门店详细资料（门店 STAFF 或 org MEMBER 及以上）。</li>
 * <li>POST /{storeId}/profile/submit — 提交门店资料审核（门店 MANAGER，org OWNER/ADMIN
 * 隐式）。</li>
 * <li>DELETE /{storeId}/profile — 停用门店详细资料（门店 MANAGER，org OWNER/ADMIN 隐式）。</li>
 * </ul>
 *
 * <p>
 * 独立门店 KYB 状态机（2026-08-16）：资料端点从组织级 ADMIN 门槛收敛为门店粒度 STAFF 读 / MANAGER 写
 * （{@link StoreAuthorization#requireStoreRole}，org OWNER/ADMIN 隐式 MANAGER
 * 不回归）——纯门店经理 无需 merchant identity 或组织成员身份即可走完 draft→pending→approved/rejected
 * 生命周期。
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/stores")
public class StoreController {

	private final OrgAuthorization authz;
	private final StoreAuthorization storeAuthz;
	private final StoreRepository stores;
	private final StoreProfileRepository storeProfiles;
	private final KybSubmissionService submissions;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final ObjectMapper json = new ObjectMapper();

	public StoreController(OrgAuthorization authz, StoreAuthorization storeAuthz, StoreRepository stores,
			StoreProfileRepository storeProfiles, KybSubmissionService submissions, OutboxRepository outbox,
			TransactionalOperator transactions) {
		this.authz = authz;
		this.storeAuthz = storeAuthz;
		this.stores = stores;
		this.storeProfiles = storeProfiles;
		this.submissions = submissions;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable String orgId,
			@RequestBody CreateStoreRequest body, ServerHttpRequest request) {
		return authz
				.requireRole(request, orgId,
						MembershipRole.ADMIN)
				.flatMap(
						account -> transactions
								.transactional(
										stores.create(orgId, body.name())
												.flatMap(
														store -> outbox
																.append(new EventEnvelope(UUID.randomUUID().toString(),
																		"StoreCreated", "Store", store.id(), 1,
																		Instant.now(), null,
																		Map.of("storeId", store.id(), "organizationId",
																				orgId, "name", store.name())))
																.thenReturn(store)))
								.map(store -> ResponseEntity.status(201)
										.body(Map.of("success", true, "data", toBody(store)))));
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, ServerHttpRequest request) {
		return authz.requireRole(request, orgId, MembershipRole.MEMBER)
				.flatMap(account -> stores.findByOrganization(orgId).collectList().map(list -> ResponseEntity
						.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
	}

	@GetMapping("/{storeId}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String orgId, @PathVariable String storeId,
			ServerHttpRequest request) {
		return authz.requireRole(request, orgId, MembershipRole.MEMBER)
				.flatMap(account -> stores.findByOrganizationAndId(orgId, storeId)
						.map(store -> ResponseEntity.ok(Map.of("success", true, "data", toBody(store))))
						.switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在"))));
	}

	// GL-P3-MERCHANT-001: 门店详细资料端点

	@PostMapping(path = "/{storeId}/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> upsertProfile(@PathVariable String orgId,
			@PathVariable String storeId, @RequestBody CreateStoreProfileRequest body, ServerHttpRequest request) {
		return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER).flatMap(account -> {
			StoreProfileDraft draft = draftFrom(body);
			return transactions.transactional(storeProfiles.findByOrganizationAndIdForUpdate(orgId, storeId)
					.flatMap(existing -> requireEditable(existing).thenReturn(existing))
					.then(storeProfiles.upsertDraft(orgId, storeId, draft)));
		}).map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile))));
	}

	/** 任务书 #24：写前归一化营销字段（帽与 trim/去重；blank → null；空数组 = 清空）。 */
	private StoreProfileDraft draftFrom(CreateStoreProfileRequest body) {
		return new StoreProfileDraft(requireAddress(body.address()), body.phone(),
				requireBusinessHours(body.businessHours()), body.description(),
				StoreMarketingFields.items(body.categories(), "主营品类"),
				StoreMarketingFields.items(body.signatureItems(), "特色产品/服务"),
				StoreMarketingFields.items(body.sellingPoints(), "推荐卖点"),
				StoreMarketingFields.items(body.mustEmphasize(), "必须强调内容"),
				StoreMarketingFields.items(body.forbiddenPhrases(), "禁止表达"),
				StoreMarketingFields.items(body.allowedTags(), "可使用标签"),
				StoreMarketingFields.optional(body.brandTone(), StoreMarketingFields.MAX_BRAND_TONE_LENGTH, "品牌语气"),
				StoreMarketingFields.optional(body.priceRange(), StoreMarketingFields.MAX_PRICE_RANGE_LENGTH, "价格区间"),
				StoreMarketingFields.averageSpend(body.averageSpendCents()),
				StoreMarketingFields.optional(body.visitNotes(), StoreMarketingFields.MAX_VISIT_NOTES_LENGTH, "到店提示"));
	}

	@PostMapping("/{storeId}/profile/submit")
	public Mono<ResponseEntity<Map<String, Object>>> submitProfile(@PathVariable String orgId,
			@PathVariable String storeId, ServerHttpRequest request) {
		return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER).flatMap(
				account -> transactions.transactional(storeProfiles.findByOrganizationAndIdForUpdate(orgId, storeId)
						.switchIfEmpty(Mono.error(new IdentityException(404, "门店资料不存在")))
						.flatMap(profile -> requireSubmittable(profile).thenReturn(profile))
						.flatMap(profile -> storeProfiles.submit(orgId, storeId, Instant.now())
								.switchIfEmpty(Mono.error(new IdentityException(409, "门店资料状态已变化"))))
						.flatMap(updated -> submissions
								.enqueue(KybVerificationType.STORE_PROFILE, orgId, UUID.fromString(storeId),
										account.id(), List.of())
								.flatMap(review -> outbox.append(submittedEvent(orgId, storeId, review))
										.thenReturn(updated)))))
				.map(profile -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(profile))));
	}

	@GetMapping("/{storeId}/profile")
	public Mono<ResponseEntity<Map<String, Object>>> getProfile(@PathVariable String orgId,
			@PathVariable String storeId, ServerHttpRequest request) {
		return requireStoreProfileReadable(request, orgId, storeId)
				.then(storeProfiles.findByOrganizationAndId(orgId, storeId))
				.map(profile -> ResponseEntity.ok(Map.of("success", true, "data", toBody(profile))))
				.defaultIfEmpty(ResponseEntity.ok(envelope(null)));
	}

	@DeleteMapping("/{storeId}/profile")
	public Mono<ResponseEntity<Map<String, Object>>> deleteProfile(@PathVariable String orgId,
			@PathVariable String storeId, ServerHttpRequest request) {
		return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER)
				.flatMap(account -> transactions.transactional(storeProfiles
						.findByOrganizationAndIdForUpdate(orgId, storeId)
						.switchIfEmpty(Mono.error(new IdentityException(404, "门店资料不存在")))
						.flatMap(existing -> requireEditable(existing).then(storeProfiles.deactivate(orgId, storeId)))
						.switchIfEmpty(Mono.error(new IdentityException(404, "门店资料不存在")))))
				.map(profile -> ResponseEntity.ok(Map.of("success", true, "data", Map.of("deleted", true))));
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	private Map<String, Object> toBody(Store store) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", store.id());
		m.put("organizationId", store.organizationId());
		m.put("name", store.name());
		m.put("status", store.status());
		m.put("createdAt", store.createdAt() == null ? null : store.createdAt().toString());
		return m;
	}

	private Mono<Void> requireStoreInOrganization(String orgId, String storeId) {
		return stores.findByOrganizationAndId(orgId, storeId)
				.switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在"))).then();
	}

	/**
	 * 门店资料读取：门店任意角色（STAFF 及以上）放行；无门店角色时回落 org MEMBER （保持组织成员既有可见性不回归）。跨 org 由
	 * {@link StoreAuthorization} 内部的门店归属校验 404。
	 */
	private Mono<Void> requireStoreProfileReadable(ServerHttpRequest request, String orgId, String storeId) {
		return storeAuthz.resolveStoreRole(request, orgId, storeId).hasElement()
				.flatMap(hasStoreRole -> hasStoreRole
						? Mono.empty()
						: authz.requireRole(request, orgId, MembershipRole.MEMBER).then());
	}

	private Mono<Void> requireEditable(StoreProfile profile) {
		StoreProfileStatus status = StoreProfileStatus.fromDb(profile.status());
		if (status.isUnderReview()) {
			return Mono.error(new IdentityException(409, "门店资料审核中，暂不可编辑"));
		}
		if (!status.isEditable()) {
			return Mono.error(new IdentityException(409, "门店资料已通过审核，如需变更请联系客服"));
		}
		return Mono.empty();
	}

	private Mono<Void> requireSubmittable(StoreProfile profile) {
		StoreProfileStatus status = StoreProfileStatus.fromDb(profile.status());
		if (status.isUnderReview()) {
			return Mono.error(new IdentityException(409, "门店资料已在审核中"));
		}
		if (!status.canSubmit()) {
			return Mono.error(new IdentityException(409, "门店资料当前不可提交审核"));
		}
		return Mono.empty();
	}

	private EventEnvelope submittedEvent(String orgId, String storeId, KybVerificationRequest review) {
		return new EventEnvelope(UUID.randomUUID().toString(), "StoreProfileSubmitted", "StoreProfile", storeId, 1,
				Instant.now(), null,
				Map.of("organizationId", orgId, "storeId", storeId, "requestId", review.id().toString()));
	}

	private String requireAddress(String value) {
		if (value == null || value.isBlank()) {
			throw new IdentityException(400, "门店地址不能为空");
		}
		try {
			JsonNode address = json.readTree(value);
			JsonNode street = address == null ? null : address.get("address");
			if (address == null || !address.isObject() || street == null || !street.isTextual()
					|| street.asText().isBlank()) {
				throw new IdentityException(400, "门店地址格式无效");
			}
			return value;
		} catch (JsonProcessingException error) {
			throw new IdentityException(400, "门店地址格式无效");
		}
	}

	private String requireBusinessHours(String value) {
		if (value == null) {
			return null;
		}
		if (value.isBlank()) {
			throw new IdentityException(400, "门店营业时间格式无效");
		}
		try {
			JsonNode businessHours = json.readTree(value);
			if (businessHours == null || !businessHours.isArray()) {
				throw new IdentityException(400, "门店营业时间格式无效");
			}
			if (businessHours.size() > 7) {
				throw new IdentityException(400, "门店营业时间格式无效");
			}
			Set<Integer> seenDays = new HashSet<>();
			for (JsonNode item : businessHours) {
				JsonNode dayOfWeek = item.get("dayOfWeek");
				JsonNode openTime = item.get("openTime");
				JsonNode closeTime = item.get("closeTime");
				if (!item.isObject() || item.size() != 3 || dayOfWeek == null || !dayOfWeek.isIntegralNumber()
						|| !dayOfWeek.canConvertToInt() || dayOfWeek.intValue() < 1 || dayOfWeek.intValue() > 7
						|| openTime == null || !openTime.isTextual() || closeTime == null || !closeTime.isTextual()
						|| !openTime.textValue().matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
						|| !closeTime.textValue().matches("(?:[01]\\d|2[0-3]):[0-5]\\d")
						|| !seenDays.add(dayOfWeek.intValue())) {
					throw new IdentityException(400, "门店营业时间格式无效");
				}
				LocalTime opensAt = LocalTime.parse(openTime.textValue());
				LocalTime closesAt = LocalTime.parse(closeTime.textValue());
				if (!opensAt.isBefore(closesAt)) {
					throw new IdentityException(400, "门店营业时间格式无效");
				}
			}
			return json.writeValueAsString(businessHours);
		} catch (JsonProcessingException | DateTimeParseException error) {
			throw new IdentityException(400, "门店营业时间格式无效");
		}
	}

	private static Map<String, Object> envelope(Map<String, Object> data) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", true);
		body.put("data", data);
		return body;
	}

	private Map<String, Object> toBody(StoreProfile profile) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("storeId", profile.storeId());
		m.put("address", profile.address());
		m.put("phone", profile.phone());
		m.put("businessHours", profile.businessHours());
		m.put("description", profile.description());
		m.put("categories", profile.categories());
		m.put("signatureItems", profile.signatureItems());
		m.put("sellingPoints", profile.sellingPoints());
		m.put("mustEmphasize", profile.mustEmphasize());
		m.put("forbiddenPhrases", profile.forbiddenPhrases());
		m.put("allowedTags", profile.allowedTags());
		m.put("brandTone", profile.brandTone());
		m.put("priceRange", profile.priceRange());
		m.put("averageSpendCents", profile.averageSpendCents());
		m.put("visitNotes", profile.visitNotes());
		m.put("status", profile.status());
		m.put("submittedAt", profile.submittedAt() == null ? null : profile.submittedAt().toString());
		m.put("reviewedAt", profile.reviewedAt() == null ? null : profile.reviewedAt().toString());
		m.put("reviewerAccountId", profile.reviewerAccountId());
		m.put("reviewNote", profile.reviewNote());
		m.put("createdAt", profile.createdAt() == null ? null : profile.createdAt().toString());
		return m;
	}

	/**
	 * 门店资料写请求。任务书 #24：新增营销字段全用包装类型/可空（Jackson 3 record 缺失 primitive 会直接
	 * 400）；营销字段整份覆盖，空数组与不传等价（清空语义）。
	 */
	public record CreateStoreProfileRequest(String address, String phone, String businessHours, String description,
			List<String> categories, List<String> signatureItems, List<String> sellingPoints,
			List<String> mustEmphasize, List<String> forbiddenPhrases, List<String> allowedTags, String brandTone,
			String priceRange, Integer averageSpendCents, String visitNotes) {
	}
}
