package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarItem;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarSource;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.BackendItem;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.BackendState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.BackendTransport;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Catalog;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Limits;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileInput;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.PublicCatalog;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.VoiceItem;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 数字人目录（任务书 #105B C105B-03 / K02、K14.1）：dh_catalog 配置 + 控制面 render 能力投影。
 *
 * <p>
 * 目录不建第二套模型配置：backend 唯一来源是 {@code platform_model_config} 的
 * {@code digital_human_render} 行（复用既有凭据/主备/健康链路），dh_catalog 只维护组合、功能状态与
 * {@code allowedBackendIds} 批准集。控制面无行/停用/未受信端点 → backend 不可用，目录<b>不</b>经
 * renderer-profile/env 兜底。预设下架/不兼容形象/不支持 voice 一律 422
 * {@code dh_combination_unsupported}；catalogVersion 失效 409
 * {@code dh_configuration_changed}。
 */
@Component
public class DigitalHumanCatalogService {

	/** 无配置行时的默认目录版本（0=未初始化，任何 catalogVersion 校验都过不去）。 */
	public static final int UNCONFIGURED_VERSION = 0;

	private static final ObjectMapper JSON = new ObjectMapper();

	private final DatabaseClient db;
	private final PlatformProviderPolicy providerPolicy;

	public DigitalHumanCatalogService(DatabaseClient db, PlatformProviderPolicy providerPolicy) {
		this.db = db;
		this.providerPolicy = providerPolicy;
	}

	/** 已登录目录（enabled=false 也如实返回关闭说明字段，K01/K02）。 */
	public Mono<Catalog> load() {
		return readConfig().flatMap(
				config -> projectBackends(config.allowedBackendIds()).map(backends -> assemble(config, backends)));
	}

	/** 游客目录：仅 {authenticated:false, enabled, description}，无私有项（K13.1）。 */
	public Mono<PublicCatalog> publicCatalog() {
		return readConfig().map(config -> new PublicCatalog(false, config.enabled(), config.description()));
	}

	/**
	 * 组合校验（profile create/update 共用）：功能关闭 404 dh_feature_disabled；catalogVersion 失效
	 * 409 dh_configuration_changed；预设下架/不兼容/voice 不支持/控制面无可用 backend → 422
	 * dh_combination_unsupported。
	 */
	public Mono<ValidatedCombination> validateCombination(ProfileInput input) {
		return readConfig().flatMap(config -> {
			if (!config.enabled()) {
				return Mono.error(new IntelligenceException(404, "dh_feature_disabled", "数字人功能暂未开放。"));
			}
			if (input.catalogVersion() != config.version()) {
				return Mono.error(new IntelligenceException(409, "dh_configuration_changed", "目录已更新，请刷新后重新选择形象与音色。"));
			}
			return projectBackends(config.allowedBackendIds()).flatMap(backends -> {
				AvatarItem avatar = null;
				for (AvatarItem candidate : config.avatars()) {
					if (candidate.id().equals(input.avatarId())) {
						avatar = candidate;
						break;
					}
				}
				if (avatar == null || avatar.state() != AvatarState.ready) {
					return Mono.error(unsupported("所选形象不可用，请重新选择。"));
				}
				VoiceItem voice = null;
				for (VoiceItem candidate : config.voices()) {
					if (candidate.id().equals(input.voiceId())) {
						voice = candidate;
						break;
					}
				}
				if (voice == null || !voice.enabled()) {
					return Mono.error(unsupported("所选音色不可用，请重新选择。"));
				}
				// 批准组合：形象与音色必须共享至少一个非 unavailable backend（控制面无行即拒绝）。
				Set<String> usable = new HashSet<>();
				for (BackendItem backend : backends) {
					if (backend.state() != BackendState.unavailable) {
						usable.add(backend.id());
					}
				}
				usable.retainAll(avatar.compatibleBackendIds());
				usable.retainAll(voice.compatibleBackendIds());
				if (usable.isEmpty()) {
					return Mono.error(unsupported("该形象与音色的组合暂不支持，请重新选择。"));
				}
				return Mono.just(new ValidatedCombination(config.version(), avatar.revision(), List.copyOf(usable)));
			});
		});
	}

	/** 组合校验结果：目录版本 + 冻结 avatarRevision + 可用 backend 集（写入 revision 快照）。 */
	public record ValidatedCombination(int catalogVersion, int avatarRevision, List<String> backendIds) {
	}

	private static IntelligenceException unsupported(String message) {
		return new IntelligenceException(422, "dh_combination_unsupported", message);
	}

	// ---------- dh_catalog 配置读取 ----------

	record CatalogConfig(int version, boolean enabled, boolean newSessionsAllowed, boolean recordingEnabled,
			boolean customAvatarEnabled, String description, String billingNoticeVersion, int maxSessionsGlobal,
			int maxQueuedGlobal, List<AvatarItem> avatars, List<VoiceItem> voices, Set<String> allowedBackendIds) {

		static CatalogConfig unconfigured() {
			return new CatalogConfig(UNCONFIGURED_VERSION, false, false, false, false, "数字人创作助手当前未开放。", "dh-billing-v1",
					1, 10, List.of(), List.of(), Set.of());
		}
	}

	private Mono<CatalogConfig> readConfig() {
		return db.sql("SELECT version, config_json::text AS config FROM dh_catalog WHERE singleton_id = 1")
				.map((row, metadata) -> parseConfig(row.get("version", Integer.class), row.get("config", String.class)))
				.one().map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.<CatalogConfig>empty())
				.map(optional -> optional.orElseGet(CatalogConfig::unconfigured));
	}

	private static CatalogConfig parseConfig(Integer version, String configJson) {
		try {
			JsonNode config = JSON.readTree(configJson == null ? "{}" : configJson);
			List<AvatarItem> avatars = new ArrayList<>();
			Set<String> presetDisabled = new HashSet<>();
			for (JsonNode node : config.path("presetAvatarStates")) {
				if (!node.path("enabled").asBoolean(true)) {
					presetDisabled.add(node.path("id").asText());
				}
			}
			for (JsonNode node : config.path("avatars")) {
				String id = node.path("id").asText();
				// 下架覆盖（K10 presetAvatarStates）：关闭的预设投影为 revoked，不得继续组合。
				boolean disabled = presetDisabled.contains(id);
				avatars.add(new AvatarItem(id, node.path("revision").asInt(1), node.path("name").asText(),
						node.path("previewMediaId").asText(),
						AvatarSource.valueOf(node.path("source").asText("preset")),
						disabled ? AvatarState.revoked : AvatarState.valueOf(node.path("state").asText("ready")),
						readStrings(node.path("compatibleBackendIds")),
						disabled
								? "preset_disabled"
								: node.hasNonNull("reasonCode") ? node.path("reasonCode").asText() : null));
			}
			List<AvatarItem> effective = List.copyOf(avatars);
			List<VoiceItem> voices = new ArrayList<>();
			Set<String> voiceDisabled = new HashSet<>();
			for (JsonNode node : config.path("voiceStates")) {
				if (!node.path("enabled").asBoolean(true)) {
					voiceDisabled.add(node.path("id").asText());
				}
			}
			for (JsonNode node : config.path("voices")) {
				String id = node.path("id").asText();
				boolean enabled = node.path("enabled").asBoolean(true) && !voiceDisabled.contains(id);
				voices.add(
						new VoiceItem(id, node.path("name").asText(), "zh-CN", node.path("providerModelRef").asText(),
								readStrings(node.path("compatibleBackendIds")), enabled));
			}
			Set<String> allowed = new HashSet<>(readStrings(config.path("allowedBackendIds")));
			return new CatalogConfig(version == null ? 1 : version, config.path("enabled").asBoolean(false),
					config.path("newSessionsAllowed").asBoolean(false),
					config.path("recordingEnabled").asBoolean(false),
					config.path("customAvatarEnabled").asBoolean(false),
					config.path("description").asText("数字人创作助手当前未开放。"),
					config.path("billingNoticeVersion").asText("dh-billing-v1"),
					config.path("maxSessionsGlobal").asInt(1), config.path("maxQueuedGlobal").asInt(10),
					List.copyOf(effective), List.copyOf(voices), allowed);
		} catch (Exception invalid) {
			return CatalogConfig.unconfigured();
		}
	}

	private static List<String> readStrings(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (node != null && node.isArray()) {
			for (JsonNode item : node) {
				values.add(item.asText());
			}
		}
		return values;
	}

	private Catalog assemble(CatalogConfig config, List<BackendItem> backends) {
		Limits limits = new Limits(1, config.maxSessionsGlobal(), config.maxQueuedGlobal(), 600000, 120000, 30000, 10,
				300000);
		return new Catalog(true, config.version(), config.enabled(), config.newSessionsAllowed(),
				config.recordingEnabled(), config.customAvatarEnabled(), config.avatars(), config.voices(), backends,
				limits, config.billingNoticeVersion());
	}

	// ---------- 控制面投影（K14.1） ----------

	private Mono<List<BackendItem>> projectBackends(Set<String> allowedBackendIds) {
		return db.sql("""
				SELECT config.id::text AS id, config.model, config.version, config.health_status,
				       config.credential_id::text AS credential_id,
				       COALESCE(cred.base_url, config.base_url) AS base_url
				FROM platform_model_config config
				LEFT JOIN platform_provider_credential cred ON cred.id = config.credential_id
				WHERE config.capability = 'digital_human_render' AND config.enabled = true
				""").map((row, metadata) -> row).all().collectList().flatMap(rows -> {
			List<BackendItem> backends = new ArrayList<>();
			for (io.r2dbc.spi.Readable row : rows) {
				String id = row.get("id", String.class);
				String baseUrl = row.get("base_url", String.class);
				String credentialId = row.get("credential_id", String.class);
				String health = row.get("health_status", String.class);
				BackendState state;
				if (credentialId == null || "unhealthy".equalsIgnoreCase(health) || !trusted(baseUrl)) {
					state = BackendState.unavailable;
				} else if (allowedBackendIds.contains(id)) {
					state = BackendState.approved;
				} else {
					state = BackendState.test_only;
				}
				backends.add(new BackendItem(id, row.get("model", String.class), id,
						row.get("version", Integer.class) == null
								? null
								: String.valueOf(row.get("version", Integer.class)),
						BackendTransport.remote, state, false, false, 0, 0, 25, 0, null));
			}
			return Mono.just(List.copyOf(backends));
		}).defaultIfEmpty(List.of());
	}

	/** 目的地受信（SSRF 闸门复用 PlatformProviderPolicy；未受信 → 投影为 unavailable，不建旁路）。 */
	private boolean trusted(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return false;
		}
		try {
			providerPolicy.validateBaseUrl(baseUrl);
			return true;
		} catch (Exception rejected) {
			return false;
		}
	}
}
