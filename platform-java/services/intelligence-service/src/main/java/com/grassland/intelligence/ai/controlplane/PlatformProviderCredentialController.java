package com.grassland.intelligence.ai.controlplane;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.crypto.MaskedKey;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 平台通用凭据 admin API（任务书 #47 S1；D1–D6）。
 *
 * <p>
 * 端点（全部 {@code requireAdmin}，与 {@code /api/admin/ai/models} 同闸——D4 刻意不新增
 * PLATFORM_AI 角色）：
 * <ul>
 * <li>GET /api/admin/ai/credentials — 列出有效凭据（只回掩码）</li>
 * <li>GET /api/admin/ai/credentials?includeDisabled=true — 含已停用行（治理台开关，任务书
 * #59）</li>
 * <li>GET /api/admin/ai/credentials/{id} — 详情</li>
 * <li>POST /api/admin/ai/credentials — 创建；同 (provider, baseUrl) 已有 → 409</li>
 * <li>PUT /api/admin/ai/credentials/{id} — 改连接信息（不含密钥）→ version+1</li>
 * <li>PUT /api/admin/ai/credentials/{id}/key — 轮换密钥 → version+1</li>
 * <li>DELETE /api/admin/ai/credentials/{id} — 软删；仍被有效模型配置引用 → 409（D6）</li>
 * <li>DELETE /api/admin/ai/credentials/&#123;id&#125;/hard — 硬删已停用行（引用中 409；勾选集
 * CASCADE，任务书 #59）</li>
 * </ul>
 *
 * <p>
 * <b>KEK 门控为 503 而非 404</b>：与 {@code AiProviderKeyController} 的
 * {@code @Conditional(CryptoKekConfiguredCondition)}（整体不注册→404）刻意不同。这是运营端点， 404
 * 会让 admin 以为「功能不存在」，而真实原因是加密基建未配；503 + 明确文案才可诊断（D8/验收 2）。
 * 无论如何都不退化存明文。读端点与无密钥凭据不需要 KEK，故不挡。
 */
@RestController
@RequestMapping("/api/admin/ai/credentials")
public class PlatformProviderCredentialController {

	private final IntelligenceCallerResolver callers;
	private final PlatformProviderCredentialRepository repository;
	private final PlatformProviderPolicy providerPolicy;
	private final TransactionalOperator transactions;
	/**
	 * KEK 未配时 bean 不存在（CryptoAutoConfiguration:27 的 @Conditional）——用 ObjectProvider
	 * 才能转 503。
	 */
	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	/** 复用用户 BYOK 的固定地址出站路径列上游模型，不自建 WebClient。 */
	private final com.grassland.intelligence.settings.ModelListingService modelListing;
	/** admin 勾选集（V51）；平台模型表单的下拉读这里，不触网。 */
	private final PlatformCredentialModelRepository selectedModels;

	public PlatformProviderCredentialController(IntelligenceCallerResolver callers,
			PlatformProviderCredentialRepository repository, PlatformProviderPolicy providerPolicy,
			TransactionalOperator transactions, ObjectProvider<EnvelopeEncryption> encryptionProvider,
			com.grassland.intelligence.settings.ModelListingService modelListing,
			PlatformCredentialModelRepository selectedModels) {
		this.callers = callers;
		this.repository = repository;
		this.providerPolicy = providerPolicy;
		this.transactions = transactions;
		this.encryptionProvider = encryptionProvider;
		this.modelListing = modelListing;
		this.selectedModels = selectedModels;
	}

	/**
	 * 列平台凭据。默认只回生效行（{@code includeDisabled=false}，与既有契约逐字节兼容）—— 平台模型表单的凭据下拉与
	 * openEdit 反查都依赖此默认值，不得翻转。 {@code includeDisabled=true}
	 * 时含已停用行（治理台「显示已停用」开关），生效行在前。
	 */
	@GetMapping
	public Flux<PlatformProviderCredentialResponse> list(
			@RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled,
			ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest()).flatMapMany(
				admin -> (includeDisabled ? repository.findAllIncludingDisabled() : repository.findAllEnabled())
						.map(PlatformProviderCredentialResponse::from));
	}

	@GetMapping("/{id}")
	public Mono<ResponseEntity<PlatformProviderCredentialResponse>> get(@PathVariable UUID id,
			ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> repository.findEnabledById(id)
						.map(c -> ResponseEntity.ok(PlatformProviderCredentialResponse.from(c)))
						.switchIfEmpty(Mono.error(notFound(id))));
	}

	/**
	 * 列出该凭据上游实际可用的模型（GET {baseUrl}/models），供治理台「平台模型」表单的模型名下拉。
	 *
	 * <p>
	 * 出站复用 {@code ModelListingService.listModelsAt}——与用户 BYOK 同一条固定地址连接路径 （HTTPS、无
	 * userinfo、全部 DNS 解析为公网、与已固定集合一致、不跟随重定向、响应体有上限）。 这里刻意不自建
	 * WebClient：SSRF/DNS-rebinding 防护分叉就会漏掉一侧。
	 *
	 * <p>
	 * 只回模型 id 列表，**不回密钥任何形态**。密钥明文只活在 Authorization 头，不落日志不入响应。 KEK 未配 →
	 * 503（与写端点同口径，404 会让 admin 以为功能不存在）；无密钥凭据 → 400。
	 */
	@GetMapping("/{id}/models")
	public Mono<ResponseEntity<List<Map<String, Object>>>> listUpstreamModels(@PathVariable UUID id,
			ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest()).flatMap(
				admin -> repository.findEnabledById(id).switchIfEmpty(Mono.error(notFound(id))).flatMap(credential -> {
					if (!credential.hasKey()) {
						return Mono.error(new IntelligenceException(400, "该凭据未配置密钥，无法列出模型"));
					}
					String plaintext = requireEncryption().decrypt(credential.encryptedKey());
					return modelListing.listModelsAt(credential.baseUrl(), plaintext);
				}).map(ResponseEntity::ok));
	}

	/**
	 * 读该凭据下 admin 已勾选的模型（V51）。平台模型表单的模型下拉数据源——不触网。
	 *
	 * <p>
	 * 与 {@code /models} 的区别：那个实时问上游「这把 key 能用什么」，这个回「运营决定用什么」。
	 */
	@GetMapping("/{id}/selected-models")
	public Mono<ResponseEntity<List<Map<String, Object>>>> listSelectedModels(@PathVariable UUID id,
			ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> repository.findEnabledById(id).switchIfEmpty(Mono.error(notFound(id)))
						.flatMap(credential -> selectedModels.findByCredential(id)
								.map(PlatformProviderCredentialController::selectedModelPayload).collectList())
						.map(ResponseEntity::ok));
	}

	/**
	 * 连通性探测（任务书 #69 卡E）：GET {baseUrl}/models 验证连通与密钥有效性，结果落库
	 * （只存最近一次）并返回。手动触发、不缓存——每次点击实打（D5）。
	 *
	 * <ul>
	 * <li>sandbox provider：不出网，直接 ok/0ms（沙箱无上游）；</li>
	 * <li>出站前过受信 origin 表校验（同 {@link #create} 的 {@code providerPolicy.validate}
	 * 闸）—— 不在表 = 失败留痕（status=error），不发请求；</li>
	 * <li>URL 拼接与 {@code ModelListingService.listModelsAt} 同规则：baseUrl 补尾斜杠 + GET
	 * /models （尾部带 /v1 的 base_url 行为一致——探测如实反映配置，包括双 /v1 的错误形态）；</li>
	 * <li>结果映射：2xx→ok；401/403→unauthorized；超时/连接失败→unreachable；其他→error（摘要截
	 * 512）。</li>
	 * </ul>
	 */
	@PostMapping("/{id}/probe")
	public Mono<ResponseEntity<Map<String, Object>>> probe(@PathVariable UUID id, ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest()).flatMap(admin -> repository.findEnabledById(id)
				.switchIfEmpty(Mono.error(notFound(id)))
				.flatMap(credential -> probeCredential(credential).flatMap(result -> repository
						.recordProbe(id, result.status(), result.latencyMs(), result.error()).thenReturn(result))))
				.map(result -> ResponseEntity.ok(probeBody(result))).onErrorMap(UntrustedPlatformOriginException.class,
						PlatformProviderCredentialController::untrustedOrigin);
	}

	/** 探测结果（落库与响应同构；checkedAt 由响应侧生成）。 */
	record ProbeResult(String status, long latencyMs, String error) {
	}

	private Mono<ProbeResult> probeCredential(PlatformProviderCredential credential) {
		if (PlatformProviderNames.SANDBOX.equalsIgnoreCase(credential.provider())) {
			return Mono.just(new ProbeResult("ok", 0, "沙箱无上游"));
		}
		try {
			providerPolicy.validate(credential.provider(), credential.baseUrl());
		} catch (UntrustedPlatformOriginException error) {
			return Mono.just(new ProbeResult("error", 0, "base URL 的端点不在受信列表，请先在受信端点中添加 " + error.origin()));
		} catch (IllegalArgumentException error) {
			return Mono.just(new ProbeResult("error", 0, truncate(error.getMessage())));
		}
		if (!credential.hasKey()) {
			return Mono.just(new ProbeResult("error", 0, "该凭据未配置密钥，无法探测"));
		}
		String plaintext = requireEncryption().decrypt(credential.encryptedKey());
		String normalizedBase = credential.baseUrl().endsWith("/") ? credential.baseUrl() : credential.baseUrl() + "/";
		return Mono.defer(() -> {
			long startedAt = System.nanoTime();
			return org.springframework.web.reactive.function.client.WebClient.create(normalizedBase).get()
					.uri("/models").header("Authorization", "Bearer " + plaintext)
					.accept(org.springframework.http.MediaType.APPLICATION_JSON)
					.exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
					.timeout(Duration.ofSeconds(10)).map(status -> {
						long elapsedMs = elapsedMs(startedAt);
						if (status.is2xxSuccessful()) {
							return new ProbeResult("ok", elapsedMs, null);
						}
						if (org.springframework.http.HttpStatus.UNAUTHORIZED.equals(status)
								|| org.springframework.http.HttpStatus.FORBIDDEN.equals(status)) {
							return new ProbeResult("unauthorized", elapsedMs, "上游 HTTP " + status.value());
						}
						return new ProbeResult("error", elapsedMs, "上游 HTTP " + status.value());
					})
					.onErrorResume(java.util.concurrent.TimeoutException.class,
							error -> Mono.just(new ProbeResult("unreachable", elapsedMs(startedAt), "探测超时（10s）")))
					.onErrorResume(error -> Mono
							.just(new ProbeResult("unreachable", elapsedMs(startedAt), truncate(error.getMessage()))))
					.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
		});
	}

	private static long elapsedMs(long startedAtNanos) {
		return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
	}

	private static String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() <= 512 ? message : message.substring(0, 512);
	}

	private static Map<String, Object> probeBody(ProbeResult result) {
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("status", result.status());
		body.put("latencyMs", result.latencyMs());
		body.put("error", result.error());
		body.put("checkedAt", java.time.Instant.now().toString());
		return body;
	}

	/**
	 * 整份覆盖勾选集。空数组合法（= 取消全部勾选）。
	 *
	 * <p>
	 * 刻意**不**校验勾选项是否仍在上游列表里：保存时再问一次上游就把「改配置」绑死在 上游可达性上（DNS
	 * 劫持、上游故障时就存不了）。上游少了某个模型是运行时错误，不是保存时错误。
	 */
	@PutMapping("/{id}/selected-models")
	public Mono<ResponseEntity<List<Map<String, Object>>>> replaceSelectedModels(@PathVariable UUID id,
			@Valid @RequestBody ReplaceSelectedModelsRequest body, ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> repository.findEnabledById(id).switchIfEmpty(Mono.error(notFound(id)))
						.flatMap(credential -> transactions
								.transactional(selectedModels.replaceAll(id, body.toDomain(), admin.accountId()))
								.thenMany(selectedModels.findByCredential(id))
								.map(PlatformProviderCredentialController::selectedModelPayload).collectList())
						.map(ResponseEntity::ok));
	}

	private static Map<String, Object> selectedModelPayload(PlatformCredentialModelRepository.SelectedModel model) {
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("id", model.modelId());
		if (model.ownedBy() != null) {
			payload.put("ownedBy", model.ownedBy());
		}
		return payload;
	}

	@PostMapping
	public Mono<ResponseEntity<PlatformProviderCredentialResponse>> create(
			@Valid @RequestBody CreatePlatformCredentialRequest body, ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest()).flatMap(admin -> {
			// baseUrl 仍过受信目的地校验（IllegalArgumentException → 400；origin 不在表 → 422）
			providerPolicy.validate(body.provider(), body.baseUrl());
			boolean withKey = body.apiKey() != null && !body.apiKey().isBlank();
			// sandbox 等无密钥凭据不需要 KEK；有密钥才要求加密基建就位
			EnvelopeEncryption encryption = withKey ? requireEncryption() : null;
			String plainKey = withKey ? validatedKey(body.apiKey()) : null;
			String encryptedKey = withKey ? encryption.encrypt(plainKey) : null;
			String keyVersion = withKey ? encryption.keyVersion(encryptedKey) : null;
			String maskedHint = withKey ? MaskedKey.mask(plainKey) : null;

			return transactions
					.transactional(repository.create(body.name(), body.provider(), body.baseUrl(), encryptedKey,
							keyVersion, maskedHint, admin.accountId()).flatMap(repository::findEnabledById))
					.map(saved -> ResponseEntity.status(201).body(PlatformProviderCredentialResponse.from(saved)));
		}).onErrorResume(DataIntegrityViolationException.class,
				error -> Mono.error(new IntelligenceException(409, "该 provider + baseUrl 已有有效凭据，或标签重复"))).onErrorMap(
						UntrustedPlatformOriginException.class, PlatformProviderCredentialController::untrustedOrigin);
	}

	@PutMapping("/{id}")
	public Mono<ResponseEntity<PlatformProviderCredentialResponse>> update(@PathVariable UUID id,
			@Valid @RequestBody UpdatePlatformCredentialRequest body, ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest()).flatMap(admin -> {
			providerPolicy.validate(body.provider(), body.baseUrl());
			return transactions
					.transactional(repository
							.updateConnection(id, body.name(), body.provider(), body.baseUrl(), admin.accountId())
							.flatMap(updated -> updated ? repository.findEnabledById(id) : Mono.empty()))
					.map(saved -> ResponseEntity.ok(PlatformProviderCredentialResponse.from(saved)))
					.switchIfEmpty(Mono.error(notFound(id)));
		}).onErrorResume(DataIntegrityViolationException.class,
				error -> Mono.error(new IntelligenceException(409, "该 provider + baseUrl 已有有效凭据，或标签重复"))).onErrorMap(
						UntrustedPlatformOriginException.class, PlatformProviderCredentialController::untrustedOrigin);
	}

	@PutMapping("/{id}/key")
	public Mono<ResponseEntity<PlatformProviderCredentialResponse>> rotateKey(@PathVariable UUID id,
			@Valid @RequestBody RotatePlatformCredentialRequest body, ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest()).flatMap(admin -> {
			EnvelopeEncryption encryption = requireEncryption();
			String plainKey = validatedKey(body.apiKey());
			String encryptedKey = encryption.encrypt(plainKey);
			String keyVersion = encryption.keyVersion(encryptedKey);
			String maskedHint = MaskedKey.mask(plainKey);

			return transactions
					.transactional(repository.rotateKey(id, encryptedKey, keyVersion, maskedHint, admin.accountId())
							.flatMap(rotated -> rotated ? repository.findEnabledById(id) : Mono.empty()))
					.map(saved -> ResponseEntity.ok(PlatformProviderCredentialResponse.from(saved)))
					.switchIfEmpty(Mono.error(notFound(id)));
		});
	}

	/** 软删；仍被有效模型配置引用时拒绝并报引用数（D6：让代价在点击那一刻显示，而非运行时 503）。 */
	@DeleteMapping("/{id}")
	public Mono<ResponseEntity<Void>> disable(@PathVariable UUID id, ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> repository.findEnabledById(id).switchIfEmpty(Mono.error(notFound(id)))
						.flatMap(existing -> repository.countEnabledReferences(id))
						.flatMap(references -> references > 0
								? Mono.<ResponseEntity<Void>>error(
										new IntelligenceException(409, "该凭据仍被 " + references + " 个模型配置引用，请先改指向后再停用"))
								: transactions.transactional(repository.disable(id, admin.accountId()))
										.flatMap(done -> done
												? Mono.just(ResponseEntity.noContent().<Void>build())
												: Mono.error(notFound(id)))));
	}

	/**
	 * 硬删一行<b>已停用</b>凭据（任务书 #59 D2）。生效中 → 409（两步确认，防误删）； 被任何 platform_model_config
	 * 行引用（含已停用历史行——历史行按其时点凭据复现审计， 且普通 FK 物理拦截）→ 409 报行数。勾选集经 CASCADE 一并清除，无需手删。
	 * ai_run.credential_version 是冻结 bigint 非 FK，历史 Run 不受影响。
	 */
	@DeleteMapping("/{id}/hard")
	public Mono<ResponseEntity<Void>> hardDelete(@PathVariable UUID id, ServerWebExchange exchange) {
		return callers.requireAdmin(exchange.getRequest())
				.flatMap(admin -> repository.findById(id).switchIfEmpty(Mono.error(notFound(id))).flatMap(existing -> {
					if (existing.enabled()) {
						return Mono.error(new IntelligenceException(409, "凭据仍在生效中，请先停用后再删除"));
					}
					return repository.countAllReferences(id);
				}).flatMap(references -> references > 0
						? Mono.<ResponseEntity<Void>>error(
								new IntelligenceException(409, "该凭据仍被 " + references + " 个模型配置行引用（含已停用历史行），不可删除"))
						: transactions.transactional(repository.hardDelete(id))
								.flatMap(done -> done
										? Mono.just(ResponseEntity.noContent().<Void>build())
										: Mono.error(notFound(id)))))
				.onErrorMap(DataIntegrityViolationException.class,
						e -> new IntelligenceException(409, "该凭据仍被模型配置行引用（含已停用历史行），不可删除"));
	}

	private EnvelopeEncryption requireEncryption() {
		EnvelopeEncryption encryption = encryptionProvider.getIfAvailable();
		if (encryption == null) {
			throw new IntelligenceException(503, "加密基建未配置（CRYPTO_KEK_BASE64），无法保存平台凭据");
		}
		return encryption;
	}

	/**
	 * 任务书 #58 S2.3：原 {@code AiCapabilityProviderConfigValidator} 的密钥强度规则并入—— ≥16
	 * 字符且禁模板占位值（占位值比没有更危险：看起来配好了，实际上游 401）。
	 */
	private static String validatedKey(String apiKey) {
		String normalized = apiKey == null ? "" : apiKey.trim().toLowerCase(java.util.Locale.ROOT);
		if (apiKey == null || apiKey.length() < 16 || normalized.contains("replace-with")
				|| normalized.contains("placeholder") || normalized.contains("changeme")
				|| normalized.startsWith("your-")) {
			throw new IntelligenceException(422, "平台凭据密钥至少 16 字符，且不能使用模板占位值");
		}
		return apiKey;
	}

	/** 任务书 #58 S2.3：origin 不在受信端点表 → 422 + 引导文案（与模型行保存同口径）。 */
	private static IntelligenceException untrustedOrigin(UntrustedPlatformOriginException error) {
		return new IntelligenceException(422, "base URL 的端点不在受信列表，请先在受信端点中添加 " + error.origin());
	}

	private static IntelligenceException notFound(UUID id) {
		return new IntelligenceException(404, "未找到平台凭据: " + id);
	}
}
