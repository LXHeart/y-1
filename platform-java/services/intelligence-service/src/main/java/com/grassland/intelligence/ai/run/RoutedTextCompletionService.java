package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 用户态文本完成的统一模型路由（2026-08-26 双通道收敛）。
 *
 * <p>此前创作域一批遗留流（图片评价 step/*、文章 outline/content、朋友圈文案、视频复刻改编、
 * 游客试用等）直连 env 版 {@code QwenClient}，绕过「模型密钥」面板开关与管理后台平台模型配置。
 * 本服务把这些流全部接到既有路由体系上，语义与执行环（{@code AiExecutionService}）一致：
 *
 * <ul>
 * <li>商家活动身份：组织 BYOK &gt; 平台控制面；推荐官/消费者：个人 BYOK（受「使用自定义模型」
 *     开关控制）&gt; 平台控制面；匿名：平台控制面直连。</li>
 * <li>平台层 = {@code platform_model_config} 控制面（管理后台配置）；凭据密文经
 *     {@link ProviderKeyDecryptor} 解密，无密钥回落 env bootstrap。</li>
 * <li>DENIED（无平台模型）→ 503 fail-closed。</li>
 * </ul>
 *
 * <p><b>刻意不带计费闸</b>：本服务只决定「用哪个模型」，不改变各调用方既有计费语义——免费流
 * （step/*、游客试用、文章独立模式）继续免费，走执行环的计费流照旧。BYOK 分支沿用 TextCompletionClient
 * 的 SSRF 防护（仅 HTTPS + 全量公网 DNS 钉扎）。
 *
 * <p>错误文案：调用方各自的 {@code failureMessage} 落在 502 上（镜像 legacy {@code TextCompletionCommand}
 * 语义）；503（凭据缺失 fail-closed）与 504（超时）保留具体文案——用户需要知道是平台配置问题而不是含糊失败。
 */
@Component
public class RoutedTextCompletionService {

    private static final Logger logger = LoggerFactory.getLogger(RoutedTextCompletionService.class);

    private static final String CAPABILITY_TEXT = "text";

    private final IntelligenceCallerResolver callers;
    private final ByokRoutingService routing;
    private final ProviderKeyDecryptor keyDecryptor;
    private final TextCompletionClient textCompletion;

    public RoutedTextCompletionService(IntelligenceCallerResolver callers, ByokRoutingService routing,
            ProviderKeyDecryptor keyDecryptor, TextCompletionClient textCompletion) {
        this.callers = callers;
        this.routing = routing;
        this.keyDecryptor = keyDecryptor;
        this.textCompletion = textCompletion;
    }

    /** 一次路由决策的完整产物：provider 解析结果 + 解密后的 bearer 明文（只活在本次调用链）。 */
    public record Routed(ProviderResolution resolution, String bearer) {
        public boolean byok() {
            return resolution.isByok();
        }
    }

    // ---------------- 解析 ----------------

    /** 按请求解析（软解析：匿名 → 平台直连）。 */
    public Mono<Routed> resolve(ServerWebExchange exchange) {
        return callers.resolveOptional(exchange.getRequest())
                .flatMap(caller -> resolveFor(caller.accountId(), caller.organizationId()))
                .switchIfEmpty(Mono.defer(() -> resolveFor(null, null)));
    }

    /** 按显式身份解析（accountId 为 null = 匿名 → 平台直连）。 */
    public Mono<Routed> resolveFor(String accountId, String organizationId) {
        return resolveRouted(accountId == null && organizationId == null
                ? routing.resolvePlatform(CAPABILITY_TEXT)
                : routing.resolveProvider(organizationId, accountId, CAPABILITY_TEXT, true));
    }

    /** 治理域固定平台（决策：治理判定不受用户自有模型影响）。 */
    public Mono<Routed> resolvePlatform() {
        return resolveRouted(routing.resolvePlatform(CAPABILITY_TEXT));
    }

    private Mono<Routed> resolveRouted(Mono<ProviderResolution> resolution) {
        // decryptIfNeeded 同步抛 IntelligenceException（KEK 缺失/平台凭据双缺 503），defer 兜住进响应链。
        return Mono.defer(() -> resolution.map(provider -> {
            if (provider.isDenied()) {
                throw new IntelligenceException(503, deniedMessage(provider));
            }
            // 排障锚点：每次生成都可从日志确认「用了哪个模型」（模型来源统一后的可观测性底线）。
            logger.info("AI routing: capability=text resolution={} provider={} model={} accountId 维度路由",
                    provider.isByok() ? (provider.byokOrganizationId() == null ? "BYOK" : "BYOK-ORG") : "PLATFORM",
                    provider.provider(), provider.model());
            return new Routed(provider, keyDecryptor.decryptIfNeeded(provider));
        }));
    }

    private static String deniedMessage(ProviderResolution provider) {
        return "no_platform_model".equals(provider.denialReason())
                ? "平台未配置可用的内置模型，请联系管理员"
                : "当前设置不允许使用平台内置模型";
    }

    // ---------------- 非流式完成 ----------------

    public Mono<TextCompletionResult> complete(ServerWebExchange exchange, List<ChatMessage> messages,
            int maxTokens, Duration timeout, String failureMessage) {
        return resolve(exchange).flatMap(r -> execute(r, messages, maxTokens, timeout, failureMessage));
    }

    public Mono<TextCompletionResult> completeFor(String accountId, String organizationId,
            List<ChatMessage> messages, int maxTokens, Duration timeout, String failureMessage) {
        return resolveFor(accountId, organizationId)
                .flatMap(r -> execute(r, messages, maxTokens, timeout, failureMessage));
    }

    /** 治理域固定平台：KYB 文档分析、履约凭证分析、门店媒体审核等。 */
    public Mono<TextCompletionResult> completePlatformOnly(
            List<ChatMessage> messages, int maxTokens, Duration timeout, String failureMessage) {
        return resolvePlatform().flatMap(r -> execute(r, messages, maxTokens, timeout, failureMessage));
    }

    /** 用既有的路由决策执行非流式调用（需要 lineage 真实 provider/model 的调用方两步走）。 */
    public Mono<TextCompletionResult> completeWith(Routed routed, List<ChatMessage> messages,
            int maxTokens, Duration timeout, String failureMessage) {
        return execute(routed, messages, maxTokens, timeout, failureMessage);
    }

    private Mono<TextCompletionResult> execute(Routed routed, List<ChatMessage> messages,
            int maxTokens, Duration timeout, String failureMessage) {
        return textCompletion
                .completeMessages(routed.resolution().provider(), routed.resolution().baseUrl(), routed.bearer(),
                        routed.resolution().model(), messages, maxTokens, routed.byok(), timeout)
                .onErrorMap(error -> userFacing(error, failureMessage));
    }

    // ---------------- 流式完成（文章 outline/content） ----------------

    public Flux<ChatChunk> stream(ServerWebExchange exchange, List<ChatMessage> messages,
            int maxTokens, Duration timeout, String failureMessage) {
        return resolve(exchange).flatMapMany(
                r -> streamWith(r, messages, maxTokens, timeout, failureMessage));
    }

    public Flux<ChatChunk> streamFor(String accountId, String organizationId, List<ChatMessage> messages,
            int maxTokens, Duration timeout, String failureMessage) {
        return resolveFor(accountId, organizationId)
                .flatMapMany(r -> streamWith(r, messages, maxTokens, timeout, failureMessage));
    }

    /** 用既有的路由决策执行流式调用（需要 lineage 真实 provider/model 的调用方两步走）。 */
    public Flux<ChatChunk> streamWith(Routed routed, List<ChatMessage> messages,
            int maxTokens, Duration timeout, String failureMessage) {
        return textCompletion
                .streamMessages(routed.resolution().provider(), routed.resolution().baseUrl(), routed.bearer(),
                        routed.resolution().model(), messages, maxTokens, routed.byok(), timeout)
                .onErrorMap(error -> userFacing(error, failureMessage));
    }

    /** 503/504 保留具体文案（平台配置缺失/超时），其余一律落调用方的失败文案（镜像 legacy）。 */
    private static Throwable userFacing(Throwable error, String failureMessage) {
        if (error instanceof IntelligenceException ie && (ie.status() == 503 || ie.status() == 504)) {
            return error;
        }
        return new IntelligenceException(502, failureMessage);
    }
}
