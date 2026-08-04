package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 控制面 Run API（GL-P3-AI-001）。让 {@link AiExecutionService} 闭环首次可达、可测。
 *
 * <p>端点（{@code resolve}——任意登录用户；资源按账号作用域）：
 * <ul>
 *   <li>POST /api/ai/runs — 执行一次 sync text run：预算→路由→(平台)扣分/(BYOK)解密→落库→provider 调用→结算/退款。</li>
 *   <li>GET /api/ai/runs — 列出当前账号最近 Run（含 TaskContext）。</li>
 *   <li>GET /api/ai/runs/{id} — Run 详情（跨账号 404）。</li>
 * </ul>
 *
 * <p>非整体 {@code @Conditional}：平台 run + 查询不依赖 KEK；仅 BYOK 解密分支在无 KEK 时 503（{@code AiExecutionService}）。
 * 真实 provider 凭据 / 浏览器真链路不在本 slice（IT 用 WireMock 证明解密→调用链）。
 */
@RestController
@RequestMapping("/api/ai/runs")
public class AiRunController {

    private static final int LIST_LIMIT = 50;
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final IntelligenceCallerResolver callers;
    private final AiExecutionService aiExecution;
    private final TextCompletionClient textClient;
    private final AiRunRepository runRepository;
    private final PriceTableService priceTableService;
    private final PlatformModelConfig platformDefaults;

    public AiRunController(
            IntelligenceCallerResolver callers,
            AiExecutionService aiExecution,
            TextCompletionClient textClient,
            AiRunRepository runRepository,
            PriceTableService priceTableService,
            PlatformModelConfig platformDefaults) {
        this.callers = callers;
        this.aiExecution = aiExecution;
        this.textClient = textClient;
        this.runRepository = runRepository;
        this.priceTableService = priceTableService;
        this.platformDefaults = platformDefaults;
    }

    @PostMapping
    public Mono<ResponseEntity<AiRunResponse>> execute(
            @Valid @RequestBody ExecuteRunRequest body, ServerWebExchange exchange) {
        boolean allowFallback = body.allowFallback() == null || body.allowFallback();
        int maxTokens = body.maxTokens() == null ? DEFAULT_MAX_TOKENS : body.maxTokens();
        int estCents = safeEstimate(maxTokens);

        return aiExecution.prepareExecution(exchange, body.capability(), CreditFeature.AI_RUN_TEXT,
                maxTokens, estCents, allowFallback)
                .flatMap(result -> result.allowed()
                        ? doExecute(result.context(), body.prompt(), maxTokens)
                        : Mono.error(deniedException(result.denialReason())));
    }

    private Mono<ResponseEntity<AiRunResponse>> doExecute(
            AiExecutionService.ExecutionContext ctx, String prompt, int maxTokens) {
        String bearer = ctx.provider().isPlatform() ? platformDefaults.apiKey() : ctx.decryptedKey();
        return textClient.complete(ctx.provider().baseUrl(), bearer, ctx.provider().model(), prompt, maxTokens)
                .flatMap(completion -> aiExecution.settleSuccess(ctx, completion.inputTokens(), completion.outputTokens(), 0, 0)
                        .then(runRepository.findById(ctx.runId()))
                        .map(run -> ResponseEntity.ok(AiRunResponse.executed(run, completion))))
                .onErrorResume(error -> aiExecution.handleFailure(ctx, errorMessage(error))
                        .then(Mono.error(error)));
    }

    @GetMapping
    public Flux<AiRunResponse> list(ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMapMany(c -> runRepository.findByAccount(c.accountId(), LIST_LIMIT)
                        .map(AiRunResponse::summary));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<AiRunResponse>> get(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .flatMap(c -> runRepository.findById(id)
                        .filter(run -> c.accountId().equals(run.accountId()))  // 跨账号 → 空 → 404
                        .map(run -> ResponseEntity.ok(AiRunResponse.summary(run)))
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "Run 不存在"))));
    }

    private int safeEstimate(int estTokens) {
        try {
            return priceTableService.estimateCost(platformDefaults.model(), estTokens, 0, 0);
        } catch (IllegalArgumentException e) {
            return 0;  // 价目表无该模型：预算按 0 估（平台承担，已知缺口）
        }
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : "AI run failed";
    }

    /** denialReason → HTTP 状态（与 {@link #prepareExecution} 的拒绝语义对齐）。 */
    private static IntelligenceException deniedException(String reason) {
        return switch (reason) {
            case "insufficient_credits" -> new IntelligenceException(402, "积分不足");
            case "exceeds_run_budget", "exceeds_daily_budget", "exceeds_monthly_budget" ->
                    new IntelligenceException(402, "已达模型预算上限：" + reason);
            case "fallback_not_authorized" -> new IntelligenceException(403, "无 BYOK 且未授权回退平台模型");
            case "no_platform_model" -> new IntelligenceException(503, "平台未配置该能力的模型");
            default -> new IntelligenceException(403, "执行被拒绝：" + reason);
        };
    }
}
