package com.grassland.intelligence.imageanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.creationcontext.GraphicTaskCreationContext;
import com.grassland.intelligence.imageanalysis.ImageAnalysisPrompts.ImageReviewInput;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 图片评价生成编排（草场 intelligence Slice 6）。移植 legacy
 * {@code image-analysis-dispatch.service.ts} +
 * {@code qwen-provider.analyzeImages/draftStep/optimizeStep/styleRefineStep}。
 *
 * <p>
 * {@link #analyze} 为多轮 pipeline（draft→optimize→可选 style-refine），每轮
 * 经统一路由的非流式完成（解析 JSON 结果），发
 * {@code {type:progress}} 帧；{@link #draft}
 * 单轮；{@link #optimize}/{@link #styleRefine} 单轮 JSON。 图片校验（MIME 白名单 + magic byte
 * + 数量 + 单张 5MB）镜像 legacy {@code uploadedImageListSchema}， 在 Flux 订阅时执行（SSE
 * headers 已提交→失败发 {@code {type:error}} 帧）。
 *
 * <p>
 * 与 legacy 的已知偏差：{@code completeText} 仅回 content，不暴露上游 {@code id}，故结果不带
 * {@code runId}（legacy 可选字段）。
 */
@Component
public class ImageAnalysisService {

	static final Duration GENERATION_TIMEOUT = Duration.ofSeconds(180); // 镜像 legacy VIDEO_ANALYSIS_API_TIMEOUT_MS
	static final int OPTIMIZATION_ROUNDS = 1;
	static final int MAX_IMAGES = 6;
	static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
	static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/webp");

	private final RoutedTextCompletionService routed;
	private final FrozenTextExecutionService frozenText;
	private final ObjectMapper mapper = new ObjectMapper();

	public ImageAnalysisService(RoutedTextCompletionService routed, FrozenTextExecutionService frozenText) {
		this.routed = routed;
		this.frozenText = frozenText;
	}

	/**
	 * 独立模式多轮评价（GL-P3-AI-001 尾巴清偿）：整条 draft→optimize→style-refine 管线经
	 * {@link FrozenTextExecutionService#executeIndependentPipeline} 一次计费/留痕/退款闭环——
	 * 轮次消息由上一轮 review 派生（运行时组装），usage 按轮累计一次结算。返回完整帧序列
	 * （prepare/progress/complete/result），扣减失败（402）以 onError 抛出由 controller 转 error
	 * 帧， 与 legacy「headers 后扣费、失败走 SSE error 帧」契约一致。
	 */
	public Mono<List<String>> analyzeIndependent(List<UploadedImage> images, ImageReviewInput input,
			ServerWebExchange exchange) {
		return Mono.defer(() -> {
			List<String> dataUrls = validateAndEncode(images);
			boolean hasStyle = input.stylePreferences() != null && !input.stylePreferences().isBlank();
			int totalRounds = 1 + OPTIMIZATION_ROUNDS + (hasStyle ? 1 : 0);
			int estimatedInput = Math.addExact(Math.multiplyExact(dataUrls.size(), 1024 * totalRounds),
					Math.multiplyExact(totalRounds, 4096));
			int estimatedOutput = Math.multiplyExact(2048, totalRounds);
			return frozenText
					.executeIndependentPipeline(exchange,
							com.grassland.intelligence.credits.CreditFeature.IMAGE_ANALYSIS, estimatedInput,
							estimatedOutput, GENERATION_TIMEOUT, 2048,
							stages -> runIndependentPipeline(stages, dataUrls, input, totalRounds, images.size()))
					.map(trace -> trace.value());
		}).doOnError(error -> LOGGER.warn("独立图片评价管线失败", error));
	}

	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ImageAnalysisService.class);

	private Mono<List<String>> runIndependentPipeline(FrozenTextExecutionService.IndependentStageExecutor stages,
			List<String> dataUrls, ImageReviewInput input, int totalRounds, int imageCount) {
		boolean hasStyle = input.stylePreferences() != null && !input.stylePreferences().isBlank();
		ImageAnalysisResult[] latest = new ImageAnalysisResult[]{null};
		List<String> frames = new ArrayList<>();
		frames.add(prepareFrame(totalRounds, imageCount));
		return Flux.range(0, totalRounds).concatMap(i -> {
			int attempt = i + 1;
			String stage = stageType(i, hasStyle);
			String prevReview = latest[0] == null ? "" : latest[0].review();
			frames.add(progressFrame(stage, attempt, totalRounds, describeStage(stage, attempt, totalRounds)));
			return Mono.defer(() -> Mono.just(stageMessages(stage, dataUrls, input, prevReview, attempt)))
					.flatMap(stages::completeRound).map(completion -> parseResult(completion.content()))
					.doOnNext(result -> latest[0] = result).then();
		}).then(Mono.fromSupplier(() -> decoratePipelineResult(frames, latest[0], totalRounds, imageCount)));
	}

	private List<String> decoratePipelineResult(List<String> frames, ImageAnalysisResult result, int totalRounds,
			int imageCount) {
		frames.add(progressFrame("complete", totalRounds, totalRounds, "文案生成完成，正在返回结果"));
		frames.add(resultFrame(result, imageCount));
		return frames;
	}

	/** 管线单轮消息：图片 parts + 阶段 prompt（图片 parts + 阶段 prompt）。 */
	private static List<ChatMessage> stageMessages(String stage, List<String> dataUrls, ImageReviewInput input,
			String prevReview, int attempt) {
		String prompt = switch (stage) {
			case "draft" -> ImageAnalysisPrompts.buildImageReviewPrompt(input);
			case "optimize" -> ImageAnalysisPrompts.buildImageReviewOptimizationPrompt(input, prevReview, attempt);
			case "style-refine" -> ImageAnalysisPrompts.buildImageReviewStyleRefinementPrompt(input, prevReview);
			default -> throw new IllegalStateException("unknown stage: " + stage);
		};
		List<ContentPart> parts = new ArrayList<>();
		for (String url : dataUrls) {
			parts.add(ContentPart.image(url));
		}
		parts.add(ContentPart.text(prompt));
		return List.of(ChatMessage.user(parts));
	}

	/** 单轮初稿（multipart 图片→SSE）。镜像 legacy {@code draftStep}；模型来源经统一路由（BYOK 开关/平台控制面）。 */
	public Flux<String> draft(ServerWebExchange exchange, List<UploadedImage> images, ImageReviewInput input) {
		return Flux.defer(() -> {
			List<String> dataUrls = validateAndEncode(images);
			ImageAnalysisResult[] latest = new ImageAnalysisResult[]{null};
			Mono<Void> call = completeMultimodal(exchange, dataUrls,
					ImageAnalysisPrompts.buildImageReviewPrompt(input), "图片评价生成")
					.doOnNext(result -> latest[0] = result).then();
			return Flux.concat(Mono.just(prepareFrame(1, images.size())),
					Mono.just(progressFrame("draft", 1, 1, describeStage("draft", 1, 1))),
					call.thenMany(Flux.<String>empty()),
					Mono.fromSupplier(() -> resultFrame(latest[0], images.size())));
		});
	}

	/** 单轮润色（JSON 请求，previousReview 为待优化文案）。 */
	public Mono<ImageAnalysisResult> optimize(ServerWebExchange exchange, String previousReview,
			ImageReviewInput input) {
		return completeText(exchange,
				ImageAnalysisPrompts.buildImageReviewOptimizationPrompt(input, previousReview, 1), "图片评价润色");
	}

	/** 单轮风格优化（JSON 请求，注入用户风格偏好）。 */
	public Mono<ImageAnalysisResult> styleRefine(ServerWebExchange exchange, String previousReview,
			ImageReviewInput input) {
		return completeText(exchange,
				ImageAnalysisPrompts.buildImageReviewStyleRefinementPrompt(input, previousReview), "图片评价风格优化");
	}

	public Flux<String> draftTask(List<UploadedImage> images, ImageReviewInput input,
			GraphicTaskCreationContext.Binding binding, ServerWebExchange exchange) {
		return Flux.defer(() -> {
			List<String> dataUrls = validateAndEncode(images);
			ImageAnalysisResult[] latest = new ImageAnalysisResult[]{null};
			Mono<Void> call = completeFrozen(dataUrls, ImageAnalysisPrompts.buildImageReviewPrompt(input), binding,
					exchange).doOnNext(result -> latest[0] = result).then();
			return Flux.concat(Mono.just(prepareFrame(1, images.size())),
					Mono.just(progressFrame("draft", 1, 1, describeStage("draft", 1, 1))), call.thenMany(Flux.empty()),
					Mono.fromSupplier(() -> resultFrame(latest[0], images.size())));
		});
	}

	public Flux<String> analyzeTask(List<UploadedImage> images, ImageReviewInput input,
			GraphicTaskCreationContext.Binding binding, ServerWebExchange exchange) {
		return Flux.defer(() -> {
			List<String> dataUrls = validateAndEncode(images);
			boolean hasStyle = input.stylePreferences() != null && !input.stylePreferences().isBlank();
			int totalRounds = 1 + OPTIMIZATION_ROUNDS + (hasStyle ? 1 : 0);
			ImageAnalysisResult[] latest = new ImageAnalysisResult[]{null};
			Flux<String> stages = Flux.range(0, totalRounds).concatMap(index -> {
				int attempt = index + 1;
				String stage = stageType(index, hasStyle);
				String previous = latest[0] == null ? "" : latest[0].review();
				Mono<Void> call = runTaskStage(stage, dataUrls, input, previous, attempt, binding, exchange)
						.doOnNext(result -> latest[0] = result).then();
				return Flux.concat(
						Mono.just(
								progressFrame(stage, attempt, totalRounds, describeStage(stage, attempt, totalRounds))),
						call.thenMany(Flux.empty()));
			});
			return Flux.concat(Mono.just(prepareFrame(totalRounds, images.size())), stages,
					Mono.just(progressFrame("complete", totalRounds, totalRounds, "文案生成完成，正在返回结果")),
					Mono.fromSupplier(() -> resultFrame(latest[0], images.size())));
		});
	}

	public Mono<ImageAnalysisResult> optimizeTask(String previousReview, ImageReviewInput input,
			GraphicTaskCreationContext.Binding binding, ServerWebExchange exchange) {
		return completeFrozen(List.of(),
				ImageAnalysisPrompts.buildImageReviewOptimizationPrompt(input, previousReview, 1), binding, exchange);
	}

	public Mono<ImageAnalysisResult> styleRefineTask(String previousReview, ImageReviewInput input,
			GraphicTaskCreationContext.Binding binding, ServerWebExchange exchange) {
		return completeFrozen(List.of(),
				ImageAnalysisPrompts.buildImageReviewStyleRefinementPrompt(input, previousReview), binding, exchange);
	}

	private Mono<ImageAnalysisResult> runTaskStage(String stage, List<String> dataUrls, ImageReviewInput input,
			String previousReview, int attempt, GraphicTaskCreationContext.Binding binding,
			ServerWebExchange exchange) {
		String prompt = switch (stage) {
			case "draft" -> ImageAnalysisPrompts.buildImageReviewPrompt(input);
			case "optimize" -> ImageAnalysisPrompts.buildImageReviewOptimizationPrompt(input, previousReview, attempt);
			case "style-refine" -> ImageAnalysisPrompts.buildImageReviewStyleRefinementPrompt(input, previousReview);
			default -> throw new IllegalStateException("unknown stage: " + stage);
		};
		return completeFrozen(dataUrls, prompt, binding, exchange);
	}

	private static String stageType(int index, boolean hasStyle) {
		if (index == 0) {
			return "draft";
		}
		if (hasStyle && index == 1 + OPTIMIZATION_ROUNDS) {
			return "style-refine";
		}
		return "optimize";
	}

	private Mono<ImageAnalysisResult> completeMultimodal(ServerWebExchange exchange, List<String> dataUrls,
			String prompt, String label) {
		List<ContentPart> parts = new ArrayList<>();
		for (String url : dataUrls) {
			parts.add(ContentPart.image(url));
		}
		parts.add(ContentPart.text(prompt));
		return routed.complete(exchange, List.of(ChatMessage.user(parts)), 2048, GENERATION_TIMEOUT,
				label + "失败，请稍后重试").map(r -> parseResult(r.content()));
	}

	private Mono<ImageAnalysisResult> completeText(ServerWebExchange exchange, String prompt, String label) {
		return routed.complete(exchange, List.of(ChatMessage.user(prompt)), 2048, GENERATION_TIMEOUT,
				label + "失败，请稍后重试").map(r -> parseResult(r.content()));
	}

	private Mono<ImageAnalysisResult> completeFrozen(List<String> dataUrls, String prompt,
			GraphicTaskCreationContext.Binding binding, ServerWebExchange exchange) {
		List<ContentPart> parts = new ArrayList<>();
		dataUrls.forEach(url -> parts.add(ContentPart.image(url)));
		parts.add(ContentPart.text(prompt));
		return frozenText.execute(exchange, binding.snapshot().id(),
				List.of(binding.promptContext(), ChatMessage.user(parts)), 2048,
				com.grassland.intelligence.credits.CreditFeature.IMAGE_ANALYSIS,
				completion -> parseResult(completion.content()));
	}

	/**
	 * 镜像 legacy {@code normalizeImageAnalysisResult} + {@code parseJsonContent}：剥
	 * code fence→解析→校验 review 非空。
	 */
	ImageAnalysisResult parseResult(String content) {
		String stripped = stripCodeFence(content == null ? "" : content).trim();
		JsonNode node;
		try {
			node = mapper.readTree(stripped);
		} catch (Exception e) {
			throw new IntelligenceException(502, "图片评价生成服务返回了无法解析的内容");
		}
		if (!node.isObject()) {
			throw new IntelligenceException(502, "图片评价生成服务返回了无效数据");
		}
		String review = optionalText(node.get("review"));
		if (review == null) {
			throw new IntelligenceException(502, "图片评价生成服务返回了空结果");
		}
		String title = optionalText(node.get("title"));
		List<String> tags = null;
		JsonNode tagsNode = node.get("tags");
		if (tagsNode != null && tagsNode.isArray()) {
			List<String> list = new ArrayList<>();
			for (JsonNode t : tagsNode) {
				if (t.isTextual()) {
					String text = t.asText().trim();
					if (!text.isEmpty()) {
						list.add(text);
					}
				}
			}
			if (!list.isEmpty()) {
				tags = List.copyOf(list);
			}
		}
		return new ImageAnalysisResult(review, title, tags);
	}

	private static String optionalText(JsonNode node) {
		if (node == null || node.isNull() || !node.isTextual()) {
			return null;
		}
		String text = node.asText().trim();
		return text.isEmpty() ? null : text;
	}

	private static String stripCodeFence(String text) {
		int start = text.indexOf("```");
		if (start < 0) {
			return text;
		}
		int newline = text.indexOf('\n', start);
		int contentStart = newline < 0 ? start + 3 : newline + 1;
		int end = text.lastIndexOf("```");
		if (end <= contentStart) {
			return text;
		}
		return text.substring(contentStart, end);
	}

	/**
	 * 镜像 legacy {@code uploadedImageListSchema}：数量 1-6、MIME 白名单、magic byte、单张
	 * 5MB、文件名非空。
	 */
	List<String> validateAndEncode(List<UploadedImage> images) {
		if (images == null || images.isEmpty()) {
			throw new IntelligenceException(400, "请至少上传 1 张图片");
		}
		if (images.size() > MAX_IMAGES) {
			throw new IntelligenceException(400, "最多上传 6 张图片");
		}
		List<String> dataUrls = new ArrayList<>(images.size());
		for (UploadedImage image : images) {
			String name = image.originalName() == null ? "" : image.originalName().trim();
			if (name.isEmpty()) {
				throw new IntelligenceException(400, "缺少图片文件名");
			}
			String mime = image.mimeType() == null ? "" : image.mimeType().trim();
			if (!ALLOWED_MIME.contains(mime)) {
				throw new IntelligenceException(400, "仅支持 JPG、PNG、WebP 图片");
			}
			byte[] bytes = image.bytes();
			if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
				throw new IntelligenceException(400, "单张图片不能超过 5 MB");
			}
			if (!matchesSignature(mime, bytes)) {
				throw new IntelligenceException(400, "图片文件内容与类型不匹配");
			}
			dataUrls.add("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
		}
		return List.copyOf(dataUrls);
	}

	private static boolean matchesSignature(String mime, byte[] bytes) {
		return switch (mime) {
			case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
					&& (bytes[2] & 0xff) == 0xff;
			case "image/png" -> bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && (bytes[1] & 0xff) == 0x50
					&& (bytes[2] & 0xff) == 0x4e && (bytes[3] & 0xff) == 0x47 && (bytes[4] & 0xff) == 0x0d
					&& (bytes[5] & 0xff) == 0x0a && (bytes[6] & 0xff) == 0x1a && (bytes[7] & 0xff) == 0x0a;
			case "image/webp" ->
				bytes.length >= 12 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 12).equals("WEBP");
			default -> false;
		};
	}

	private static String ascii(byte[] bytes, int from, int to) {
		return new String(bytes, from, to - from, StandardCharsets.US_ASCII);
	}

	private static String describeStage(String stage, int attempt, int totalRounds) {
		return switch (stage) {
			case "draft" -> "正在分析图片并生成初稿（第 " + attempt + " / " + totalRounds + " 步）";
			case "optimize" -> "正在润色文案口吻（第 " + attempt + " / " + totalRounds + " 步）";
			default -> "正在根据个人风格偏好优化文案（第 " + attempt + " / " + totalRounds + " 步）";
		};
	}

	private String prepareFrame(int totalRounds, int imageCount) {
		Map<String, Object> event = baseProgress("prepare", null, totalRounds);
		event.put("message", "已接收 " + imageCount + " 张图片，准备开始生成");
		event.put("startedAt", Instant.now().toString());
		return frame(event);
	}

	private String progressFrame(String stage, Integer attempt, int totalRounds, String message) {
		Map<String, Object> event = baseProgress(stage, attempt, totalRounds);
		event.put("message", message);
		event.put("startedAt", Instant.now().toString());
		return frame(event);
	}

	private static Map<String, Object> baseProgress(String stage, Integer attempt, int totalRounds) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "progress");
		event.put("stage", stage);
		if (attempt != null) {
			event.put("attempt", attempt);
		}
		event.put("totalAttempts", totalRounds);
		return event;
	}

	private String resultFrame(ImageAnalysisResult result, int imageCount) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("review", result.review());
		if (result.title() != null) {
			data.put("title", result.title());
		}
		if (result.tags() != null) {
			data.put("tags", result.tags());
		}
		data.put("imageCount", imageCount);
		return frame(Map.of("type", "result", "data", data));
	}

	private String frame(Object event) {
		try {
			return mapper.writeValueAsString(event);
		} catch (Exception e) {
			return "{\"type\":\"error\",\"error\":\"评价生成失败，请稍后重试\"}";
		}
	}

	/** 上传图片（multipart 解析后传入）。 */
	public record UploadedImage(String mimeType, String originalName, byte[] bytes) {
	}

	/** 图片评价生成结果（镜像 legacy {@code ImageAnalysisResult}；不含 runId）。 */
	public record ImageAnalysisResult(String review, String title, List<String> tags) {
	}
}
