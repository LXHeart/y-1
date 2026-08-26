package com.grassland.intelligence.media;

import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.ContentPart;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 媒体多模态审核（缺口清偿之五 + 任务书 #45 登记）：多模态内容安全审核一次，结论落
 * {@code store_media_moderation}（表名沿用门店媒体起源，已泛化承载全部受审媒体）。
 *
 * <p>受审范围：{@code store_media}（门店公开媒体，confirm 时同步审）+ AI 生成媒体（{@code video_asset}
 * 视频产出/改编出图、{@code article_generated} 文章配图——生成落库时异步审）。{@code content_asset} 与
 * {@code user_upload} 私有素材不审（明确排除）；imagestudio 抠图结果无 media 行且仅为已审输入的抠像
 * 变换（30 分钟 TTL），不单独立项送审。
 *
 * <p>
 * 姿态对齐内容安全 ADR-D16 D6 advisory：审核模型未配置/调用失败/输出不可解析为 verdict
 * 语义时<b>不阻断上传与公开展示</b>——无行=未审；显式 {@code blocked} 才被公开端点过滤。 输出不可解析不伪装成通过：记
 * {@code review}（待人工复核）+ 说明性 finding。 视频按帧送审（遗留清偿）：ffmpeg
 * 抽帧（{@link VideoFrameExtractor}）逐帧 data URL 一并送审； 抽帧失败/零帧与模型不可用同口径，advisory
 * 降级为无行（未审）。
 */
@Component
public class StoreMediaModerationService {

	private static final Logger log = LoggerFactory.getLogger(StoreMediaModerationService.class);
	private static final Base64.Encoder BASE64 = Base64.getEncoder();
	private static final String FRAME_MIME = "image/jpeg";
	private static final String MODERATION_FAILURE = "媒体审核服务暂不可用";

	private final RoutedTextCompletionService routed;
	private final StoreMediaModerationRepository moderation;
	private final VideoFrameExtractor frameExtractor;
	private final String provider;
	private final Duration timeout;

	public StoreMediaModerationService(RoutedTextCompletionService routed, StoreMediaModerationRepository moderation,
			VideoFrameExtractor frameExtractor, Environment environment) {
		this.routed = routed;
		this.moderation = moderation;
		this.frameExtractor = frameExtractor;
		this.provider = environment.getProperty("ai.store-media-moderation.provider", "qwen");
		long timeoutMs = environment.getProperty("ai.store-media-moderation.timeout-ms", Long.class, 30_000L);
		this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 120_000)));
	}

	/** 审核结论：status ∈ pass/review/blocked（blocked = 公开展示拦截）。 */
	public record Verdict(String status, List<Finding> findings, String model, String runId) {
		public record Finding(String category, String severity, String advice) {
		}
	}

	/**
	 * confirm 后审核一次（已有结论不重跑）。{@code bytes} 为惰性对象字节拉取——仅在确实要送审 （受审
	 * purpose/mime 且无既有结论）时才订阅。返回落库后的行；未审（未配置/失败）返回 empty。
	 */
	public Mono<StoreMediaModerationRepository.ModerationRow> moderateOnce(MediaReference ref, Mono<byte[]> bytes) {
		if (!isModeratedPurpose(ref.purpose())
				|| (!isSupportedImage(ref.mimeType()) && !isSupportedVideo(ref.mimeType()))) {
			return Mono.empty();
		}
		return moderation.exists(ref.id()).flatMap(
				exists -> exists ? moderation.find(ref.id()) : bytes.flatMap(value -> runModeration(ref, value)));
	}

	/**
	 * AI 生成流钩子（任务书 #45 登记）：生成结果落库后异步送审——脱离请求路径（审核最长 30s， 不拖慢生成
	 * 响应/归档），advisory 失败静默（无行=未审），重复落库由 moderateOnce 的既有结论短路 幂等。
	 */
	public void moderateGeneratedAsync(MediaReference ref, byte[] bytes) {
		Mono.defer(() -> moderateOnce(ref, Mono.just(bytes)))
				.subscribeOn(Schedulers.boundedElastic())
				.subscribe(ignored -> { }, error -> log.warn(
						"generated media moderation skipped mediaId={} purpose={}", ref.id(), ref.purpose(), error));
	}

	/** 受审 purpose：门店公开媒体 + AI 生成媒体（用户上传通道对 article_generated 黑名单，video_asset 用户上传经 confirm 同样受审）。 */
	private static boolean isModeratedPurpose(String purpose) {
		return "store_media".equals(purpose) || "video_asset".equals(purpose)
				|| "article_generated".equals(purpose);
	}

	private Mono<StoreMediaModerationRepository.ModerationRow> runModeration(MediaReference ref, byte[] bytes) {
		if (!"qwen".equalsIgnoreCase(provider)) {
			return Mono.empty();
		}
		return moderationParts(ref, bytes).flatMap(parts -> parts.isEmpty()
				? Mono.<Verdict>empty()
				: routed.completePlatformOnly(java.util.List.of(com.grassland.intelligence.ai.ChatMessage.user(parts)), 1024, timeout, MODERATION_FAILURE)
						.map(completion -> parseVerdict(completion.content(), completion.providerRunId()))
						.onErrorResume(error -> {
							log.warn("store media moderation unavailable mediaId={}", ref.id(), error);
							return Mono.empty();
						}))
				.flatMap(verdict -> persist(ref, verdict));
	}

	/** 图片单 part；视频抽帧后逐帧 data URL part（阻塞抽帧上 boundedElastic）。空 = 未审降级。 */
	private Mono<List<ContentPart>> moderationParts(MediaReference ref, byte[] bytes) {
		boolean storeMedia = "store_media".equals(ref.purpose());
		if (isSupportedImage(ref.mimeType())) {
			String dataUrl = "data:" + ref.mimeType() + ";base64," + BASE64.encodeToString(bytes);
			return Mono.just(List.of(
					ContentPart.image(dataUrl),
					ContentPart.text(storeMedia ? rubric() : generatedRubric())));
		}
		return Mono.fromCallable(() -> frameExtractor.extract(bytes)).subscribeOn(Schedulers.boundedElastic())
				.map(frames -> {
					if (frames.isEmpty()) {
						return List.<ContentPart>of();
					}
					List<ContentPart> parts = new ArrayList<>();
					for (byte[] frame : frames) {
						parts.add(ContentPart.image("data:" + FRAME_MIME + ";base64," + BASE64.encodeToString(frame)));
					}
					parts.add(ContentPart.text(storeMedia ? videoRubric() : generatedVideoRubric()));
					return List.copyOf(parts);
				}).onErrorResume(error -> {
					log.warn("store media video frame extraction unavailable mediaId={}", ref.id(), error);
					return Mono.just(List.of());
				});
	}

	private Mono<StoreMediaModerationRepository.ModerationRow> persist(MediaReference ref, Verdict verdict) {
		String findingsJson = findingsJson(verdict.findings());
		return moderation.upsert(new StoreMediaModerationRepository.ModerationRow(ref.id(), verdict.status(),
				findingsJson, verdict.model(), verdict.runId(), Instant.now()));
	}

	private static boolean isSupportedImage(String mimeType) {
		if (mimeType == null) {
			return false;
		}
		return switch (mimeType.toLowerCase(Locale.ROOT)) {
			case "image/png", "image/jpeg", "image/webp" -> true;
			default -> false;
		};
	}

	/** 与 identity 开票前置校验的门店视频白名单一一对齐（#42 D7：mp4/mov/webm ≤20MB）。 */
	private static boolean isSupportedVideo(String mimeType) {
		if (mimeType == null) {
			return false;
		}
		return switch (mimeType.toLowerCase(Locale.ROOT)) {
			case "video/mp4", "video/quicktime", "video/webm" -> true;
			default -> false;
		};
	}

	/** verdict JSON 解析；不可解析 → review + 说明 finding（不把「读不懂」伪装成通过）。 */
	static Verdict parseVerdict(String content, String runId) {
		try {
			String stripped = stripCodeFence(content == null ? "" : content);
			var root = MAPPER.readTree(stripped);
			String verdict = root.path("verdict").asText("").trim().toLowerCase(Locale.ROOT);
			if (!verdict.equals("pass") && !verdict.equals("review") && !verdict.equals("blocked")) {
				return unparseable(runId);
			}
			List<Verdict.Finding> findings = new ArrayList<>();
			var findingsNode = root.path("findings");
			if (findingsNode.isArray()) {
				for (var node : findingsNode) {
					String category = node.path("category").asText("").trim();
					if (category.isEmpty()) {
						continue;
					}
					findings.add(new Verdict.Finding(category, node.path("severity").asText("medium"),
							node.path("advice").asText("")));
				}
			}
			return new Verdict(verdict, List.copyOf(findings), null, runId);
		} catch (Exception error) {
			log.warn("store media moderation output unparseable", error);
			return unparseable(runId);
		}
	}

	private static Verdict unparseable(String runId) {
		return new Verdict("review", List.of(new Verdict.Finding("unparseable", "medium", "审核模型输出不可解析，转人工复核")), null,
				runId);
	}

	private static String findingsJson(List<Verdict.Finding> findings) {
		try {
			var array = MAPPER.createArrayNode();
			for (Verdict.Finding finding : findings) {
				var node = array.addObject();
				node.put("category", finding.category());
				node.put("severity", finding.severity());
				node.put("advice", finding.advice());
			}
			return MAPPER.writeValueAsString(array);
		} catch (Exception error) {
			return "[]";
		}
	}

	private static String stripCodeFence(String raw) {
		String stripped = raw.trim();
		if (stripped.startsWith("```")) {
			int start = stripped.indexOf('\n');
			int end = stripped.lastIndexOf("```");
			if (start >= 0 && end > start) {
				stripped = stripped.substring(start + 1, end).trim();
			}
		}
		return stripped;
	}

	static String rubric() {
		return """
				你是门店公开媒体内容安全审核员。门店会在公开页面展示这张图片（门面/环境/菜单/宣传图），\
				检查画面是否存在以下问题（仅凭画面明确证据判定，不确定不算）：
				- pornographic：色情低俗内容
				- violence：暴力血腥内容
				- illegal_goods：违禁品或违法经营暗示
				- misleading：画面文字含广告法极限词/虚假宣传（如「全网最好」「第一」）
				- minor_safety：未成年人不当呈现
				- diversion：站外导流（明显二维码/联系方式招揽私域流量）
				verdict 判定：无问题=pass；仅轻微/存疑=review；明确违规=blocked。仅返回 JSON：
				{"verdict":"pass|review|blocked","findings":[{"category":"...","severity":"high|medium|low",\
				"advice":"20 字以内说明"}]}""";
	}

	/** 视频按抽帧送审：多帧是同一视频的时间采样，任一帧的明确证据都足以定级。 */
	static String videoRubric() {
		return """
				你是门店公开媒体内容安全审核员。以下多张图片是同一段门店宣传视频按时间抽出的帧，\
				门店会在公开页面播放该视频。检查帧画面是否存在以下问题（仅凭画面明确证据判定，不确定不算；\
				任一帧明确违规即整体违规）：
				- pornographic：色情低俗内容
				- violence：暴力血腥内容
				- illegal_goods：违禁品或违法经营暗示
				- misleading：画面文字含广告法极限词/虚假宣传（如「全网最好」「第一」）
				- minor_safety：未成年人不当呈现
				- diversion：站外导流（明显二维码/联系方式招揽私域流量）
				verdict 判定：无问题=pass；仅轻微/存疑=review；明确违规=blocked。仅返回 JSON：
				{"verdict":"pass|review|blocked","findings":[{"category":"...","severity":"high|medium|low",\
				"advice":"20 字以内说明"}]}""";
	}

	/** AI 生成图（文章配图/视频改编出图）送审：类目与门店媒体一致，表述面向「将发布到平台的内容」。 */
	static String generatedRubric() {
		return """
				你是 AI 生成内容安全审核员。这张图片由 AI 生成、将作为种草内容发布到社交平台，\
				检查画面是否存在以下问题（仅凭画面明确证据判定，不确定不算）：
				- pornographic：色情低俗内容
				- violence：暴力血腥内容
				- illegal_goods：违禁品或违法经营暗示
				- misleading：画面文字含广告法极限词/虚假宣传（如「全网最好」「第一」）
				- minor_safety：未成年人不当呈现
				- diversion：站外导流（明显二维码/联系方式招揽私域流量）
				verdict 判定：无问题=pass；仅轻微/存疑=review；明确违规=blocked。仅返回 JSON：
				{"verdict":"pass|review|blocked","findings":[{"category":"...","severity":"high|medium|low",\
				"advice":"20 字以内说明"}]}""";
	}

	/** AI 生成视频（视频制作产出）按抽帧送审：任一帧的明确证据都足以定级。 */
	static String generatedVideoRubric() {
		return """
				你是 AI 生成内容安全审核员。以下多张图片是同一段 AI 生成视频按时间抽出的帧，\
				该视频将作为种草内容发布到社交平台。检查帧画面是否存在以下问题（仅凭画面明确证据判定，\
				不确定不算；任一帧明确违规即整体违规）：
				- pornographic：色情低俗内容
				- violence：暴力血腥内容
				- illegal_goods：违禁品或违法经营暗示
				- misleading：画面文字含广告法极限词/虚假宣传（如「全网最好」「第一」）
				- minor_safety：未成年人不当呈现
				- diversion：站外导流（明显二维码/联系方式招揽私域流量）
				verdict 判定：无问题=pass；仅轻微/存疑=review；明确违规=blocked。仅返回 JSON：
				{"verdict":"pass|review|blocked","findings":[{"category":"...","severity":"high|medium|low",\
				"advice":"20 字以内说明"}]}""";
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
}
