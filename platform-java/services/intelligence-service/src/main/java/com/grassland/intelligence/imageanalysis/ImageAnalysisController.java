package com.grassland.intelligence.imageanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.creationcontext.GraphicTaskCreationContext;
import com.grassland.intelligence.imageanalysis.FeishuExportService.FeishuExportInput;
import com.grassland.intelligence.imageanalysis.FeishuExportService.FeishuImageInput;
import com.grassland.intelligence.imageanalysis.FeishuCredentialsRepository.FeishuCredentials;
import com.grassland.intelligence.imageanalysis.ImageAnalysisPrompts.ImageReviewInput;
import com.grassland.intelligence.imageanalysis.ImageAnalysisService.ImageAnalysisResult;
import com.grassland.intelligence.imageanalysis.ImageAnalysisService.UploadedImage;
import com.grassland.intelligence.imageanalysis.StylePreferencesService.StyleSnapshot;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 图片评价文案 9 端点（草场 intelligence Slice 6）。路径沿用 legacy
 * {@code /api/image-analysis/*}，前端零改动。
 *
 * <p>
 * 关键时序（逐字保留 legacy）：{@code /analyze} 与 {@code /step/draft} 的文本字段校验在 SSE headers
 * 之前（→400 JSON）， 图片 magic-byte/数量校验与 {@code /analyze} 扣积分在 headers
 * 之后（→{@code {type:error}} 帧仍 HTTP 200）。 {@code /analyze} 匿名→SSE
 * error「评价生成失败，请稍后重试」（镜像 legacy {@code getSessionUser(req)!.id}
 * 崩溃）；{@code /step/draft} 匿名可生成。 仅 {@code /analyze} 扣 {@code IMAGE_ANALYSIS}
 * 积分。
 */
@RestController
@RequestMapping("/api/image-analysis")
public class ImageAnalysisController {

	private static final int MAX_FILE_BYTES = ImageAnalysisService.MAX_FILE_BYTES;
	private static final String ANALYZE_FALLBACK = "评价生成失败，请稍后重试";
	private static final String DRAFT_FALLBACK = "初稿生成失败，请稍后重试";

	private final IntelligenceCallerResolver callers;
	private final ImageAnalysisService analysis;
	private final StylePreferencesService styles;
	private final FeishuExportService feishuExport;
	private final FeishuCredentialsRepository feishuCreds;
	private final GraphicTaskCreationContext creationContexts;
	private final ObjectMapper mapper = new ObjectMapper();
	private final com.grassland.intelligence.contentsafety.ContentSafetyService safety;

	public ImageAnalysisController(IntelligenceCallerResolver callers, ImageAnalysisService analysis,
			StylePreferencesService styles, FeishuExportService feishuExport, FeishuCredentialsRepository feishuCreds,
			GraphicTaskCreationContext creationContexts,
			com.grassland.intelligence.contentsafety.ContentSafetyService safety) {
		this.callers = callers;
		this.analysis = analysis;
		this.styles = styles;
		this.feishuExport = feishuExport;
		this.feishuCreds = feishuCreds;
		this.creationContexts = creationContexts;
		this.safety = safety;
	}

	// ---------------- SSE 生成端点 ----------------

	@PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ResponseEntity<Flux<DataBuffer>>> analyze(ServerWebExchange exchange) {
		return exchange.getMultipartData().flatMap(form -> Mono.defer(() -> {
			GenerationInput input = parseGenerationInput(form);
			validateMultipartShape(form, true);
			return readImages(form).flatMap(images -> input.taskModeEnabled()
					? taskBinding(exchange, input).flatMap(binding -> sseResponse(exchange,
							withSafety(exchange,
									analysis.analyzeTask(images, binding.input(), binding.binding(), exchange)
											.onErrorResume(error -> Flux.just(errorFrame(error, ANALYZE_FALLBACK))),
									binding.binding().snapshot(), binding.input().platform())))
					: sseResponse(exchange, withSafety(exchange, analyzeEvents(exchange, input.input(), images), null,
							input.input().platform())));
		}).doFinally(s -> releaseParts(form)));
	}

	@PostMapping(value = "/step/draft", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ResponseEntity<Flux<DataBuffer>>> draft(ServerWebExchange exchange) {
		return exchange.getMultipartData().flatMap(form -> Mono.defer(() -> {
			GenerationInput input = parseGenerationInput(form);
			validateMultipartShape(form, true);
			return readImages(form).flatMap(images -> input.taskModeEnabled()
					? taskBinding(exchange, input).flatMap(binding -> sseResponse(exchange,
							withSafety(exchange,
									analysis.draftTask(images, binding.input(), binding.binding(), exchange)
											.onErrorResume(error -> Flux.just(errorFrame(error, DRAFT_FALLBACK))),
									binding.binding().snapshot(), binding.input().platform())))
					: sseResponse(exchange, withSafety(exchange, draftEvents(exchange, input.input(), images), null,
							input.input().platform())));
		}).doFinally(s -> releaseParts(form)));
	}

	/** 任务书 #34 D8：图片评价文案流尾追加安全检查帧（检查文本=result 帧 data.review）。 */
	private Flux<String> withSafety(ServerWebExchange exchange, Flux<String> frames,
			com.grassland.intelligence.creationcontext.CreationContextSnapshot snapshot, String requestedPlatform) {
		return safety.appendSafetyFrame(exchange, frames,
				com.grassland.intelligence.contentsafety.ContentSafetyService.reviewExtractor(),
				snapshot == null ? requestedPlatform : snapshot.platformId(),
				com.grassland.intelligence.contentsafety.ContentSafetyService.industryFromSnapshot(snapshot),
				com.grassland.intelligence.contentsafety.ContentSafetyService.generationContext(snapshot));
	}

	private Flux<String> analyzeEvents(ServerWebExchange exchange, ImageReviewInput baseInput,
			List<UploadedImage> images) {
		// GL-P3-AI-001 尾巴清偿：多轮管线整条经执行环一次计费/留痕/退款闭环（预算闸拒绝 402 以
		// onError 抛出 → 仍按 legacy 契约转 SSE error 帧，HTTP 200 不变；匿名 → error 帧不变）。
		return callers.resolve(exchange.getRequest()).onErrorResume(e -> Mono.error(AnonymousMarker.INSTANCE))
				.flatMap(caller -> styles.styleAppendixFor(caller.accountId()).map(app -> withStyle(baseInput, app)))
				.flatMapMany(
						input -> analysis.analyzeIndependent(images, input, exchange).flatMapMany(Flux::fromIterable))
				.onErrorResume(e -> Flux.just(errorFrame(e, ANALYZE_FALLBACK)));
	}

	private Flux<String> draftEvents(ServerWebExchange exchange, ImageReviewInput baseInput,
			List<UploadedImage> images) {
		return appendixFor(exchange, baseInput).flatMapMany(in -> analysis.draft(images, in))
				.onErrorResume(e -> Flux.just(errorFrame(e, DRAFT_FALLBACK)));
	}

	// ---------------- JSON 单步端点 ----------------

	@PostMapping(value = "/step/optimize", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<Map<String, Object>> optimize(@RequestBody StepRequest body, ServerWebExchange exchange) {
		if (body.isTaskMode()) {
			return taskBinding(exchange, body).flatMap(
					binding -> analysis.optimizeTask(body.review(), binding.input(), binding.binding(), exchange))
					.map(result -> success(resultData(result, false, 0)));
		}
		return appendixFor(exchange, body.toInput()).flatMap(in -> analysis.optimize(body.review(), in))
				.map(result -> success(resultData(result, false, 0)));
	}

	@PostMapping(value = "/step/style-refine", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<Map<String, Object>> styleRefine(@RequestBody StepRequest body, ServerWebExchange exchange) {
		if (body.isTaskMode()) {
			return taskBinding(exchange, body).flatMap(
					binding -> analysis.styleRefineTask(body.review(), binding.input(), binding.binding(), exchange))
					.map(result -> success(resultData(result, false, 0)));
		}
		return appendixFor(exchange, body.toInput()).flatMap(in -> analysis.styleRefine(body.review(), in))
				.map(result -> success(resultData(result, false, 0)));
	}

	// ---------------- 风格偏好端点 ----------------

	@GetMapping("/style-preferences")
	public Mono<Map<String, Object>> getStylePreferences(ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).onErrorResume(e -> Mono.empty())
				.flatMap(caller -> styles.loadPreferences(caller.accountId())).defaultIfEmpty(List.of())
				.map(prefs -> success(Map.of("preferences", prefs)));
	}

	@PutMapping("/style-preferences")
	public Mono<Map<String, Object>> updateStylePreferences(@RequestBody StyleUpdateRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> styles.savePreferences(caller.accountId(), body.preferences()))
				.map(prefs -> success(Map.of("preferences", prefs)));
	}

	@PostMapping(value = "/style-preferences/optimize", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<Map<String, Object>> optimizeStylePreferences(@RequestBody StyleOptimizeRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> styles.optimizePreferences(body.preferences()))
				.map(prefs -> success(Map.of("preferences", prefs)));
	}

	@PostMapping(value = "/save-style-memory", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<Map<String, Object>> saveStyleMemory(@RequestBody StyleSaveRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> styles.saveFromEdits(caller.accountId(), body.original(), body.edited()))
				.map(prefs -> success(Map.of("preferences", prefs, "updatedAt", Instant.now().toString())));
	}

	// ---------------- 飞书导出 ----------------

	@PostMapping(value = "/export-feishu", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<Map<String, Object>> exportFeishu(ServerWebExchange exchange) {
		return exchange.getMultipartData().flatMap(form -> Mono.defer(() -> {
			ExportFields fields = parseExportFields(form);
			validateMultipartShape(form, false);
			return readFeishuImages(form).flatMap(this::validateFeishuImages)
					.flatMap(images -> callers.resolve(exchange.getRequest())
							.flatMap(caller -> feishuCreds.find(caller.accountId())
									.defaultIfEmpty(new FeishuCredentials(null, null, null))
									.flatMap(creds -> feishuExport.export(new FeishuExportInput(creds, fields.review(),
											fields.title(), fields.tags(), images, fields.platform(),
											fields.reviewLength(), fields.feelings(), fields.runId()))))
							.map(ImageAnalysisController::success));
		}).doFinally(s -> releaseParts(form)));
	}

	// ---------------- helpers ----------------

	/**
	 * 软 resolve：匿名→空附录；登录→注入风格附录（镜像 legacy step/optimize/style-refine 的
	 * {@code injectPreferences}）。
	 */
	private Mono<ImageReviewInput> appendixFor(ServerWebExchange exchange, ImageReviewInput baseInput) {
		return callers.resolve(exchange.getRequest()).onErrorResume(e -> Mono.empty())
				.flatMap(caller -> styles.styleAppendixFor(caller.accountId()).map(app -> withStyle(baseInput, app)))
				.defaultIfEmpty(withStyle(baseInput, ""));
	}

	private static ImageReviewInput withStyle(ImageReviewInput base, String appendix) {
		return new ImageReviewInput(base.reviewLength(), base.feelings(), base.platform(), appendix);
	}

	private Mono<ResponseEntity<Flux<DataBuffer>>> sseResponse(ServerWebExchange exchange, Flux<String> payloads) {
		HttpHeaders h = new HttpHeaders();
		h.setContentType(MediaType.TEXT_EVENT_STREAM);
		h.set("X-Accel-Buffering", "no");
		h.setCacheControl("no-cache");
		Flux<DataBuffer> body = Sse.stream(payloads, exchange.getResponse().bufferFactory());
		return Mono.just(new ResponseEntity<>(body, h, HttpStatus.OK));
	}

	private String errorFrame(Throwable error, String fallback) {
		String message = error instanceof IntelligenceException ie ? ie.getMessage() : fallback;
		try {
			return mapper.writeValueAsString(Map.of("type", "error", "error", message));
		} catch (Exception e) {
			return "{\"type\":\"error\",\"error\":\"" + fallback + "\"}";
		}
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}

	private static Map<String, Object> resultData(ImageAnalysisResult result, boolean withImageCount, int imageCount) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("review", result.review());
		if (result.title() != null) {
			data.put("title", result.title());
		}
		if (result.tags() != null) {
			data.put("tags", result.tags());
		}
		if (withImageCount) {
			data.put("imageCount", imageCount);
		}
		return data;
	}

	private GenerationInput parseGenerationInput(MultiValueMap<String, Part> form) {
		int reviewLength = parseReviewLength(field(form, "reviewLength"), 0, 15, 300);
		String feelings = optionalField(form, "feelings", 200, "感受内容不能超过 200 字");
		String platform = parsePlatform(field(form, "platform"));
		boolean taskMode = parseTaskMode(field(form, "taskMode"));
		UUID contextSnapshotId = parseContextSnapshotId(field(form, "contextSnapshotId"));
		validateTaskBinding(taskMode, contextSnapshotId);
		return new GenerationInput(new ImageReviewInput(reviewLength, feelings, platform, null), taskMode,
				contextSnapshotId);
	}

	private ExportFields parseExportFields(MultiValueMap<String, Part> form) {
		String review = requireField(form, "review").trim();
		String title = optionalField(form, "title", 200, null);
		String tagsRaw = optionalFieldRaw(form, "tags");
		List<String> tags = parseTagsJson(tagsRaw);
		String runId = optionalFieldRaw(form, "runId");
		String platform = parsePlatform(field(form, "platform"));
		Integer reviewLength = parseOptionalInt(field(form, "reviewLength"), 15, 300);
		String feelings = optionalField(form, "feelings", 200, null);
		return new ExportFields(review, title, tags, runId, platform, reviewLength, feelings);
	}

	private static String field(MultiValueMap<String, Part> form, String name) {
		Part part = form.getFirst(name);
		return part instanceof FormFieldPart formField ? formField.value() : null;
	}

	private static String optionalFieldRaw(MultiValueMap<String, Part> form, String name) {
		String value = field(form, name);
		return value == null || value.isBlank() ? null : value;
	}

	private static String optionalField(MultiValueMap<String, Part> form, String name, int max, String maxMessage) {
		String value = field(form, name);
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		if (maxMessage != null && trimmed.length() > max) {
			throw new IntelligenceException(400, maxMessage);
		}
		return trimmed;
	}

	private static String requireField(MultiValueMap<String, Part> form, String name) {
		String value = field(form, name);
		if (value == null || value.trim().isEmpty()) {
			throw new IntelligenceException(400, "评价内容不能为空");
		}
		return value;
	}

	private static String parsePlatform(String raw) {
		if (raw == null || raw.isBlank()) {
			return "taobao";
		}
		String value = raw.trim();
		if (!"taobao".equals(value) && !"dianping".equals(value)) {
			throw new IntelligenceException(400, "评价平台无效");
		}
		return value;
	}

	private static int parseReviewLength(String raw, int defaultValue, int min, int max) {
		if (raw == null || raw.isBlank()) {
			return defaultValue;
		}
		int value = parseInt(raw);
		if (value != 0 && (value < min || value > max)) {
			throw new IntelligenceException(400, "评价字数需在 " + min + "-" + max + " 之间，或填 0 不限制");
		}
		return value;
	}

	private static Integer parseOptionalInt(String raw, int min, int max) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		int value = parseInt(raw);
		if (value < min || value > max) {
			throw new IntelligenceException(400, "评价字数需在 " + min + "-" + max + " 之间");
		}
		return value;
	}

	private static int parseInt(String raw) {
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			throw new IntelligenceException(400, "评价字数必须是整数");
		}
	}

	private static List<String> parseTagsJson(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return new ObjectMapper().readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {
			});
		} catch (Exception e) {
			return null;
		}
	}

	private Mono<List<UploadedImage>> readImages(MultiValueMap<String, Part> form) {
		List<Part> parts = form.getOrDefault("images", List.of());
		return Flux.fromIterable(parts).concatMap(this::readUploadImage).collectList();
	}

	private Mono<List<FeishuImageInput>> readFeishuImages(MultiValueMap<String, Part> form) {
		List<Part> parts = form.getOrDefault("images", List.of());
		return Flux.fromIterable(parts).concatMap(this::readFeishuImage).collectList();
	}

	/**
	 * multipart 结构二次门禁（chunked 请求无 Content-Length 时仍生效）：最多 6 图片、8 文本字段、14 parts；
	 * 只接受声明过的字段，文本字段由 Spring max-in-memory 32KB + 此处 16KB 双层限制。
	 */
	private static void validateMultipartShape(MultiValueMap<String, Part> form, boolean generation) {
		Set<String> allowed = generation
				? Set.of("images", "reviewLength", "feelings", "platform", "taskMode", "contextSnapshotId")
				: Set.of("images", "review", "title", "tags", "runId", "platform", "reviewLength", "feelings");
		int totalParts = form.values().stream().mapToInt(List::size).sum();
		int imageCount = form.getOrDefault("images", List.of()).size();
		int fieldCount = 0;
		for (Map.Entry<String, List<Part>> entry : form.entrySet()) {
			if (!allowed.contains(entry.getKey())) {
				throw new IntelligenceException(400, "图片上传失败，请检查文件后重试");
			}
			for (Part part : entry.getValue()) {
				if (!(part instanceof FilePart)) {
					fieldCount++;
					if (part instanceof FormFieldPart field
							&& field.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024) {
						throw new IntelligenceException(400, "图片上传失败，请检查文件后重试");
					}
				}
			}
		}
		if (imageCount > 6) {
			throw new IntelligenceException(400, "最多上传 6 张图片");
		}
		if (fieldCount > 8 || totalParts > 14) {
			throw new IntelligenceException(400, "图片上传失败，请检查文件后重试");
		}
	}

	private Mono<TaskBinding> taskBinding(ServerWebExchange exchange, TaskInput input) {
		return callers.requireUser(exchange.getRequest()).flatMap(
				caller -> creationContexts.bind(input.contextSnapshotId(), caller.accountId(), input.platform()))
				.flatMap(binding -> styles.styleAppendixFor(binding.snapshot().accountId())
						.map(appendix -> new TaskBinding(binding, withStyle(input.toInput(), appendix))));
	}

	private static boolean parseTaskMode(String raw) {
		if (raw == null || raw.isBlank() || "false".equalsIgnoreCase(raw.trim())) {
			return false;
		}
		if ("true".equalsIgnoreCase(raw.trim())) {
			return true;
		}
		throw new IntelligenceException(400, "任务创作模式参数不合法");
	}

	private static UUID parseContextSnapshotId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException error) {
			throw new IntelligenceException(400, "创作上下文快照标识不合法");
		}
	}

	private static void validateTaskBinding(boolean taskMode, UUID contextSnapshotId) {
		if (taskMode && contextSnapshotId == null) {
			throw new IntelligenceException(400, "任务创作必须绑定创作上下文快照");
		}
		if (!taskMode && contextSnapshotId != null) {
			throw new IntelligenceException(400, "独立创作不能绑定任务创作上下文");
		}
	}

	/** export-feishu 复用 generation 的 MIME/magic/数量校验，但保持 0 张图片合法。 */
	private Mono<List<FeishuImageInput>> validateFeishuImages(List<FeishuImageInput> images) {
		if (images.isEmpty()) {
			return Mono.just(images);
		}
		List<UploadedImage> validation = images.stream()
				.map(i -> new UploadedImage(i.mimeType(), i.originalName(), i.bytes())).toList();
		return Mono.fromCallable(() -> {
			analysis.validateAndEncode(validation);
			return images;
		});
	}

	private Mono<UploadedImage> readUploadImage(Part part) {
		if (!(part instanceof FilePart file)) {
			return Mono.error(new IntelligenceException(400, "仅支持上传 images 字段的图片文件"));
		}
		return readBytes(file).map(bytes -> new UploadedImage(
				file.headers().getContentType() == null ? null : file.headers().getContentType().toString(),
				file.filename(), bytes));
	}

	private Mono<FeishuImageInput> readFeishuImage(Part part) {
		if (!(part instanceof FilePart file)) {
			return Mono.error(new IntelligenceException(400, "仅支持上传 images 字段的图片文件"));
		}
		return readBytes(file).map(bytes -> new FeishuImageInput(bytes,
				file.headers().getContentType() == null ? null : file.headers().getContentType().toString(),
				file.filename()));
	}

	private Mono<byte[]> readBytes(FilePart file) {
		return DataBufferUtils.join(file.content(), MAX_FILE_BYTES + 1).map(buffer -> {
			try {
				byte[] bytes = new byte[buffer.readableByteCount()];
				buffer.read(bytes);
				return bytes;
			} finally {
				DataBufferUtils.release(buffer);
			}
		});
	}

	private static void releaseParts(MultiValueMap<String, Part> form) {
		Flux.fromIterable(form.values()).flatMapIterable(p -> p).flatMap(Part::delete).onErrorComplete().subscribe();
	}

	/** 匿名调用哨兵（{@code /analyze} 匿名→generic error frame，非 401）。 */
	private static final class AnonymousMarker extends RuntimeException {
		static final AnonymousMarker INSTANCE = new AnonymousMarker();
	}

	/** 飞书导出字段（multipart 解析）。 */
	private record ExportFields(String review, String title, List<String> tags, String runId, String platform,
			Integer reviewLength, String feelings) {
	}

	/** {@code /step/optimize} 与 {@code /step/style-refine} 请求体。 */
	private interface TaskInput {
		ImageReviewInput toInput();
		boolean taskModeEnabled();
		UUID contextSnapshotId();

		default boolean isTaskMode() {
			return taskModeEnabled();
		}

		default String platform() {
			return toInput().platform();
		}
	}

	private record GenerationInput(ImageReviewInput input, boolean taskMode,
			UUID contextSnapshotId) implements TaskInput {
		@Override
		public ImageReviewInput toInput() {
			return input;
		}

		@Override
		public boolean taskModeEnabled() {
			return taskMode;
		}

		@Override
		public UUID contextSnapshotId() {
			return contextSnapshotId;
		}
	}

	private record TaskBinding(GraphicTaskCreationContext.Binding binding, ImageReviewInput input) {
	}

	public record StepRequest(String review, String title, List<String> tags, Integer reviewLength, String feelings,
			String platform, Boolean taskMode, UUID contextSnapshotId) implements TaskInput {
		public StepRequest {
			review = review == null ? "" : review.trim();
			if (review.isEmpty()) {
				throw new IllegalArgumentException("评价内容不能为空");
			}
			title = title == null || title.isBlank() ? null : title.trim();
			if (title != null && title.length() > 200) {
				throw new IllegalArgumentException("评价标题过长");
			}
			feelings = feelings == null || feelings.isBlank() ? null : feelings.trim();
			if (feelings != null && feelings.length() > 200) {
				throw new IllegalArgumentException("感受内容不能超过 200 字");
			}
			int length = reviewLength == null ? 0 : reviewLength;
			if (length != 0 && (length < 15 || length > 300)) {
				throw new IllegalArgumentException("评价字数需在 15-300 之间，或填 0 不限制");
			}
			platform = platform == null || platform.isBlank() ? "taobao" : platform.trim();
			if (!"taobao".equals(platform) && !"dianping".equals(platform)) {
				throw new IllegalArgumentException("评价平台无效");
			}
			reviewLength = length;
			validateTaskBinding(Boolean.TRUE.equals(taskMode), contextSnapshotId);
		}

		public ImageReviewInput toInput() {
			return new ImageReviewInput(reviewLength, feelings, platform, null);
		}

		@Override
		public boolean taskModeEnabled() {
			return Boolean.TRUE.equals(taskMode);
		}
	}

	public record StyleUpdateRequest(List<String> preferences) {
		public StyleUpdateRequest {
			preferences = preferences == null ? List.of() : sanitize(preferences);
			if (preferences.size() > 100) {
				throw new IllegalArgumentException("风格偏好最多 100 条");
			}
		}
	}

	public record StyleOptimizeRequest(List<String> preferences) {
		public StyleOptimizeRequest {
			preferences = preferences == null ? List.of() : sanitize(preferences);
			if (preferences.isEmpty()) {
				throw new IllegalArgumentException("至少需要 1 条风格偏好");
			}
		}
	}

	public record StyleSaveRequest(StyleSnapshot original, StyleSnapshot edited) {
		public StyleSaveRequest {
			if (original == null || original.review() == null || original.review().trim().isEmpty()) {
				throw new IllegalArgumentException("缺少原始评价内容");
			}
			if (edited == null || edited.review() == null || edited.review().trim().isEmpty()) {
				throw new IllegalArgumentException("缺少修改后评价内容");
			}
		}
	}

	private static List<String> sanitize(List<String> preferences) {
		List<String> out = new ArrayList<>();
		for (String value : preferences) {
			if (value != null) {
				String trimmed = value.trim();
				if (!trimmed.isEmpty()) {
					out.add(trimmed);
				}
			}
		}
		return List.copyOf(out);
	}
}
