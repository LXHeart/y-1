package com.grassland.intelligence.guesttrial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.guesttrial.GuestTrialRateLimiter.Exceeded;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 游客有限体验 HTTP 入口（任务书 #36 / ADR-D14）：未登录可用的窄面，**匿名放行**（镜像 HomepageController
 * 的 resolveOptional 先例——不要求断言，也不解析登录身份）。既有生成端点鉴权零改动（fail-closed 基线）。
 *
 * <ul>
 *   <li>POST /api/guest-trial/{capability} —— 试用（SSE）。失败语义锁定（R5）：IP 限流在 SSE 前判 → HTTP 429；
 *       连接建立后的失败（额度用尽/provider）一律 200 + SSE {@code {error, code}} 帧。</li>
 *   <li>GET /api/guest-trial/quota —— 额度徽标数据（各 capability 的 used/limit/remaining + 注册赠送文案值）。</li>
 * </ul>
 *
 * <p>编排顺序（陷阱清单）：限流 → 白名单 → 请求体校验 → 额度预检 → provider → 成功计次（R6：成功才算一次，
 * 扣减失败不影响已产出内容）。gtid cookie httpOnly 随首个响应发放（R3）。审计只存 IP 截断哈希（R8）。
 */
@RestController
@EnableConfigurationProperties(GuestTrialProperties.class)
public class GuestTrialController {

    static final String COOKIE_NAME = "gtid";
    /** 请求级 gtid 缓存键（同请求内多次解析同源）。 */
    private static final String GTID_ATTR = GuestTrialController.class.getName() + ".gtid";
    private static final Logger log = LoggerFactory.getLogger(GuestTrialController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 日界统一北京时间（平台既有约定）。 */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final Duration COOKIE_TTL = Duration.ofDays(365);
    /** image-review base64 内嵌上限（4MB 原始；base64 膨胀 4/3，进 buffer 前按字符判）。 */
    private static final int MAX_IMAGE_BASE64_CHARS = 4 * 1024 * 1024 * 4 / 3;

    private final GuestTrialProperties props;
    private final GuestTrialRateLimiter rateLimiter;
    private final GuestTrialQuotaRepository quotas;
    private final GuestTrialRunRepository runs;
    private final GuestTrialService trial;

    public GuestTrialController(GuestTrialProperties props,
                                GuestTrialRateLimiter rateLimiter,
                                GuestTrialQuotaRepository quotas,
                                GuestTrialRunRepository runs,
                                GuestTrialService trial) {
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.quotas = quotas;
        this.runs = runs;
        this.trial = trial;
    }

    @PostMapping("/api/guest-trial/{capability}")
    public Mono<ResponseEntity<Flux<DataBuffer>>> run(
            @PathVariable String capability,
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        if (!props.isEnabled() || !props.getCapabilities().contains(capability)) {
            return Mono.error(new IntelligenceException(404, "能力不存在"));
        }
        UUID gtid = resolveOrIssueGtid(exchange);
        // gtid 立即种进响应（含 error 路径——429 响应也带 cookie，客户端额度视角一致）；
        // 此后 sse()/quota() 再取时命中请求属性缓存，不会再生成第二个 id（否则计费与 cookie 不同源）。
        exchange.getResponse().addCookie(cookie(gtid));
        exchange.getAttributes().put(GTID_ATTR, gtid);
        String ipHash = ipHash(exchange.getRequest());
        LocalDate day = LocalDate.now(BEIJING);

        return rateLimiter.check(ipHash, day.toString())
                // Redis 故障 fail-closed：按超限拒绝（R2），不静默放行。
                .onErrorResume(error -> Mono.just(new Exceeded(GuestTrialRateLimiter.Layer.IP_BURST,
                        props.getIpBurstPerMinute())))
                .flatMap(exceeded -> runs.append(gtid, capability, ipHash,
                                GuestTrialRunRepository.OUTCOME_RATE_LIMITED)
                        .then(Mono.<ResponseEntity<Flux<DataBuffer>>>error(
                                new IntelligenceException(429, "尝试过于频繁，请稍后再试"))))
                .switchIfEmpty(Mono.defer(() -> runTrial(capability, body, gtid, ipHash, day, exchange)));
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> runTrial(
            String capability, Map<String, Object> body, UUID gtid, String ipHash,
            LocalDate day, ServerWebExchange exchange) {
        int limit = props.limitFor(capability);
        return quotas.used(gtid, capability, day)
                .flatMap(used -> used >= limit
                        ? exhaustedResponse(capability, gtid, ipHash, exchange)
                        : providerResponse(capability, body, gtid, ipHash, day, limit, exchange));
    }

    /** 额度用尽（R5 锁定语义）：200 + SSE error 帧 code=quota_exhausted（前端据此弹登录引导）。 */
    private Mono<ResponseEntity<Flux<DataBuffer>>> exhaustedResponse(
            String capability, UUID gtid, String ipHash, ServerWebExchange exchange) {
        return runs.append(gtid, capability, ipHash, GuestTrialRunRepository.OUTCOME_QUOTA_EXHAUSTED)
                .thenReturn(sse(Flux.just(
                        frame("progress", "正在检查今日体验次数…"),
                        errorFrame("今日免费体验次数已用完", "quota_exhausted")), exchange));
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> providerResponse(
            String capability, Map<String, Object> body, UUID gtid, String ipHash,
            LocalDate day, int limit, ServerWebExchange exchange) {
        Mono<String> provider;
        try {
            provider = switch (capability) {
                case "article-titles" -> trial.titles(requiredText(body, "topic", 100));
                case "content-score" -> trial.score(requiredText(body, "content", 5_000));
                case "image-review" -> trial.imageReview(
                        requiredText(body, "imageBase64", MAX_IMAGE_BASE64_CHARS),
                        optionalImageMime(body));
                default -> Mono.error(new IntelligenceException(404, "能力不存在"));
            };
        } catch (IllegalArgumentException error) {
            return Mono.error(new IntelligenceException(400, error.getMessage()));
        }
        Flux<String> payloads = Flux.just(frame("progress", "正在生成…"))
                .concatWith(provider
                        .flatMap(result -> quotas.consume(gtid, capability, day, limit)
                                .onErrorResume(error -> Mono.empty())   // 扣减失败不回滚内容（R6）
                                .then(runs.append(gtid, capability, ipHash,
                                        GuestTrialRunRepository.OUTCOME_SUCCESS))
                                .thenReturn(resultFrame(result)))
                        .onErrorResume(error -> {
                            log.warn("guest trial provider failed capability={} gtid={}", capability, gtid);
                            return runs.append(gtid, capability, ipHash,
                                            GuestTrialRunRepository.OUTCOME_PROVIDER_ERROR)
                                    .thenReturn(errorFrame("生成失败，请稍后再试（本次不消耗次数）",
                                            "provider_error"));
                        }));
        return Mono.just(sse(payloads, exchange));
    }

    /** 额度徽标数据（R7）：各 capability 的 used/limit/remaining + 注册赠送积分文案值。 */
    @GetMapping("/api/guest-trial/quota")
    public Mono<Map<String, Object>> quota(ServerWebExchange exchange) {
        if (!props.isEnabled()) {
            return Mono.error(new IntelligenceException(404, "能力不存在"));
        }
        UUID gtid = resolveOrIssueGtid(exchange);
        exchange.getAttributes().put(GTID_ATTR, gtid);
        exchange.getResponse().addCookie(cookie(gtid));
        LocalDate day = LocalDate.now(BEIJING);
        return Flux.fromIterable(props.getCapabilities())
                .concatMap(cap -> quotas.used(gtid, cap, day)
                        .map(used -> Map.entry(cap, Map.<String, Object>of(
                                "used", used,
                                "limit", props.limitFor(cap),
                                "remaining", Math.max(0, props.limitFor(cap) - used)))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
                .map(capabilities -> Map.<String, Object>of(
                        "success", true,
                        "data", Map.of(
                                "capabilities", capabilities,
                                "signupBonusCredits", props.getSignupBonusCredits())));
    }

    // ---------- helpers ----------

    private ResponseEntity<Flux<DataBuffer>> sse(Flux<String> payloads, ServerWebExchange exchange) {
        Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_EVENT_STREAM);
        h.set("X-Accel-Buffering", "no");
        h.setCacheControl("no-cache");
        return new ResponseEntity<>(sseBody, h, HttpStatus.OK);
    }

    private UUID resolveOrIssueGtid(ServerWebExchange exchange) {
        Object cached = exchange.getAttributes().get(GTID_ATTR);
        if (cached instanceof UUID uuid) {
            return uuid;   // 同一请求内二次解析（run + sse）必须同源
        }
        org.springframework.http.HttpCookie existing = exchange.getRequest().getCookies().getFirst(COOKIE_NAME);
        if (existing != null && !existing.getValue().isBlank()) {
            try {
                return UUID.fromString(existing.getValue());
            } catch (IllegalArgumentException ignored) {
                // 坏 cookie（手改/截断）→ 换新身份；旧身份额度自然沉淀，IP 层兜底（R3 权衡）。
            }
        }
        return UUID.randomUUID();
    }

    private ResponseCookie cookie(UUID gtid) {
        return ResponseCookie.from(COOKIE_NAME, gtid.toString())
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(COOKIE_TTL)
                .secure(props.isCookieSecure())
                .build();
    }

    /** IP → SHA-256 截断哈希（16 hex）：XFF 首值（经 edge 入口）优先，否则 remoteAddress。审计/限流共用。 */
    private static String ipHash(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        String ip = null;
        if (forwarded != null && !forwarded.isBlank()) {
            ip = forwarded.split(",")[0].trim();
        }
        if (ip == null || ip.isBlank()) {
            InetSocketAddress remote = request.getRemoteAddress();
            ip = remote == null ? "unknown" : remote.getAddress().getHostAddress();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception error) {
            return "0000000000000000";
        }
    }

    private static String requiredText(Map<String, Object> body, String field, int maxLength) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Object value = body.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String trimmed = text.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " 超长（上限 " + maxLength + " 字符）");
        }
        return trimmed;
    }

    private static String optionalImageMime(Map<String, Object> body) {
        Object value = body == null ? null : body.get("mimeType");
        if (value instanceof String mime && mime.startsWith("image/")) {
            return mime.trim();
        }
        return "image/jpeg";
    }

    private static String frame(String field, String value) {
        return "{\"" + field + "\":" + quote(value) + "}";
    }

    private static String errorFrame(String message, String code) {
        return "{\"error\":" + quote(message) + ",\"code\":" + quote(code) + "}";
    }

    /** result 帧：{@code {"result": <providerJson>}}——providerJson 已验为对象字面量，直接内嵌避免双重编码。 */
    private static String resultFrame(String providerJson) {
        return "{\"result\":" + providerJson + "}";
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            return "\"" + value + "\"";
        }
    }
}
