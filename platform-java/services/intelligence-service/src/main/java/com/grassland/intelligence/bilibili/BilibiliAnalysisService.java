package com.grassland.intelligence.bilibili;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
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
 * Bilibili 视频分析编排（草场 Slice 13 Stage 5）。移植 legacy {@code bilibili-video-analysis.service.ts} 的
 * {@code analyzeBilibiliVideoByProxyUrl}（content 提取）与 {@code analyzeBilibiliVideoForRecreation}（复刻场景，无路由）。
 *
 * <p><b>路由决策</b>（Java vs 回落 legacy）——在扣积分之前判定，避免双扣：
 * <ul>
 *   <li>Java 路径 ⟺ {@code kind=progressive && durationSeconds ≤ maxSingleSegmentSeconds(默认 60) && provider=qwen
 *       && PUBLIC_BACKEND_ORIGIN 已配置}。直接发 {@code video_url}（公开代理地址）给平台 Qwen，扣积分（CreditsClient），
 *       归一返回。</li>
 *   <li>其余（DASH / 超阈值需 FFmpeg 切片 / 非 qwen provider）→ {@link BilibiliAnalysisOutcome.Fallback}，
 *       由 controller 整体转发 legacy（legacy 扣积分 + FFmpeg/Coze）。Cookie 经 edge-bff→intelligence→legacy 透传，
 *       legacy session 中间件解析同 {@code COOKIE_SECRET}+{@code y1.sid} → {@code getSessionUser}+{@code requireCredit} 闭环。</li>
 * </ul>
 *
 * <p>FFmpeg 切片 / 分段合并（legacy ~350 行 overlap trim）不移植；超阈值一律回落 legacy。
 * 平台级 Qwen 配置（{@code ai.bilibili-analysis.provider} 默认 qwen），不读用户 BYOK 设置（与 Slice 10 同前提）。
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

    public BilibiliAnalysisService(AiCapabilityAdapter ai, BilibiliProxyToken tokenCodec, CreditsClient credits,
                                   Environment environment) {
        this.ai = ai;
        this.tokenCodec = tokenCodec;
        this.credits = credits;
        this.provider = environment.getProperty("ai.bilibili-analysis.provider", "qwen");
        long timeoutMs = environment.getProperty("ai.bilibili-analysis.timeout-ms", Long.class, 180_000L);
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
        this.maxSingleSegmentSeconds = environment.getProperty("ai.bilibili-analysis.max-single-segment-seconds",
                Integer.class, 60);
        this.publicBackendOrigin = environment.getProperty("app.public-backend-origin", "");
    }

    /** content 提取（live 端点 {@code POST /api/bilibili/analyze-video}）。 */
    public Mono<BilibiliAnalysisOutcome> analyze(String proxyVideoUrl, String accountId) {
        String token = extractToken(proxyVideoUrl);
        BilibiliMediaTarget target = tokenCodec.parse(token);
        long duration = assertAnalysisDuration(target.durationSeconds());

        if (!isJavaEligible(target, duration)) {
            return Mono.just(new BilibiliAnalysisOutcome.Fallback());
        }
        String publicProxyUrl = buildPublicProxyUrl(token);
        List<ContentPart> parts = List.of(ContentPart.video(publicProxyUrl), ContentPart.text(BilibiliAnalysisPrompts.analysis()));
        return credits.consume(accountId, CreditFeature.VIDEO_ANALYSIS)
                .then(ai.completeMultimodalMeta(parts, timeout)
                        .map(meta -> new BilibiliAnalysisOutcome.Java(
                                BilibiliAnalysisResultNormalizer.normalize(meta.content(), meta.runId()))));
    }

    /**
     * 复刻分镜场景分析（无路由——legacy {@code analyzeBilibiliVideoForRecreation} 为 dead code，此处仅银行化 Java 能力）。
     * 与 {@link #analyze} 同路由决策；Java 路径用 {@link BilibiliAnalysisPrompts#recreation()} + 复刻归一。
     */
    public Mono<BilibiliAnalysisOutcome> analyzeForRecreation(String proxyVideoUrl, String accountId) {
        String token = extractToken(proxyVideoUrl);
        BilibiliMediaTarget target = tokenCodec.parse(token);
        long duration = assertAnalysisDuration(target.durationSeconds());

        if (!isJavaEligible(target, duration)) {
            return Mono.just(new BilibiliAnalysisOutcome.Fallback());
        }
        String publicProxyUrl = buildPublicProxyUrl(token);
        List<ContentPart> parts = List.of(ContentPart.video(publicProxyUrl), ContentPart.text(BilibiliAnalysisPrompts.recreation()));
        return credits.consume(accountId, CreditFeature.VIDEO_ANALYSIS)
                .then(ai.completeMultimodalMeta(parts, timeout)
                        .map(meta -> new BilibiliAnalysisOutcome.Java(
                                BilibiliRecreationResultNormalizer.normalize(meta.content(), meta.runId()))));
    }

    private boolean isJavaEligible(BilibiliMediaTarget target, long duration) {
        return target instanceof BilibiliMediaTarget.Progressive
                && duration <= maxSingleSegmentSeconds
                && "qwen".equalsIgnoreCase(provider)
                && !publicBackendOrigin.isBlank();
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
        // publicBackendOrigin 非空已由 isJavaEligible 保证；token URL 安全，直接拼接。
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
