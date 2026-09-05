package com.grassland.intelligence.ai.byok;

import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * BYOK 与平台模型分发服务（GL-P3-AI-001 Phase 5 / 控制面闭环；组织级 ADR-D17）。
 *
 * <p>
 * <b>任务书 #47 D9 起按活动身份分叉</b>（不再是单一优先级链）：
 * <ul>
 * <li><b>merchant 活动身份</b>（{@code organizationId} 非空）→ <b>组织 BYOK &gt; 平台</b>，
 * 跳过个人密钥。商家侧由组织统一配置模型，个人密钥数据保留但不参与路由。</li>
 * <li><b>recommender / 消费者</b>（{@code organizationId} 为 null）→ <b>模型来源总开关</b>
 * （任务书 #78 卡 B，{@code ai_provider_preference} 主行 {@code capability='*'}）：
 * platform（默认）直落平台段；own 逐能力取个人密钥，未命中 DENIED own_key_missing（422）
 * 不回退平台。per-capability 开关已退役（行保留不读）。</li>
 * </ul>
 * 分叉依据是 edge 的不变量：{@code SessionIdentityResolver:75-80} 保证只有 merchant 活动身份才带
 * org/tier，其注释明说破坏它就破坏 HLD 7.4「活动身份 ↔ 组织上下文」。故运行时每个 session 的
 * 链路是<b>单一确定</b>的，不存在「同时看两层」的歧义。
 *
 * <p>
 * 平台回退的授权口径：
 * <ul>
 * <li>组织<b>未</b>配置任何有效组织密钥：沿用调用方显式 {@code allowFallback}（与组织级开启前一致）；</li>
 * <li>组织配置了有效组织密钥（即「组织选择了 BYOK」）：须组织回退策略 （{@code ai_org_byok_policy}，<b>D16
 * 起无行默认允许</b>）<b>且</b> {@code allowFallback} 双满足。 已显式设 {@code false} 的组织仍严格
 * DENIED——不静默扣平台额度（D-11 / HLD §12.3 硬规则）。</li>
 * </ul>
 *
 * <p>
 * 解密<b>不在本层</b>：BYOK 解析仍回传密文 {@code encryptedKey}，明文解密在执行层
 * （{@code AiExecutionService} 经
 * {@code EnvelopeEncryption.decrypt}）按需进行，绝不入日志/响应。
 */
@Service
public class ByokRoutingService {

	private static final Logger logger = LoggerFactory.getLogger(ByokRoutingService.class);

	private final AiProviderKeyRepository keyRepository;
	private final AiOrgByokPolicyRepository policyRepository;
	private final AiProviderPreferenceRepository preferenceRepository;
	private final PlatformModelControlPlaneService platformModelControlPlane;
	private final boolean allowSandbox;

	public ByokRoutingService(AiProviderKeyRepository keyRepository, AiOrgByokPolicyRepository policyRepository,
			AiProviderPreferenceRepository preferenceRepository,
			PlatformModelControlPlaneService platformModelControlPlane,
			@Value("${ai.provider.allow-sandbox:true}") boolean allowSandbox) {
		this.keyRepository = keyRepository;
		this.policyRepository = policyRepository;
		this.preferenceRepository = preferenceRepository;
		this.platformModelControlPlane = platformModelControlPlane;
		this.allowSandbox = allowSandbox;
	}

	/**
	 * 解析目标 AI provider 配置。
	 *
	 * @param organizationId
	 *            组织 ID（个人用户为 null）
	 * @param accountId
	 *            账号 ID
	 * @param capability
	 *            能力（text/image_generation 等）
	 * @param allowFallback
	 *            两级 BYOK 都未命中时是否允许回落平台模型（HLD §12.3 须显式授权； 组织配了组织密钥时还须组织策略同时允许）
	 * @return provider 解析结果（BYOK / PLATFORM / DENIED）
	 */
	public Mono<ProviderResolution> resolveProvider(String organizationId, String accountId, String capability,
			boolean allowFallback) {

		// 任务书 #47 D9：按活动身份分叉。edge 保证「organizationId 非空 ⟺ merchant 活动身份」
		// （SessionIdentityResolver:75-80 的不变量，注释明说破坏它就破坏 HLD 7.4），故非空即商家视角：
		// 商家侧由组织统一配置模型，个人密钥不参与——**跳过个人查询**，不是降级排序。
		if (organizationId != null) {
			return resolveOrgTier(organizationId, capability, allowFallback);
		}
		// 推荐官（及消费者）视角：个人模型来源总开关（任务书 #78 卡 B，D3 单总开关）决定走向——
		// master=platform → **跳过个人段**直落平台段（per-capability 开关已退役，行保留不读）；
		// master=own → 逐能力取个人密钥，命中 → BYOK；未命中 → DENIED own_key_missing（422），
		// **不回退平台**（D3：own 模式未配密钥的能力禁用并引导配置）。
		return preferenceRepository.isOwnModelSource(accountId).flatMap(own -> {
			if (!own) {
				return fallbackStage(capability, allowFallback);
			}
			return keyRepository.findByPersonalAndCapability(accountId, capability)
					.map(key -> ProviderResolution.byok(key.provider(), key.baseUrl(), key.model(), key.encryptedKey(),
							key.keyVersion(), null))
					.switchIfEmpty(Mono.defer(() -> Mono.just(ProviderResolution.denied("own_key_missing"))));
		});
	}

	/** 组织层：组织密钥命中 → BYOK；未命中但组织配有组织密钥 → 按组织策略决定回退。 */
	private Mono<ProviderResolution> resolveOrgTier(String organizationId, String capability, boolean allowFallback) {
		return keyRepository.findByOrganizationAndCapability(organizationId, capability)
				.map(key -> ProviderResolution.byok(key.provider(), key.baseUrl(), key.model(), key.encryptedKey(),
						key.keyVersion(), organizationId))
				.switchIfEmpty(Mono
						.defer(() -> keyRepository.existsEnabledForOrganization(organizationId).flatMap(hasOrgKeys -> {
							if (!hasOrgKeys) {
								// 组织未选择 BYOK：与组织级开启前完全一致
								return fallbackStage(capability, allowFallback);
							}
							// 任务书 #47 D16：无行默认翻为 **允许**。D15「组织配了该 capability 的 key
							// 就用、没配就走平台」与原默认 false（DENIED）在「配了 text、没配 image」时
							// 直接冲突——保留原默认会让 org admin 配完 text 后，图片能力对全组织突然
							// 不可用，且他完全不会预期。已显式设 false 的组织仍严格拒绝（那是明示选择）。
							return policyRepository.find(organizationId).map(AiOrgByokPolicy::allowPlatformFallback)
									.defaultIfEmpty(true).flatMap(orgAllows -> {
										if (!orgAllows) {
											logger.info("Org={} has BYOK keys but fallback policy not allowed"
													+ " for capability={} → deny", organizationId, capability);
											return Mono.just(ProviderResolution.denied("fallback_not_authorized"));
										}
										return fallbackStage(capability, allowFallback);
									});
						})));
	}

	/** 平台回退段：allowFallback 未授权即 DENIED；授权则经控制面解析主/备平台模型。 */
	private Mono<ProviderResolution> fallbackStage(String capability, boolean allowFallback) {
		if (!allowFallback) {
			logger.info("No BYOK for capability={} and fallback not authorized → deny", capability);
			return Mono.just(ProviderResolution.denied("fallback_not_authorized"));
		}
		return platformModelControlPlane.resolve(capability).map(
				opt -> opt.map(ByokRoutingService::toPlatform).orElseGet(() -> builtInSandboxOrDenied(capability)));
	}

	/**
	 * 有真实 Sandbox 客户端实现的能力（任务书 #58 决策 F）。text 经实现期核实
	 * <b>不纳入</b>：{@code TextCompletionClient} 是真实 HTTP 客户端，没有 Sandbox 实现。
	 */
	private static final Map<String, String> BUILT_IN_SANDBOX_MODELS = Map.of("voice", "sandbox-speech-v1", "retrieval",
			"sandbox-embedding-v1", "image_edit", "sandbox-matting-v1");

	/**
	 * 控制面无行时的能力分级（任务书 #58 决策 F，取代已删的启动期 seed）：
	 * <ul>
	 * <li>有真实 Sandbox 客户端实现且 {@code ai.provider.allow-sandbox=true} → 内置 Sandbox
	 * 平台解析（免 origin 校验、零成本）——本地 dev 零模型配置时这些能力仍可跑；</li>
	 * <li>其余能力（text / image_generation / content_safety 等）→
	 * {@code no_platform_model} DENIED，需 BYOK 或治理台配行（新部署冷启动 = 运营在治理台手工配置）。</li>
	 * </ul>
	 */
	private ProviderResolution builtInSandboxOrDenied(String capability) {
		String sandboxModel = BUILT_IN_SANDBOX_MODELS.get(capability);
		if (sandboxModel != null && allowSandbox) {
			return ProviderResolution.platform(null, "sandbox", "https://sandbox.invalid", sandboxModel, 1, null);
		}
		return ProviderResolution.denied("no_platform_model");
	}

	/**
	 * 平台直连解析（不走 BYOK 链）：匿名调用（游客试用、免费匿名流）与治理域固定平台
	 * （{@code RoutedTextCompletionService.completePlatformOnly}）共用——语义与 BYOK 未命中的
	 * 回落完全一致（含 no_platform_model DENIED），只是跳过密钥层查询。
	 */
	public Mono<ProviderResolution> resolvePlatform(String capability) {
		return fallbackStage(capability, true);
	}

	/**
	 * 任务书 #56：快照冻结用——返回当前命中的 BYOK 键本体（含 configId/configUpdatedAt 等冻结字段）。 命中语义与
	 * {@link #resolveProvider} 完全一致（D9 身份分叉；#78 卡 B 起个人段改读模型来源 总开关——master=platform
	 * 视为未命中，master=own 命中密钥即返回）；未命中返回 empty， 由调用方回落平台冻结（组织回退策略只影响未命中后的走向，不影响命中）。
	 */
	public Mono<AiProviderKey> resolveByokKey(String organizationId, String accountId, String capability) {
		if (organizationId != null) {
			return keyRepository.findByOrganizationAndCapability(organizationId, capability);
		}
		return preferenceRepository.isOwnModelSource(accountId)
				.flatMap(own -> own
						? keyRepository.findByPersonalAndCapability(accountId, capability)
						: Mono.<AiProviderKey>empty());
	}

	private static ProviderResolution toPlatform(ResolvedPlatformModel rpm) {
		// 任务书 #47 S2：平台凭据密文随解析结果下传，执行层按需解密；为 null 表示凭据无密钥——
		// 任务书 #58 决策 E 起 fail-closed（解密层 503「平台凭据缺失」），env bootstrap 兜底已删。
		return ProviderResolution.platform(rpm.configId(), rpm.provider(), rpm.baseUrl(), rpm.model(), rpm.version(),
				rpm.maxConcurrency(), rpm.credentialEncryptedKey(), rpm.credentialVersion());
	}

	/**
	 * Provider 配置解析结果。
	 */
	public record ProviderResolution(ResolutionType type, String provider, // qwen/openai-compatible（DENIED 时为 null）
			String baseUrl, String model, String encryptedKey, // BYOK 密文（platform/denied 时为 null）
			String keyVersion, // BYOK 路由版本（platform/denied 时为 null）
			String byokOrganizationId, // 组织密钥命中时的组织 ID（个人 BYOK/platform/denied 为 null）
			boolean chargesPlatformFee, // 是否收平台 AI 费（仅平台模型）
			int platformModelVersion, // 平台配置版本（TaskContext 冻结用）；非平台为 0
			UUID platformConfigId, Integer maxConcurrency, String denialReason, // DENIED 时的原因；其余为 null
			Long credentialVersion // 平台凭据版本（任务书 #47 D7）；BYOK/无凭据为 null
	) {
		public static ProviderResolution byok(String provider, String baseUrl, String model, String encryptedKey,
				String keyVersion, String byokOrganizationId) {
			return new ProviderResolution(ResolutionType.BYOK, provider, baseUrl, model, encryptedKey, keyVersion,
					byokOrganizationId, false, 0, null, null, null, null);
		}

		/** 个人 BYOK（组织维度为 null）。 */
		public static ProviderResolution byok(String provider, String baseUrl, String model, String encryptedKey,
				String keyVersion) {
			return byok(provider, baseUrl, model, encryptedKey, keyVersion, null);
		}

		/** 内置 Sandbox 平台解析（决策 F：控制面无行且能力有 Sandbox 客户端时的假 provider）或无凭据控制面行。 */
		public static ProviderResolution platform(UUID configId, String provider, String baseUrl, String model,
				int version, Integer maxConcurrency) {
			return platform(configId, provider, baseUrl, model, version, maxConcurrency, null, null);
		}

		/**
		 * 带平台凭据的解析（任务书 #47 S2）。
		 *
		 * <p>
		 * {@code credentialEncryptedKey} 复用 {@code encryptedKey} 字段承载——它此前只服务 BYOK，
		 * 语义扩为「本次解析要用的密文，无论来源」。为 null 表示凭据无密钥，执行层按 503 fail-closed（任务书 #58 决策 E）。
		 */
		public static ProviderResolution platform(UUID configId, String provider, String baseUrl, String model,
				int version, Integer maxConcurrency, String credentialEncryptedKey, Long credentialVersion) {
			return new ProviderResolution(ResolutionType.PLATFORM, provider, baseUrl, model, credentialEncryptedKey,
					null, null, true, version, configId, maxConcurrency, null, credentialVersion);
		}

		public static ProviderResolution denied(String reason) {
			return new ProviderResolution(ResolutionType.DENIED, null, null, null, null, null, null, false, 0, null,
					null, reason, null);
		}

		public boolean isByok() {
			return type == ResolutionType.BYOK;
		}

		public boolean isPlatform() {
			return type == ResolutionType.PLATFORM;
		}

		public boolean isDenied() {
			return type == ResolutionType.DENIED;
		}

		/**
		 * 是否需要解密（有密文即需要）。
		 *
		 * <p>
		 * 任务书 #47 S2 起<b>不再限定 BYOK</b>：平台凭据也带密文。平台凭据无密钥时仍为 false， 由执行层按 503
		 * fail-closed（任务书 #58 决策 E，env bootstrap 兜底已删；Sandbox 假 provider 除外）。
		 */
		public boolean needsKeyDecryption() {
			return encryptedKey != null && !encryptedKey.isBlank();
		}

		/**
		 * 平台解析<b>且</b>凭据自带密钥（任务书 #47 S2）。
		 *
		 * <p>
		 * 给那些本就有 provider 专属凭据配置的执行点用（Embedding / Speech）：精确表达
		 * 「凭据真配了密钥才取解密明文」，其余平台分支（Sandbox 或无密钥）不会走到这里。
		 */
		public boolean hasPlatformCredentialKey() {
			return isPlatform() && needsKeyDecryption();
		}

		public String modelVersionKey() {
			if (isPlatform()) {
				return "platform:" + platformModelVersion;
			}
			if (isByok()) {
				return (byokOrganizationId == null ? "byok:" : "byok-org:") + keyVersion;
			}
			throw new IllegalStateException("拒绝结果没有模型版本");
		}
	}

	/** 解析类型。 */
	public enum ResolutionType {
		/** 用户自带 Key。 */
		BYOK,
		/** 平台默认模型（经控制面解析的主/备）。 */
		PLATFORM,
		/** 拒绝执行（无 BYOK 且回退未授权，或无平台模型可用）。 */
		DENIED
	}
}
