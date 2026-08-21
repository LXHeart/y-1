package com.grassland.intelligence.douyin;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.mediaplatform.VideoAnalysisPrompts;
import com.grassland.intelligence.mediaplatform.VideoAnalysisResultNormalizer;
import com.grassland.intelligence.mediaplatform.VideoRecreationResultNormalizer;
import com.grassland.intelligence.mediaplatform.PlatformMediaService;
import com.grassland.intelligence.mediaplatform.VideoSegmentAnalysisService;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.videorecreation.TaskVideoAnalysisService;
import com.grassland.intelligence.videorecreation.VideoRecreationTaskRequest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Douyin 视频分析编排（草场 GL-P3-MEDIA-001）。移植 legacy
 * {@code douyin-video-analysis.service.ts} 的
 * {@code analyzeDouyinVideoByProxyUrl} 短视频分支。
 *
 * <p>
 * <b>Java 分析路径</b>：
 * <ul>
 * <li>不超过单段阈值的视频通过公开 Java 代理地址直接提交 Qwen。</li>
 * <li>长视频由 Java 下载并以 30 秒片段提交 Qwen，结果按时间顺序合并。</li>
 * <li>整次分析经 {@link FrozenTextExecutionService} 单环执行（GL-P3-AI-001 尾巴清偿）：
 * 一次计费/留痕/失败退款，分段场景合并为一次批量 AI run。</li>
 * </ul>
 *
 * <p>
 * 提示词与归一和 Bilibili Java 路径共用
 * {@link VideoAnalysisPrompts}/{@link VideoAnalysisResultNormalizer}
 * （douyin/bilibili 使用同一 prompt 和归一规则）。provider 解析经执行环控制面 （个人 BYOK &gt; 组织 BYOK
 * &gt; 平台回落，与任务模式同机器）。
 */
@Service
public class DouyinAnalysisService {

	/** legacy {@code maxAnalysisDurationSeconds}：超过 10 分钟拒绝（422）。 */
	static final int MAX_ANALYSIS_DURATION_SECONDS = 10 * 60;

	private static final Pattern PROXY_TOKEN_PATH = Pattern.compile("^/api/douyin/proxy/([^/]+)$");
	private static final String INVALID_PROXY_URL = "视频代理地址无效";
	private static final int ANALYSIS_MAX_TOKENS = 4096;

	private final DouyinProxyToken tokenCodec;
	private final String provider;
	private final Duration timeout;
	private final int maxSingleSegmentSeconds;
	private final String publicBackendOrigin;
	private final PlatformMediaService media;
	private final VideoSegmentAnalysisService segmented;
	private final TaskVideoAnalysisService taskAnalysis;
	private final FrozenTextExecutionService frozenText;

	public DouyinAnalysisService(DouyinProxyToken tokenCodec, Environment environment, PlatformMediaService media,
			VideoSegmentAnalysisService segmented, TaskVideoAnalysisService taskAnalysis,
			FrozenTextExecutionService frozenText) {
		this.tokenCodec = tokenCodec;
		this.provider = environment.getProperty("ai.douyin-analysis.provider", "qwen");
		long timeoutMs = environment.getProperty("ai.douyin-analysis.timeout-ms", Long.class, 180_000L);
		this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
		this.maxSingleSegmentSeconds = environment.getProperty("ai.douyin-analysis.max-single-segment-seconds",
				Integer.class, 60);
		this.publicBackendOrigin = environment.getProperty("app.public-backend-origin", "");
		this.media = media;
		this.segmented = segmented;
		this.taskAnalysis = taskAnalysis;
		this.frozenText = frozenText;
	}

	/** content 提取（live 端点 {@code POST /api/douyin/analyze-video}）。 */
	public Mono<DouyinAnalysisOutcome> analyze(String proxyVideoUrl, String accountId, ServerWebExchange exchange) {
		String token = extractToken(proxyVideoUrl);
		DouyinMediaTarget target = tokenCodec.parse(token);
		long duration = assertAnalysisDuration(target.durationSeconds());

		if (!"qwen".equalsIgnoreCase(provider) || publicBackendOrigin.isBlank()) {
			throw new IntelligenceException(503, "Java 视频分析 provider 或 PUBLIC_BACKEND_ORIGIN 未配置");
		}
		return analyzeTarget(token, target, duration, exchange);
	}

	public Mono<DouyinAnalysisOutcome> analyzeTask(String proxyVideoUrl, String accountId,
			VideoRecreationTaskRequest task, ServerWebExchange exchange) {
		String token = extractToken(proxyVideoUrl);
		DouyinMediaTarget target = tokenCodec.parse(token);
		long duration = assertAnalysisDuration(target.durationSeconds());
		if (!"qwen".equalsIgnoreCase(provider) || publicBackendOrigin.isBlank()) {
			throw new IntelligenceException(503, "Java 视频分析 provider 或 PUBLIC_BACKEND_ORIGIN 未配置");
		}
		if (duration <= maxSingleSegmentSeconds) {
			return taskAnalysis.analyzeShort(buildPublicProxyUrl(token), accountId, task, exchange)
					.map(DouyinAnalysisOutcome::new);
		}
		return media.prepareDouyinVideo(target)
				.flatMap(sourceId -> media.createClips(sourceId, duration, 30)
						.flatMap(ids -> taskAnalysis.analyzeSegments("douyin", ids, accountId, task, exchange)
								.map(DouyinAnalysisOutcome::new).doFinally(signal -> ids.forEach(media::remove)))
						.doFinally(signal -> media.remove(sourceId)));
	}

	/**
	 * 复刻分镜场景分析（{@code POST /api/douyin/analyze-video} body
	 * {@code mode:"recreation"}）。与 Bilibili 路径共用提示词与归一。
	 */
	public Mono<DouyinAnalysisOutcome> analyzeForRecreation(String proxyVideoUrl, String accountId,
			ServerWebExchange exchange) {
		String token = extractToken(proxyVideoUrl);
		DouyinMediaTarget target = tokenCodec.parse(token);
		long duration = assertAnalysisDuration(target.durationSeconds());

		if (!"qwen".equalsIgnoreCase(provider) || publicBackendOrigin.isBlank()) {
			throw new IntelligenceException(503, "Java 视频分析 provider 或 PUBLIC_BACKEND_ORIGIN 未配置");
		}
		if (duration > maxSingleSegmentSeconds) {
			throw new IntelligenceException(422, "复刻分析暂不支持分段视频");
		}
		List<ContentPart> parts = List.of(ContentPart.video(buildPublicProxyUrl(token)),
				ContentPart.text(VideoAnalysisPrompts.recreation()));
		return frozenText
				.executeIndependent(exchange, List.of(ChatMessage.user(parts)), ANALYSIS_MAX_TOKENS,
						CreditFeature.VIDEO_ANALYSIS, timeout,
						completion -> new DouyinAnalysisOutcome(VideoRecreationResultNormalizer
								.normalize(completion.content(), completion.providerRunId())))
				.map(FrozenTextExecutionService.Traced::value);
	}

	/** 任务模式复刻分镜分析：短视频走冻结快照执行；长视频与独立模式同限。 */
	public Mono<DouyinAnalysisOutcome> analyzeTaskForRecreation(String proxyVideoUrl, String accountId,
			VideoRecreationTaskRequest task, ServerWebExchange exchange) {
		String token = extractToken(proxyVideoUrl);
		DouyinMediaTarget target = tokenCodec.parse(token);
		long duration = assertAnalysisDuration(target.durationSeconds());
		if (!"qwen".equalsIgnoreCase(provider) || publicBackendOrigin.isBlank()) {
			throw new IntelligenceException(503, "Java 视频分析 provider 或 PUBLIC_BACKEND_ORIGIN 未配置");
		}
		if (duration > maxSingleSegmentSeconds) {
			throw new IntelligenceException(422, "复刻分析暂不支持分段视频");
		}
		return taskAnalysis.analyzeShortRecreation(buildPublicProxyUrl(token), accountId, task, exchange)
				.map(DouyinAnalysisOutcome::new);
	}

	private Mono<DouyinAnalysisOutcome> analyzeTarget(String token, DouyinMediaTarget target, long duration,
			ServerWebExchange exchange) {
		if (duration <= maxSingleSegmentSeconds) {
			List<ContentPart> parts = List.of(ContentPart.video(buildPublicProxyUrl(token)),
					ContentPart.text(VideoAnalysisPrompts.analysis()));
			return frozenText
					.executeIndependent(exchange, List.of(ChatMessage.user(parts)), ANALYSIS_MAX_TOKENS,
							CreditFeature.VIDEO_ANALYSIS, timeout,
							completion -> new DouyinAnalysisOutcome(VideoAnalysisResultNormalizer
									.normalize(completion.content(), completion.providerRunId())))
					.map(FrozenTextExecutionService.Traced::value);
		}
		return media.prepareDouyinVideo(target)
				.flatMap(sourceId -> media.createClips(sourceId, duration, 30)
						.flatMap(ids -> segmented.analyze("douyin", ids, timeout, exchange)
								.map(DouyinAnalysisOutcome::new).doFinally(signal -> ids.forEach(media::remove)))
						.doFinally(signal -> media.remove(sourceId)));
	}

	private long assertAnalysisDuration(Long durationSeconds) {
		if (durationSeconds == null) {
			throw new IntelligenceException(422, "未能识别视频时长，请重新提取后再分析");
		}
		if (durationSeconds > MAX_ANALYSIS_DURATION_SECONDS) {
			throw new IntelligenceException(422, "当前仅支持分析 10 分钟以内的抖音视频，建议选择 30 秒到 2 分钟的视频");
		}
		return durationSeconds;
	}

	/**
	 * 移植 legacy {@code extractTokenFromProxyUrl}：相对路径或与 PUBLIC_BACKEND_ORIGIN 同源；抽
	 * {@code /api/douyin/proxy/{token}}。
	 */
	private String extractToken(String proxyVideoUrl) {
		if (proxyVideoUrl == null || proxyVideoUrl.isBlank()) {
			throw new IntelligenceException(400, INVALID_PROXY_URL);
		}
		String url = proxyVideoUrl.trim();
		String path;
		if (url.startsWith("http://") || url.startsWith("https://")) {
			URI parsed;
			try {
				parsed = URI.create(url);
			} catch (IllegalArgumentException error) {
				throw new IntelligenceException(400, INVALID_PROXY_URL);
			}
			if (publicBackendOrigin.isBlank()) {
				throw new IntelligenceException(400, INVALID_PROXY_URL);
			}
			if (!sameOrigin(parsed, publicBackendOrigin)) {
				throw new IntelligenceException(400, INVALID_PROXY_URL);
			}
			path = parsed.getRawPath();
		} else {
			// 相对路径：按 localhost 解析（与 legacy `new URL(value, 'http://localhost')` 等价）。
			URI parsed;
			try {
				parsed = URI.create("http://localhost" + (url.startsWith("/") ? url : "/" + url));
			} catch (IllegalArgumentException error) {
				throw new IntelligenceException(400, INVALID_PROXY_URL);
			}
			path = parsed.getRawPath();
		}
		if (path == null) {
			throw new IntelligenceException(400, INVALID_PROXY_URL);
		}
		Matcher matcher = PROXY_TOKEN_PATH.matcher(path);
		if (!matcher.matches()) {
			throw new IntelligenceException(400, INVALID_PROXY_URL);
		}
		// token 为 base64url + '.'，URL 安全字符，无需 decode（与 DouyinExtractController 拼接契约一致）。
		return matcher.group(1);
	}

	private String buildPublicProxyUrl(String token) {
		// publicBackendOrigin 非空已在 analyze 开始时验证；token URL 安全，直接拼接。
		String base = publicBackendOrigin.endsWith("/")
				? publicBackendOrigin.substring(0, publicBackendOrigin.length() - 1)
				: publicBackendOrigin;
		return base + "/api/douyin/proxy/" + token;
	}

	private static boolean sameOrigin(URI parsed, String publicBackendOrigin) {
		try {
			URI expected = URI.create(publicBackendOrigin);
			return eq(parsed.getScheme(), expected.getScheme()) && eq(parsed.getHost(), expected.getHost())
					&& port(parsed) == port(expected);
		} catch (IllegalArgumentException error) {
			return false;
		}
	}

	private static int port(URI uri) {
		int port = uri.getPort();
		if (port != -1) {
			return port;
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static boolean eq(String a, String b) {
		return (a == null) ? b == null : a.equalsIgnoreCase(b);
	}
}
