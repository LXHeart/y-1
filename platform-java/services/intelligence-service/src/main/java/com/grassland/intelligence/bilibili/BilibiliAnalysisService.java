package com.grassland.intelligence.bilibili;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
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
 * Bilibili 视频分析编排（草场 Slice 13 Stage 5）。移植 legacy {@code bilibili-video-analysis.service.ts} 的
 * {@code analyzeBilibiliVideoByProxyUrl}（content 提取）与 {@code analyzeBilibiliVideoForRecreation}（复刻场景）。
 *
 * <p><b>Java 分析路径</b>：
 * <ul>
 *   <li>不超过单段阈值的视频通过公开 Java 代理地址直接提交 Qwen。</li>
 *   <li>DASH 或长视频由 Java 下载/合流并以 30 秒片段提交 Qwen，结果按时间顺序合并。</li>
 *   <li>仅在完整分析开始前扣一次积分；任一上游步骤失败会自动退款。</li>
 * </ul>
 *
 * <p>平台级 Qwen 配置（{@code ai.bilibili-analysis.provider} 默认 qwen），不读用户 BYOK 设置（与 Slice 10 同前提）。
 */
@Service
public class BilibiliAnalysisService {

    /** legacy {@code maxAnalysisDurationSeconds}：超过 10 分钟拒绝（422）。 */
    static final int MAX_ANALYSIS_DURATION_SECONDS = 10 * 60;

    private static final Pattern PROXY_TOKEN_PATH = Pattern.compile("^/api/bilibili/proxy/([^/]+)$");
    private static final String INVALID_PROXY_URL = "视频代理地址无效";

    private final AiCapabilityAdapter ai;
    private final BilibiliProxyToken tokenCodec;
    private final CreditsClient credits;
    private final String provider;
    private final Duration timeout;
    private final int maxSingleSegmentSeconds;
    private final String publicBackendOrigin;
    private final PlatformMediaService media;
    private final VideoSegmentAnalysisService segmented;
    private final TaskVideoAnalysisService taskAnalysis;

    public BilibiliAnalysisService(AiCapabilityAdapter ai, BilibiliProxyToken tokenCodec, CreditsClient credits,
                                   Environment environment, PlatformMediaService media,
                                   VideoSegmentAnalysisService segmented,
                                   TaskVideoAnalysisService taskAnalysis) {
        this.ai = ai;
        this.tokenCodec = tokenCodec;
        this.credits = credits;
        this.provider = environment.getProperty("ai.bilibili-analysis.provider", "qwen");
        long timeoutMs = environment.getProperty("ai.bilibili-analysis.timeout-ms", Long.class, 180_000L);
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
        this.maxSingleSegmentSeconds = environment.getProperty("ai.bilibili-analysis.max-single-segment-seconds",
                Integer.class, 60);
        this.publicBackendOrigin = environment.getProperty("app.public-backend-origin", "");
        this.media = media;
        this.segmented = segmented;
        this.taskAnalysis = taskAnalysis;
    }

    /** content 提取（live 端点 {@code POST /api/bilibili/analyze-video}）。 */
    public Mono<BilibiliAnalysisOutcome> analyze(String proxyVideoUrl, String accountId) {
        String token = extractToken(proxyVideoUrl);
        BilibiliMediaTarget target = tokenCodec.parse(token);
        long duration = assertAnalysisDuration(target.durationSeconds());

        assertJavaConfigured();
        return credits.consume(accountId, CreditFeature.VIDEO_ANALYSIS)
                .flatMap(charge -> analyzeTarget(token, target, duration)
                        // 上游失败：退回已扣积分后仍向调用方抛原始错误（GL-P0-BILL-002）
                        .onErrorResume(error -> credits.refund(charge, "Bilibili 视频分析失败自动退回")
                                .then(Mono.error(error))));
    }

    public Mono<BilibiliAnalysisOutcome> analyzeTask(
            String proxyVideoUrl,
            String accountId,
            VideoRecreationTaskRequest task,
            ServerWebExchange exchange) {
        String token = extractToken(proxyVideoUrl);
        BilibiliMediaTarget target = tokenCodec.parse(token);
        long duration = assertAnalysisDuration(target.durationSeconds());
        assertJavaConfigured();
        if (target instanceof BilibiliMediaTarget.Progressive && duration <= maxSingleSegmentSeconds) {
            return taskAnalysis.analyzeShort(
                            buildPublicProxyUrl(token), accountId, task, exchange)
                    .map(BilibiliAnalysisOutcome::new);
        }
        return media.prepareBilibili(target).flatMap(sourceId ->
                (duration > maxSingleSegmentSeconds
                        ? media.createClips(sourceId, duration, 30)
                        : Mono.just(List.of(sourceId)))
                        .flatMap(ids -> taskAnalysis.analyzeSegments(
                                        "bilibili", ids, accountId, task, exchange)
                                .map(BilibiliAnalysisOutcome::new)
                                .doFinally(signal -> ids.forEach(media::remove)))
                        .doFinally(signal -> media.remove(sourceId)));
    }

    /**
     * 复刻分镜场景分析（{@code POST /api/bilibili/analyze-video} body {@code mode:"recreation"}）。
     * 与 {@link #analyze} 同路由决策；Java 路径用 {@link VideoAnalysisPrompts#recreation()} + 复刻归一；
     * legacy {@code analyzeBilibiliVideoForRecreation} 在切流期无路由，本方法即其正式暴露。
     */
    public Mono<BilibiliAnalysisOutcome> analyzeForRecreation(String proxyVideoUrl, String accountId) {
        String token = extractToken(proxyVideoUrl);
        BilibiliMediaTarget target = tokenCodec.parse(token);
        long duration = assertAnalysisDuration(target.durationSeconds());

        assertJavaConfigured();
        if (!(target instanceof BilibiliMediaTarget.Progressive) || duration > maxSingleSegmentSeconds) {
            throw new IntelligenceException(422, "复刻分析暂不支持分段视频");
        }
        String publicProxyUrl = buildPublicProxyUrl(token);
        List<ContentPart> parts = List.of(ContentPart.video(publicProxyUrl), ContentPart.text(VideoAnalysisPrompts.recreation()));
        return credits.consume(accountId, CreditFeature.VIDEO_ANALYSIS)
                .flatMap(charge -> ai.completeMultimodalMeta(parts, timeout)
                        .map(meta -> new BilibiliAnalysisOutcome(
                                VideoRecreationResultNormalizer.normalize(meta.content(), meta.runId())))
                        // 上游失败：退回已扣积分后仍向调用方抛原始错误（GL-P0-BILL-002）
                        .onErrorResume(error -> credits.refund(charge, "Bilibili 复刻分析失败自动退回")
                                .then(Mono.error(error))));
    }

    /** 任务模式复刻分镜分析：短 progressive 走冻结快照执行；DASH/长视频与独立模式同限。 */
    public Mono<BilibiliAnalysisOutcome> analyzeTaskForRecreation(
            String proxyVideoUrl,
            String accountId,
            VideoRecreationTaskRequest task,
            ServerWebExchange exchange) {
        String token = extractToken(proxyVideoUrl);
        BilibiliMediaTarget target = tokenCodec.parse(token);
        long duration = assertAnalysisDuration(target.durationSeconds());
        assertJavaConfigured();
        if (!(target instanceof BilibiliMediaTarget.Progressive) || duration > maxSingleSegmentSeconds) {
            throw new IntelligenceException(422, "复刻分析暂不支持分段视频");
        }
        return taskAnalysis.analyzeShortRecreation(buildPublicProxyUrl(token), accountId, task, exchange)
                .map(BilibiliAnalysisOutcome::new);
    }

    private void assertJavaConfigured() {
        if (!"qwen".equalsIgnoreCase(provider) || publicBackendOrigin.isBlank()) {
            throw new IntelligenceException(503, "Java 视频分析 provider 或 PUBLIC_BACKEND_ORIGIN 未配置");
        }
    }

    private Mono<BilibiliAnalysisOutcome> analyzeTarget(String token, BilibiliMediaTarget target, long duration) {
        if (target instanceof BilibiliMediaTarget.Progressive && duration <= maxSingleSegmentSeconds) {
            List<ContentPart> parts = List.of(ContentPart.video(buildPublicProxyUrl(token)),
                    ContentPart.text(VideoAnalysisPrompts.analysis()));
            return ai.completeMultimodalMeta(parts, timeout).map(meta -> (BilibiliAnalysisOutcome)
                    new BilibiliAnalysisOutcome(VideoAnalysisResultNormalizer.normalize(meta.content(), meta.runId())));
        }
        return media.prepareBilibili(target).flatMap(sourceId ->
                (duration > maxSingleSegmentSeconds ? media.createClips(sourceId, duration, 30) : Mono.just(List.of(sourceId)))
                        .flatMap(ids -> segmented.analyze("bilibili", ids, timeout)
                                .map(BilibiliAnalysisOutcome::new)
                                .doFinally(signal -> ids.forEach(media::remove)))
                        .doFinally(signal -> media.remove(sourceId)));
    }

    private long assertAnalysisDuration(Long durationSeconds) {
        if (durationSeconds == null) {
            throw new IntelligenceException(422, "未能识别视频时长，请重新提取后再分析");
        }
        if (durationSeconds > MAX_ANALYSIS_DURATION_SECONDS) {
            throw new IntelligenceException(422, "当前仅支持分析 10 分钟以内的 B 站视频，建议选择 30 秒到 2 分钟的视频");
        }
        return durationSeconds;
    }

    /** 移植 legacy {@code extractTokenFromProxyUrl}：相对路径或与 PUBLIC_BACKEND_ORIGIN 同源；抽 {@code /api/bilibili/proxy/{token}}。 */
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
        // token 为 base64url + '.'，URL 安全字符，无需 decode（与 BilibiliExtractController 拼接契约一致）。
        return matcher.group(1);
    }

    private String buildPublicProxyUrl(String token) {
        // publicBackendOrigin 非空已由 assertJavaConfigured 保证；token URL 安全，直接拼接。
        String base = publicBackendOrigin.endsWith("/") ? publicBackendOrigin.substring(0, publicBackendOrigin.length() - 1) : publicBackendOrigin;
        return base + "/api/bilibili/proxy/" + token;
    }

    private static boolean sameOrigin(URI parsed, String publicBackendOrigin) {
        try {
            URI expected = URI.create(publicBackendOrigin);
            return eq(parsed.getScheme(), expected.getScheme())
                    && eq(parsed.getHost(), expected.getHost())
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
