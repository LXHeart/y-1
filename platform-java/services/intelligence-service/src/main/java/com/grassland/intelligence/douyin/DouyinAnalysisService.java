package com.grassland.intelligence.douyin;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.mediaplatform.VideoAnalysisPrompts;
import com.grassland.intelligence.mediaplatform.VideoAnalysisResultNormalizer;
import com.grassland.intelligence.mediaplatform.PlatformMediaService;
import com.grassland.intelligence.mediaplatform.VideoSegmentAnalysisService;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Douyin 视频分析编排（草场 GL-P3-MEDIA-001）。移植 legacy {@code douyin-video-analysis.service.ts} 的
 * {@code analyzeDouyinVideoByProxyUrl} 短视频分支。
 *
 * <p><b>Java 分析路径</b>：
 * <ul>
 *   <li>不超过单段阈值的视频通过公开 Java 代理地址直接提交 Qwen。</li>
 *   <li>长视频由 Java 下载并以 30 秒片段提交 Qwen，结果按时间顺序合并。</li>
 *   <li>仅在完整分析开始前扣一次积分；任一上游步骤失败会自动退款。</li>
 * </ul>
 *
 * <p>提示词与归一和 Bilibili Java 路径共用 {@link VideoAnalysisPrompts}/{@link VideoAnalysisResultNormalizer}
 * （douyin/bilibili 使用同一 prompt 和归一规则）。平台级 Qwen 配置
 * （{@code ai.douyin-analysis.provider} 默认 qwen），不读用户 BYOK 设置。
 */
@Service
public class DouyinAnalysisService {

    /** legacy {@code maxAnalysisDurationSeconds}：超过 10 分钟拒绝（422）。 */
    static final int MAX_ANALYSIS_DURATION_SECONDS = 10 * 60;

    private static final Pattern PROXY_TOKEN_PATH = Pattern.compile("^/api/douyin/proxy/([^/]+)$");
    private static final String INVALID_PROXY_URL = "视频代理地址无效";

    private final AiCapabilityAdapter ai;
    private final DouyinProxyToken tokenCodec;
    private final CreditsClient credits;
    private final String provider;
    private final Duration timeout;
    private final int maxSingleSegmentSeconds;
    private final String publicBackendOrigin;
    private final PlatformMediaService media;
    private final VideoSegmentAnalysisService segmented;

    public DouyinAnalysisService(AiCapabilityAdapter ai, DouyinProxyToken tokenCodec, CreditsClient credits,
                                 Environment environment, PlatformMediaService media,
                                 VideoSegmentAnalysisService segmented) {
        this.ai = ai;
        this.tokenCodec = tokenCodec;
        this.credits = credits;
        this.provider = environment.getProperty("ai.douyin-analysis.provider", "qwen");
        long timeoutMs = environment.getProperty("ai.douyin-analysis.timeout-ms", Long.class, 180_000L);
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
        this.maxSingleSegmentSeconds = environment.getProperty("ai.douyin-analysis.max-single-segment-seconds",
                Integer.class, 60);
        this.publicBackendOrigin = environment.getProperty("app.public-backend-origin", "");
        this.media = media;
        this.segmented = segmented;
    }

    /** content 提取（live 端点 {@code POST /api/douyin/analyze-video}）。 */
    public Mono<DouyinAnalysisOutcome> analyze(String proxyVideoUrl, String accountId) {
        String token = extractToken(proxyVideoUrl);
        DouyinMediaTarget target = tokenCodec.parse(token);
        long duration = assertAnalysisDuration(target.durationSeconds());

        if (!"qwen".equalsIgnoreCase(provider) || publicBackendOrigin.isBlank()) {
            throw new IntelligenceException(503, "Java 视频分析 provider 或 PUBLIC_BACKEND_ORIGIN 未配置");
        }
        return credits.consume(accountId, CreditFeature.VIDEO_ANALYSIS)
                .flatMap(charge -> analyzeTarget(token, target, duration)
                        // 上游失败：退回已扣积分后仍向调用方抛原始错误（GL-P0-BILL-002）
                        .onErrorResume(error -> credits.refund(charge, "抖音视频分析失败自动退回")
                                .then(Mono.error(error))));
    }

    private Mono<DouyinAnalysisOutcome> analyzeTarget(String token, DouyinMediaTarget target, long duration) {
        if (duration <= maxSingleSegmentSeconds) {
            List<ContentPart> parts = List.of(ContentPart.video(buildPublicProxyUrl(token)),
                    ContentPart.text(VideoAnalysisPrompts.analysis()));
            return ai.completeMultimodalMeta(parts, timeout).map(meta -> (DouyinAnalysisOutcome)
                    new DouyinAnalysisOutcome(VideoAnalysisResultNormalizer.normalize(meta.content(), meta.runId())));
        }
        return media.prepareDouyinVideo(target).flatMap(sourceId -> media.createClips(sourceId, duration, 30)
                .flatMap(ids -> segmented.analyze("douyin", ids, timeout)
                        .map(DouyinAnalysisOutcome::new)
                        .doFinally(signal -> ids.forEach(media::remove)))
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

    /** 移植 legacy {@code extractTokenFromProxyUrl}：相对路径或与 PUBLIC_BACKEND_ORIGIN 同源；抽 {@code /api/douyin/proxy/{token}}。 */
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
        String base = publicBackendOrigin.endsWith("/") ? publicBackendOrigin.substring(0, publicBackendOrigin.length() - 1) : publicBackendOrigin;
        return base + "/api/douyin/proxy/" + token;
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
