package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import com.grassland.intelligence.ai.run.PriceTableService;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.BillingItem;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InputMode;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ModelSource;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Preflight;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 预检（任务书 #105C C105C-01 / K03 API07、K08.1）：无扣费报价与 60 秒易失快照。
 *
 * <p>
 * 只解析配置与报价，不 prepareExecution、不派发 provider（TC105C-01-02 断言 provider 调用与积分变化均
 * 0）。四项模型（LLM=text、STT=voice、TTS=video_tts、render=digital_human_render）全部来自既有控制面
 * 现行生效行：缺行/停用/无凭据/不健康/目的地未受信/价表缺该模型 → 409 {@code dh_configuration_changed}
 * （K14：不经 env/renderer-profile 兜底）。快照存独立易失 Redis（TTL 60s，键
 * {@code dh:preflight:{id}}，无 secret）；create 的最终一次性由
 * {@code dh_session.preflight_id} UNIQUE 保证，Redis 丢失只要求重新预检。
 */
@Component
public class DigitalHumanPreflightService {

	/** K01：sessionCostCapCents 首期固定 300 分（平台成本授权上限，不是积分数量）。 */
	public static final long PLATFORM_COST_CAP_CENTS = 300;

	// Instant 需 JSR310 模块（findAndRegisterModules；服务内私有实例，不依赖全局 bean）。
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
	private static final Duration TTL = Duration.ofSeconds(60);
	/** K01 限制用于报价上界（时长上界 → 秒 → cents）。 */
	private static final int SESSION_MAX_SECONDS = 600;
	private static final int TTS_SEGMENT_MAX_SECONDS = 90;
	private static final int AUDIO_INPUT_MAX_SECONDS = 60;
	private static final int ESTIMATED_LLM_INPUT_TOKENS = 65_536 / 4;
	private static final int ESTIMATED_LLM_OUTPUT_TOKENS = 1024;

	/** 快照（Redis 载荷；无密钥/正文）。 */
	public record Snapshot(String id, String ownerId, String profileId, int profileVersion, int catalogVersion,
			String inputMode, String backendId, String controllerId, String llmModel, String sttModel, String ttsModel,
			String renderModel, String priceTableVersion, Instant expiresAt) {
	}

	private final DatabaseClient db;
	private final ReactiveStringRedisTemplate redis;
	private final PriceTableService prices;
	private final PlatformProviderPolicy providerPolicy;
	private final DigitalHumanCatalogService catalog;

	public DigitalHumanPreflightService(DatabaseClient db, ReactiveStringRedisTemplate redis, PriceTableService prices,
			PlatformProviderPolicy providerPolicy, DigitalHumanCatalogService catalog) {
		this.db = db;
		this.redis = redis;
		this.prices = prices;
		this.providerPolicy = providerPolicy;
		this.catalog = catalog;
	}

	/** API07：报价（上限估计）；不预留、不调用、不建 run。 */
	public Mono<Preflight> check(PersonalActor actor, UUID profileId, int profileVersion, InputMode inputMode,
			UUID controllerId) {
		return catalog.load().flatMap(catalogDto -> {
			if (!catalogDto.enabled() || !catalogDto.newSessionsAllowed()) {
				return Mono.error(new IntelligenceException(404, "dh_feature_disabled", "数字人功能暂未开放。"));
			}
			return loadProfile(actor, profileId, profileVersion, catalogDto)
					.flatMap(profile -> resolveModels().flatMap(models -> Mono.fromCallable(() -> {
						String priceTableVersion = prices.currentVersionLabel();
						List<BillingItem> items = new ArrayList<>();
						items.add(bill("llm", "platform", models.llm(), "user", priceTableVersion, "token",
								estimatedCents(models.llm(), ESTIMATED_LLM_INPUT_TOKENS, ESTIMATED_LLM_OUTPUT_TOKENS,
										0)));
						items.add(bill("stt", "platform", models.stt(), "user", priceTableVersion, "second",
								estimatedCents(models.stt(), 0, 0, AUDIO_INPUT_MAX_SECONDS)));
						items.add(bill("tts", "platform", models.tts(), "platform", priceTableVersion, "second",
								estimatedCents(models.tts(), 0, 0, TTS_SEGMENT_MAX_SECONDS)));
						items.add(bill("render", "platform", models.render(), "platform", priceTableVersion, "second",
								estimatedCents(models.render(), 0, 0, SESSION_MAX_SECONDS)));
						return new Preflight(models.toPreflightId().toString(), Instant.now().plus(TTL),
								profileId.toString(), profileVersion, catalogDto.version(), inputMode,
								ModelSource.platform, models.llm(), models.stt(), models.tts(), models.render(),
								priceTableVersion, "dh-billing-v1", catalogDto.limits(), PLATFORM_COST_CAP_CENTS,
								PLATFORM_COST_CAP_CENTS, List.copyOf(items), 0L, profile.backendId(),
								controllerId.toString());
					}).flatMap(dto -> save(actor, profile.backendId(), dto, models))));
		});
	}

	/**
	 * create 侧消费：读快照（可重复读；一次性由 DB UNIQUE 保证）。缺失 → 410 dh_preflight_expired；他人快照 →
	 * 404。
	 */
	public Mono<Snapshot> consume(PersonalActor actor, UUID preflightId) {
		return redis.opsForValue().get("dh:preflight:" + preflightId)
				.flatMap(json -> Mono.fromCallable(() -> JSON.readValue(json, Snapshot.class)))
				.switchIfEmpty(Mono.error(new IntelligenceException(410, "dh_preflight_expired", "预检已过期，请重新确认配置与报价。")))
				.flatMap(snapshot -> actor.accountId().equals(snapshot.ownerId())
						? Mono.just(snapshot)
						: Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private Mono<Preflight> save(PersonalActor actor, String backendId, Preflight dto, ModelSet models) {
		Snapshot snapshot = new Snapshot(dto.id(), actor.accountId(), dto.profileId(), dto.profileVersion(),
				dto.catalogVersion(), dto.inputMode().name(), backendId, dto.controllerId(), models.llm(), models.stt(),
				models.tts(), models.render(), dto.priceTableVersion(), dto.expiresAt());
		return Mono.fromCallable(() -> JSON.writeValueAsString(snapshot))
				.flatMap(json -> redis.opsForValue().set("dh:preflight:" + dto.id(), json, TTL)).thenReturn(dto);
	}

	private record ProfileView(int version, int catalogVersion, String backendId) {
	}

	private Mono<ProfileView> loadProfile(PersonalActor actor, UUID profileId, int profileVersion,
			com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Catalog catalogDto) {
		return db
				.sql("SELECT version FROM dh_profile WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner"
						+ " AND status = 'active'")
				.bind("id", profileId.toString()).bind("owner", actor.accountId())
				.map(row -> row.get("version", Integer.class)).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
				.flatMap(version -> {
					if (version != profileVersion) {
						return Mono.error(new IntelligenceException(409, "dh_configuration_changed", "角色已更新，请重新确认。"));
					}
					// 组合当前仍批准（目录改版即失效，不静默换模型）。
					return Mono.fromSupplier(() -> {
						String backend = catalogDto.backends().stream()
								.filter(b -> b.state() != DigitalHumanRecords.BackendState.unavailable)
								.map(DigitalHumanRecords.BackendItem::id).findFirst().orElse(null);
						if (backend == null) {
							throw new IntelligenceException(409, "dh_configuration_changed", "渲染服务暂不可用，请稍后再试。");
						}
						return new ProfileView(version, catalogDto.version(), backend);
					});
				});
	}

	record ModelSet(String llm, String stt, String tts, String render) {

		UUID toPreflightId() {
			return UUID.randomUUID();
		}
	}

	/** 四项模型现行解析（capability→enabled+credential+healthy+受信；缺一即 409）。 */
	private Mono<ModelSet> resolveModels() {
		return db.sql("""
				SELECT config.capability, config.model, config.health_status, config.credential_id::text AS cred,
				       COALESCE(cred2.base_url, config.base_url) AS base_url
				FROM platform_model_config config
				LEFT JOIN platform_provider_credential cred2 ON cred2.id = config.credential_id
				WHERE config.enabled = true AND config.capability IN ('text','voice','video_tts','digital_human_render')
				""").map((row, metadata) -> row).all().collectList().flatMap(rows -> {
			String llm = null;
			String stt = null;
			String tts = null;
			String render = null;
			for (io.r2dbc.spi.Readable row : rows) {
				String capability = row.get("capability", String.class);
				String model = row.get("model", String.class);
				boolean usable = row.get("cred", String.class) != null
						&& !"unhealthy".equalsIgnoreCase(row.get("health_status", String.class))
						&& trusted(row.get("base_url", String.class));
				if (!usable) {
					continue;
				}
				switch (capability) {
					case "text" -> llm = model;
					case "voice" -> stt = model;
					case "video_tts" -> tts = model;
					case "digital_human_render" -> render = model;
					default -> {
					}
				}
			}
			if (llm == null || stt == null || tts == null || render == null) {
				return Mono.error(new IntelligenceException(409, "dh_configuration_changed", "数字人模型配置不完整，请稍后再试。"));
			}
			// 价表缺该模型 → 拒绝（无价不派发）。
			for (String model : List.of(llm, stt, tts, render)) {
				try {
					prices.priceFor(null, model);
				} catch (Exception unpriced) {
					return Mono.error(new IntelligenceException(409, "dh_configuration_changed", "模型暂不支持数字人服务。"));
				}
			}
			return Mono.just(new ModelSet(llm, stt, tts, render));
		});
	}

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

	private static BillingItem bill(String stage, String modelSource, String modelLabel, String chargeTo,
			String priceTableVersion, String unit, Long estimatedCents) {
		return new BillingItem(stage, modelSource, modelLabel, chargeTo, priceTableVersion, unit, estimatedCents);
	}

	private Long estimatedCents(String model, int tokens, int outputTokens, int seconds) {
		try {
			return (long) prices.estimateCost(model, tokens + outputTokens, 0, seconds);
		} catch (Exception unpriced) {
			throw new IntelligenceException(409, "dh_configuration_changed", "模型暂不支持数字人服务。");
		}
	}
}
