package com.grassland.intelligence.creationstudio;

import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.articleimage.ImageProtocolPolicy;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-02：读取本人草稿的实际图片路由能力（C101-07）。
 *
 * <p>
 * 能力来自服务端实际路由与冻结配置（凭据解析后的 provider 决定协议），不接受浏览器自报； capabilities
 * 读取本身不调用供应商生成接口（无探测式生图）。{@code studioEnabled}/{@code wechatEnabled} 反映服务端开关状态，
 * 与 Edge 路由 flag 各自独立。
 */
@RestController
public class ImageCapabilitiesController {

	private final IntelligenceCallerResolver callers;
	private final CreationStudioContextService context;
	private final ByokRoutingService routing;
	private final CreationStudioProperties studio;
	private final com.grassland.intelligence.creationstudio.wechat.WechatProperties wechat;

	public ImageCapabilitiesController(IntelligenceCallerResolver callers, CreationStudioContextService context,
			ByokRoutingService routing, CreationStudioProperties studio,
			com.grassland.intelligence.creationstudio.wechat.WechatProperties wechat) {
		this.callers = callers;
		this.context = context;
		this.routing = routing;
		this.studio = studio;
		this.wechat = wechat;
	}

	@GetMapping("/api/creation-studio/drafts/{draftId}/capabilities")
	public Mono<Map<String, Object>> capabilities(@PathVariable String draftId, ServerWebExchange exchange) {
		UUID id;
		try {
			id = UUID.fromString(draftId);
		} catch (Exception error) {
			throw new com.grassland.intelligence.security.IntelligenceException(404, "STUDIO_NOT_FOUND", "草稿不存在");
		}
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> context
				.loadOwnedDraftContext(id.toString(), caller)
				.flatMap(draft -> context.imageRoute(draft.draft(), caller.accountId(), caller.organizationId())
						.map(route -> Map.of("success", true, "data", body(route))).onErrorReturn(availableFalse()))
				.defaultIfEmpty(availableFalse()));
	}

	/** 路由拒绝/依赖缺失：能力不可用但结构完整（available=false + 原因），不 500。 */
	private Map<String, Object> availableFalse() {
		Map<String, Object> image = new LinkedHashMap<>();
		image.put("protocol", ImageProtocolPolicy.PROTOCOL_LEGACY);
		image.put("referenceKinds", List.of());
		image.put("maxReferences", 0);
		image.put("maxReferenceBytes", 0);
		image.put("generationSizes", List.of());
		image.put("providerLabel", "");
		image.put("modelLabel", "");
		image.put("configurationFingerprint", "");
		image.put("available", false);
		image.put("unavailableReason", "当前没有可用的图片生成配置，请先在设置或治理台配置");
		return Map.of("success", true, "data", Map.of("image", image, "studioEnabled", studio.isWritesEnabled(),
				"wechatEnabled", wechat.isWritesEnabled()));
	}

	private Map<String, Object> body(CreationStudioContextService.ImageRoute route) {
		ProviderResolution provider = route.provider();
		String protocol = ImageProtocolPolicy.protocolOf(provider.provider(), provider.baseUrl());
		boolean available = provider.isByok() || provider.isPlatform();
		Map<String, Object> image = new LinkedHashMap<>();
		image.put("protocol", protocol);
		image.put("referenceKinds", ImageProtocolPolicy.referenceKindsOf(protocol));
		image.put("maxReferences",
				ImageProtocolPolicy.supportsImageReference(protocol)
						? ImageProtocolPolicy.OPENAI_IMAGE_MAX_REFERENCES
						: (ImageProtocolPolicy.supportsCharacterReference(protocol) ? 1 : 0));
		image.put("maxReferenceBytes",
				ImageProtocolPolicy.supportsImageReference(protocol)
						? ImageProtocolPolicy.OPENAI_IMAGE_MAX_REFERENCE_BYTES
						: (ImageProtocolPolicy.supportsCharacterReference(protocol) ? 10 * 1024 * 1024 : 0));
		image.put("generationSizes", ImageProtocolPolicy.generationSizesOf(protocol));
		image.put("providerLabel", provider.provider() == null ? "" : provider.provider());
		image.put("modelLabel", provider.model() == null ? "" : provider.model());
		image.put("configurationFingerprint", CreationStudioContextService.imageFingerprint(route));
		image.put("available", available);
		image.put("unavailableReason",
				available ? null : (provider.denialReason() == null ? "图片生成配置不可用" : provider.denialReason()));
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("image", image);
		// 开关为服务端写闸；读取能力本身不要求写开放（§7.6 关写保留读）。
		data.put("studioEnabled", studio.isWritesEnabled());
		data.put("wechatEnabled", wechat.isWritesEnabled());
		return data;
	}

	private static String fingerprint(ProviderResolution provider) {
		Map<String, Object> canonical = new TreeMap<>();
		canonical.put("provider", provider.provider());
		canonical.put("model", provider.model());
		canonical.put("platformModelVersion", provider.platformModelVersion());
		canonical.put("credentialVersion", provider.credentialVersion() == null ? 0 : provider.credentialVersion());
		String json = canonical.toString();
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			return java.util.HexFormat.of()
					.formatHex(digest.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}
}
