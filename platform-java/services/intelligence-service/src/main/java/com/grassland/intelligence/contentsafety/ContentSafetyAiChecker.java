package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.AiExecutionService.ExecutionResult;
import com.grassland.intelligence.ai.run.PlatformConcurrencyLimiter;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import com.grassland.intelligence.contentsafety.SafetyReport.Finding;
import com.grassland.intelligence.credits.CreditFeature;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * L2 LLM 深检（任务书 #34 / ADR-D16 D5）：content_safety capability 经
 * {@link AiExecutionService} 单一执行环的一次 run——**feature=null 免费分支**（平台资助 0 积分，
 * 见 ADR-D16 D5 实现载体修正），ai_run 留痕、沿用预算/并发机器，不开第二条执行旁路。
 *
 * <p>执行形态镜像 {@code FrozenTextExecutionService.executePrepared}：prepare → 并发槽 →
 * completeMessages → settleSuccess / handleFailure。控制面未配置 content_safety → denied
 * （= 只跑 L1 的降级路径，非错误）；模型坏 JSON 输出降级为「深检不可用」，不炸生成主流程（D6 advisory）。
 */
@Component
public class ContentSafetyAiChecker {

    /** ADR-D16 D5：长文本阈值——≥ 此长度的文本才跑 L2（短文本仅 L1）。 */
    static final int DEEP_CHECK_MIN_CHARS = 200;

    private static final Logger log = LoggerFactory.getLogger(ContentSafetyAiChecker.class);
    private static final int MAX_OUTPUT_TOKENS = 1024;

    private final AiExecutionService executions;
    private final TextCompletionClient textClient;
    private final PlatformModelConfig platformDefaults;
    private final PlatformConcurrencyLimiter concurrencyLimiter;
    private final DeepCheckJsonParser parser = new DeepCheckJsonParser();

    public ContentSafetyAiChecker(
            AiExecutionService executions,
            TextCompletionClient textClient,
            PlatformModelConfig platformDefaults,
            PlatformConcurrencyLimiter concurrencyLimiter) {
        this.executions = executions;
        this.textClient = textClient;
        this.platformDefaults = platformDefaults;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /**
     * 深检：empty = 不可用/未配置/失败（调用方保持 L1 结果与 {@code deepCheck:false}，绝不阻断生成）。
     * 输入经 rubric prompt（类目清单 + 语境判定 + 结构化 JSON），输出折叠为 deep findings。
     */
    public Mono<List<Finding>> deepCheck(ServerWebExchange exchange, String text) {
        if (text == null || text.length() < DEEP_CHECK_MIN_CHARS) {
            return Mono.empty();
        }
        List<ChatMessage> messages = List.of(
                ChatMessage.system(rubric()),
                ChatMessage.user("待检查文本：\n" + text));
        int estimatedInput = messages.stream()
                .mapToInt(ContentSafetyAiChecker::estimatedMessageBytes)
                .sum();
        // allowFallback=true：个人 BYOK 不含 content_safety（D4 白名单）→ 恒回落平台模型；
        // 平台未配置 → denied（no_platform_model）= L1-only 降级路径。
        return executions.prepareExecution(
                        exchange, "content_safety", null, estimatedInput, MAX_OUTPUT_TOKENS, true)
                .flatMap(result -> result.allowed()
                        ? executePrepared(result.context(), messages)
                        : Mono.<List<Finding>>empty());   // denied（未配置模型等）= 降级，不是错误
    }

    private Mono<List<Finding>> executePrepared(
            AiExecutionService.ExecutionContext context, List<ChatMessage> messages) {
        String bearer = context.provider().isPlatform()
                ? platformDefaults.apiKey() : context.decryptedKey();
        return Mono.usingWhen(
                        Mono.just(context),
                        ignored -> Mono.usingWhen(
                                concurrencyLimiter.acquire(context.provider()),
                                lease -> textClient.completeMessages(
                                        context.provider().baseUrl(), bearer, context.provider().model(),
                                        messages, MAX_OUTPUT_TOKENS, context.provider().isByok()),
                                PlatformConcurrencyLimiter.Lease::release,
                                (lease, error) -> lease.release(),
                                PlatformConcurrencyLimiter.Lease::release)
                                .map(completion -> executions.normalizeProviderUsage(context, completion))
                                .flatMap(completion -> executions.settleSuccess(
                                                context, completion.inputTokens(), completion.outputTokens(), 0, 0)
                                        .then(Mono.defer(() -> {
                                            // 坏 JSON = 深检结论不可信 → 视为深检未发生（empty → deepCheck:false），
                                            // 不把「模型跑过但读不懂」伪装成「深检通过」（ADR-D16 D5 降级语义）。
                                            List<Finding> parsed = parser.parse(completion.content());
                                            return parsed == null ? Mono.empty() : Mono.just(parsed);
                                        })))
                                .onErrorResume(error -> {
                                    log.warn("content safety deep check model call failed run={}",
                                            context.runId(), error);
                                    return executions.handleFailure(context,
                                                    error.getMessage() == null
                                                            ? "deep check failed" : error.getMessage())
                                            .then(Mono.<List<Finding>>empty());   // 深检失败不炸生成流
                                }),
                        ignored -> Mono.empty(),
                        (ignored, error) -> Mono.empty(),
                        ignored -> executions.handleCancellation(context).then())
                .onErrorResume(error -> {
                    log.warn("content safety deep check unavailable", error);
                    return Mono.empty();
                });
    }

    /**
     * rubric：类目清单 + 语境判定标准 + 固定 JSON 输出。L2 只报 L1 词库难以捕捉的**语境级**问题，
     * 输出按 severity 排序责任在前端。
     */
    static String rubric() {
        return """
                你是商业内容合规审查助手。检查下面这篇章草/种草文案是否存在以下类别的问题（按语境判断，\
                不做逐字词库匹配）：
                - absolute_claims：广告法极限词/夸大宣传（如隐含「全网最好」「第一名」语义的表达、无依据的\
                「第一」「顶级」暗示）
                - false_promises：违规承诺/夸大保证（保本稳赚、包治百病、绝对见效等语境）
                - diversion：站外导流联系方式（微信号/电话/引导私聊转移）
                - illegal：涉嫌违法内容（违禁品、赌博、代考等）
                判定标准：只报有明确语境证据的问题；正常的主观评价（「个人觉得好吃」）不算夸大。\
                没有问题就返回空数组。仅返回 JSON：
                {"findings":[{"category":"...","severity":"high|medium|low","match":"原文中命中的短语",\
                "advice":"20 字以内修改建议"}]}""";
    }

    private static int estimatedMessageBytes(ChatMessage message) {
        int bytes = message.content() == null ? 0
                : message.content().getBytes(StandardCharsets.UTF_8).length;
        return bytes;
    }

    /** 深检 JSON 解析（服务本地实例——intelligence 无全局 ObjectMapper bean）。 */
    static final class DeepCheckJsonParser {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();

        /**
         * @return findings（可为空数组=模型判定无问题）；{@code null} = 输出不可解析（调用方视为深检未发生）。
         */
        List<Finding> parse(String raw) {
            try {
                String stripped = stripCodeFence(raw);
                var root = mapper.readTree(stripped);
                var findingsNode = root.path("findings");
                if (!findingsNode.isArray()) {
                    return null;
                }
                List<Finding> findings = new ArrayList<>();
                for (var node : findingsNode) {
                    String category = node.path("category").asText("");
                    String match = node.path("match").asText("");
                    if (category.isBlank() || match.isBlank()) {
                        continue;
                    }
                    findings.add(new Finding(
                            category,
                            node.path("severity").asText("medium"),
                            match,
                            -1,   // L2 无精确位置
                            node.path("advice").asText(""),
                            true));
                }
                return findings;
            } catch (Exception error) {
                log.warn("content safety deep check output unparseable", error);
                return null;
            }
        }

        private static String stripCodeFence(String raw) {
            String stripped = raw == null ? "" : raw.trim();
            if (stripped.startsWith("```")) {
                int start = stripped.indexOf('\n');
                int end = stripped.lastIndexOf("```");
                if (start >= 0 && end > start) {
                    stripped = stripped.substring(start + 1, end).trim();
                }
            }
            return stripped;
        }
    }
}
