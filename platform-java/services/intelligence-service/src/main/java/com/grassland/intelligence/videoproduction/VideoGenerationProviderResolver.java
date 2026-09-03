package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService;
import com.grassland.intelligence.ai.controlplane.PlatformModelControlPlaneService.ResolvedPlatformModel;
import com.grassland.intelligence.ai.run.PriceTableService;
import com.grassland.intelligence.ai.run.ProviderKeyDecryptor;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 视频管线 provider 控制面解析器（任务书 #64 卡2，关闭「视频走 MiniMax 专用 env 链」的旧设计）。
 *
 * <p>
 * {@code video_generation} / {@code video_tts} 两个 capability 经
 * {@link PlatformModelControlPlaneService} 解析 primary（健康优先）+ 平台凭据，vendor
 * adapter 按行的 {@code provider} 值分派（minimax / seedance / sandbox / wan / xai）。
 * {@code VideoGenerationProperties} 的 mode/base-url/api-key/model 自此不再是生产配置来源
 * （字段保留供旧链 IT 注入 sandbox，见 application.yml 卡2 注释）。
 *
 * <p>
 * 两道 fail-closed 闸：凭据解密失败（含平台凭据缺密钥）→ Unavailable(reason)；模型在 当前 active 价目表无行 →
 * Unavailable（宁可 slideshow 也不误配 0 价）。解密与价目的明文/ 单价随 plan 返回，capabilities 显
 * available=false、建任务的卡6 拒绝建——不在这里抛 500。
 */
@Service
public class VideoGenerationProviderResolver {

	public static final String CAPABILITY_VIDEO_GENERATION = "video_generation";
	public static final String CAPABILITY_VIDEO_TTS = "video_tts";

	/** 支持的视频生成 provider 值（治理台凭据携带厂商名，这里强制白名单=已实现适配器清单）。 */
	private static final Set<String> VIDEO_PROVIDERS = Set.of("sandbox", "minimax", "seedance", "wan", "xai");
	private static final Set<String> TTS_PROVIDERS = Set.of("sandbox", "minimax");

	private final PlatformModelControlPlaneService controlPlane;
	private final ProviderKeyDecryptor keyDecryptor;
	private final PriceTableService priceTable;
	private final VideoGenerationProperties properties;
	private final SandboxVideoGenerationProvider sandbox = new SandboxVideoGenerationProvider();
	private final SandboxTtsProvider sandboxTts = new SandboxTtsProvider();

	public VideoGenerationProviderResolver(PlatformModelControlPlaneService controlPlane,
			ProviderKeyDecryptor keyDecryptor, PriceTableService priceTable, VideoGenerationProperties properties) {
		this.controlPlane = controlPlane;
		this.keyDecryptor = keyDecryptor;
		this.priceTable = priceTable;
		this.properties = properties;
	}

	/** 视频生成渠道解析：无行 = slideshow（前端按 mode 明示降级，不锁死）。 */
	public Mono<VideoProviderResolution> resolveVideoGeneration() {
		return controlPlane.resolve(CAPABILITY_VIDEO_GENERATION).map(
				row -> row.map(this::toVideoPlan).orElseGet(() -> VideoProviderResolution.unavailable("未配置视频生成模型")));
	}

	/** 配音渠道解析（卡5 TTS worker 同款）：无行 = skipped（无配音模式，不算失败）。 */
	public Mono<TtsProviderResolution> resolveTts() {
		return controlPlane.resolve(CAPABILITY_VIDEO_TTS)
				.map(row -> row.map(this::toTtsPlan).orElseGet(() -> TtsProviderResolution.unavailable("配音模型未配置")));
	}

	private VideoProviderResolution toVideoPlan(ResolvedPlatformModel row) {
		String provider = normalized(row.provider());
		if (!VIDEO_PROVIDERS.contains(provider)) {
			return VideoProviderResolution.unavailable("不支持的视频 provider: " + row.provider());
		}
		String apiKey = decryptedKey(row);
		if (apiKey == null && !"sandbox".equals(provider)) {
			return VideoProviderResolution.unavailable(decryptFailure);
		}
		int unitPriceCents;
		String priceTableVersion;
		try {
			unitPriceCents = priceTable.priceFor(null, row.model()).centsPerSecond();
			priceTableVersion = priceTable.currentVersionLabel();
		} catch (IllegalArgumentException error) {
			return VideoProviderResolution.unavailable("视频生成模型缺少价目配置: " + row.model());
		}
		VideoGenerationProvider adapter = switch (provider) {
			case "sandbox" -> sandbox;
			case "minimax" -> new MinimaxVideoGenerationProvider(pathOverridden(
					VideoProviderEndpoint.minimax(row.baseUrl(), apiKey, properties.getRequestTimeout())));
			case "seedance" -> new SeedanceVideoGenerationProvider(pathOverridden(
					VideoProviderEndpoint.seedance(row.baseUrl(), apiKey, properties.getRequestTimeout())));
			case "wan" -> new WanVideoGenerationProvider(pathOverridden(new VideoProviderEndpoint(row.baseUrl(), apiKey,
					"/api/v1/services/aigc/video-generation/video-synthesis", "/api/v1/tasks/{taskId}",
					"/api/v1/tasks/{taskId}", properties.getRequestTimeout())));
			case "xai" -> new XaiVideoGenerationProvider(
					pathOverridden(VideoProviderEndpoint.xai(row.baseUrl(), apiKey, properties.getRequestTimeout())));
			default -> throw new IllegalStateException("unreachable: provider whitelist 已拦截 " + provider);
		};
		return VideoProviderResolution.of(new VideoProviderResolution.Plan(adapter, toProviderResolution(row),
				unitPriceCents, priceTableVersion));
	}

	private TtsProviderResolution toTtsPlan(ResolvedPlatformModel row) {
		String provider = normalized(row.provider());
		if (!TTS_PROVIDERS.contains(provider)) {
			return TtsProviderResolution.unavailable("不支持的视频配音 provider: " + row.provider());
		}
		String apiKey = decryptedKey(row);
		if (apiKey == null && !"sandbox".equals(provider)) {
			return TtsProviderResolution.unavailable(decryptFailure);
		}
		try {
			// 免费分支（feature=null）也要过估价：模型无价目行会被执行环按 unpriced_model 拒绝，
			// 这里提前显式化，capabilities 才能给运营明确的「配音模型缺少价目配置」。
			priceTable.priceFor(null, row.model());
		} catch (IllegalArgumentException error) {
			return TtsProviderResolution.unavailable("配音模型缺少价目配置: " + row.model());
		}
		return TtsProviderResolution.available(provider, row.model(), apiKey, row.baseUrl(), row.maxConcurrency(),
				row.version(), row.configId(), ttsAdapter(provider, row, apiKey));
	}

	/** 卡5：TTS adapter 按控制面 provider 值分派（sandbox 纯 Java 合成 / minimax T2A 异步）。 */
	private TtsProvider ttsAdapter(String provider, ResolvedPlatformModel row, String apiKey) {
		return switch (provider) {
			case "sandbox" -> sandboxTts;
			case "minimax" ->
				new MinimaxTtsProvider(new VideoProviderEndpoint(row.baseUrl(), apiKey, "/v1/t2a_v2_async",
						"/v1/query/t2a_v2_async", "/v1/files/retrieve", properties.getRequestTimeout()));
			default -> throw new IllegalStateException("unreachable: TTS provider 白名单已拦截 " + provider);
		};
	}

	/** WireMock IT 的路径覆写出口（生产留空 = 各协议默认路径）。 */
	private VideoProviderEndpoint pathOverridden(VideoProviderEndpoint endpoint) {
		return endpoint.withPathOverrides(properties.getCreatePath(), properties.getPollPath(),
				properties.getRetrievePath());
	}

	/** 解密失败时记录 reason 并返回 null；sandbox 无密钥合法（返回 null 且不动 decryptFailure）。 */
	private String decryptedKey(ResolvedPlatformModel row) {
		try {
			return keyDecryptor.decryptIfNeeded(toProviderResolution(row));
		} catch (RuntimeException error) {
			decryptFailure = error.getMessage() == null ? "平台凭据解密失败" : error.getMessage();
			return null;
		}
	}

	// 同一线程内先写后读；volatile 只为避免可见性歧义，无并发语义依赖
	private volatile String decryptFailure;

	private ProviderResolution toProviderResolution(ResolvedPlatformModel row) {
		return ProviderResolution.platform(row.configId(), row.provider(), row.baseUrl(), row.model(), row.version(),
				row.maxConcurrency(), row.credentialEncryptedKey(), row.credentialVersion());
	}

	private static String normalized(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * 解析产物：adapter 已按解密凭据构造好；{@code resolution} 携带密文供执行环 （prepareMediaExecution
	 * 内部会再解密一次——确定性开销，明文不出本进程）。
	 */
	public record VideoProviderResolution(Plan plan, String unavailableReason) {

		public static VideoProviderResolution of(Plan plan) {
			return new VideoProviderResolution(plan, null);
		}

		public static VideoProviderResolution unavailable(String reason) {
			return new VideoProviderResolution(null, reason);
		}

		public boolean available() {
			return plan != null;
		}

		public record Plan(VideoGenerationProvider adapter, ProviderResolution resolution, int unitPriceCents,
				String priceTableVersion) {
		}
	}

	/**
	 * 配音解析产物（卡5 构造 TTS adapter 用）。{@code apiKey} 是解密明文，只在进程内传递， 绝不入日志/响应/落库。
	 */
	public record TtsProviderResolution(String provider, String model, String apiKey, String baseUrl,
			Integer maxConcurrency, int platformModelVersion, UUID configId, TtsProvider adapter,
			String unavailableReason) {

		public static TtsProviderResolution available(String provider, String model, String apiKey, String baseUrl,
				Integer maxConcurrency, int platformModelVersion, UUID configId, TtsProvider adapter) {
			return new TtsProviderResolution(provider, model, apiKey, baseUrl, maxConcurrency, platformModelVersion,
					configId, adapter, null);
		}

		public static TtsProviderResolution unavailable(String reason) {
			return new TtsProviderResolution(null, null, null, null, null, 0, null, null, reason);
		}

		public boolean available() {
			return provider != null;
		}
	}
}
