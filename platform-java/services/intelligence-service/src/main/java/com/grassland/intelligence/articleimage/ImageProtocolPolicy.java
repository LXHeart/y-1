package com.grassland.intelligence.articleimage;

import com.grassland.intelligence.ai.controlplane.PlatformProviderNames;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 图片协议能力与合法输入的<b>单源定义</b>（任务书 #101 C101-07 §6.6）。
 *
 * <p>
 * 三个协议口径：{@code openai-image}（原生生成／编辑，通用图像参考）、{@code minimax-character}（仅人物一致参考）、
 * {@code legacy-generation}（openai-compatible 等旧路径，参考图只做文本描述增强，不传字节）。
 *
 * <p>
 * 能力绑定约束（§6.6）：{@code openai-image} 仅允许绑定
 * {@code image_generation}——控制面平台模型与个人／组织 BYOK 三处保存入口共用
 * {@link #requireImageOnlyCapability}，不能只检查请求体 provider（凭据覆盖后的实际 provider
 * 同样受限）。
 */
public final class ImageProtocolPolicy {

	/** 原生协议首版生成的固定尺寸（/images/generations 与 /images/edits 通用值集）。 */
	public static final List<String> OPENAI_IMAGE_SIZES = List.of("1024x1024", "1024x1536", "1536x1024");

	/** 原生编辑实际发送的参考图上限：1 张、PNG/JPEG、≤5 MiB（§5.1）。 */
	public static final int OPENAI_IMAGE_MAX_REFERENCES = 1;
	public static final int OPENAI_IMAGE_MAX_REFERENCE_BYTES = 5 * 1024 * 1024;

	/** MiniMax subject_reference：人物一致参考（官方契约 JPG/PNG ≤10MB）。 */
	public static final List<String> MINIMAX_SIZES = List.of();

	public static final String PROTOCOL_OPENAI_IMAGE = "openai-image";
	public static final String PROTOCOL_MINIMAX_CHARACTER = "minimax-character";
	public static final String PROTOCOL_LEGACY = "legacy-generation";

	private static final Set<String> REFERENCE_MIME = Set.of("image/png", "image/jpeg");

	private ImageProtocolPolicy() {
	}

	/** provider 名（凭据解析后的实际值）→ 协议口径；未知/空走 legacy，不因模型名含 image 自动升级。 */
	public static String protocolOf(String provider, String baseUrl) {
		if (PlatformProviderNames.OPENAI_IMAGE.equals(normalize(provider))) {
			return PROTOCOL_OPENAI_IMAGE;
		}
		if (isMinimax(provider, baseUrl)) {
			return PROTOCOL_MINIMAX_CHARACTER;
		}
		return PROTOCOL_LEGACY;
	}

	/** 协议支持的参考类型：image=通用图像参考；character=仅人物一致；legacy 无。 */
	public static List<String> referenceKindsOf(String protocol) {
		return switch (protocol) {
			case PROTOCOL_OPENAI_IMAGE -> List.of("image");
			case PROTOCOL_MINIMAX_CHARACTER -> List.of("character");
			default -> List.of();
		};
	}

	public static boolean supportsImageReference(String protocol) {
		return PROTOCOL_OPENAI_IMAGE.equals(protocol);
	}

	public static boolean supportsCharacterReference(String protocol) {
		return PROTOCOL_MINIMAX_CHARACTER.equals(protocol);
	}

	public static List<String> generationSizesOf(String protocol) {
		return switch (protocol) {
			case PROTOCOL_OPENAI_IMAGE -> OPENAI_IMAGE_SIZES;
			case PROTOCOL_MINIMAX_CHARACTER -> MINIMAX_SIZES;
			default -> List.of();
		};
	}

	/**
	 * 保存入口的 provider×capability 绑定校验：{@code openai-image} 只允许
	 * {@code image_generation}，绑定 text/video 等其余能力一律 400（§6.6）。
	 */
	public static void requireImageOnlyCapability(String capability, String provider) {
		if (capability != null && PlatformProviderNames.OPENAI_IMAGE.equals(normalize(provider))
				&& !"image_generation".equals(capability)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "openai-image 协议只允许绑定 image_generation 能力");
		}
	}

	/**
	 * 原生编辑参考图合法性：PNG/JPEG 且 ≤5 MiB，超限/非法 MIME 直接 400——不静默丢弃参考图继续生成（§5.1）。
	 */
	public static void requireAcceptableNativeReference(ReferenceImage image) {
		String mime = normalize(image.mimeType());
		if (!REFERENCE_MIME.contains(mime)) {
			throw new IntelligenceException(400, "STUDIO_UNSUPPORTED_FORMAT", "参考图只支持 PNG/JPEG");
		}
		if (image.bytes().length > OPENAI_IMAGE_MAX_REFERENCE_BYTES) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "参考图超过 5 MiB 上限，请先压缩后再试");
		}
	}

	private static boolean isMinimax(String provider, String baseUrl) {
		if (provider != null && provider.toLowerCase(Locale.ROOT).contains("minimax")) {
			return true;
		}
		return baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).contains("minimax");
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
