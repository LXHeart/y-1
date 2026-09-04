package com.grassland.trust.judge;

import com.grassland.trust.security.TrustServiceAssertionIssuer;
import com.grassland.http.ManagedWebClientFactory;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * trust→marketplace 声誉查询客户端（GL-P2-TRUST-001：reputation-based judge eligibility）。
 *
 * <p>以现签 {@code principal=trust} 服务断言调 marketplace 内部端点，取回推荐官的声誉等级代码。
 * 用于审判官入池时的资格判定（Lv5 绑定审判官资格）。
 *
 * <p>所有非 200、空/畸形信封、账号不匹配和字段不一致均作为上游失败返回；报名调用方据此 fail-closed。
 */
@Component
public class MarketplaceReputationClient {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceReputationClient.class);

    private final WebClient webClient;
    private final TrustServiceAssertionIssuer issuer;
    private final String headerName;
    private final Duration timeout;

    public MarketplaceReputationClient(
            TrustServiceAssertionIssuer issuer,
            @Value("${marketplace.service.base-url:http://marketplace-service:8083}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName,
            @Value("${marketplace.service.reputation-timeout-seconds:3}") long timeoutSeconds) {
        this.issuer = issuer;
        this.headerName = headerName;
        long boundedTimeoutSeconds = Math.max(1, Math.min(timeoutSeconds, 30));
        this.timeout = Duration.ofSeconds(boundedTimeoutSeconds);
        this.webClient = ManagedWebClientFactory.create(
                MarketplaceReputationClient.class, baseUrl, timeout);
    }

    /** marketplace 的有效等级及审判资格判定。 */
    public record LevelResult(
            String accountId,
            String effectiveLevel,
            int levelNumber,
            boolean judgeEligible,
            long policyVersion,
            long completedCount) {

        /** 既有 5 参调用方兼容：完成数缺失 = -1（Lv4 报名的 ≥20 任务门槛按不满足处理）。 */
        public LevelResult(String accountId, String effectiveLevel, int levelNumber, boolean judgeEligible,
                           long policyVersion) {
            this(accountId, effectiveLevel, levelNumber, judgeEligible, policyVersion, -1L);
        }

        public boolean isEligibleLv5Judge() {
            return judgeEligible && levelNumber == 5 && "Lv5".equals(effectiveLevel);
        }
    }

    /**
     * 查询推荐官声誉等级。
     *
     * @param accountId 推荐官账号 ID
     * @return 经严格验证的声誉等级结果
     */
    public Mono<LevelResult> getLevel(String accountId) {
        return webClient.get()
                .uri("/internal/marketplace/reputation/{accountId}/level", accountId)
                .header(headerName, issuer.issueService("grassland-marketplace"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.debug("reputation-level HTTP {} accountId={}", code, accountId);
                    if (code == 200) {
                        return resp.bodyToMono(LevelResponse.class)
                                .switchIfEmpty(Mono.error(new ReputationException("empty reputation response")))
                                .map(body -> validate(accountId, body));
                    }
                    return resp.releaseBody().then(Mono.error(
                            new ReputationException("reputation endpoint returned HTTP " + code)));
                })
                .timeout(timeout)
                .onErrorMap(error -> error instanceof ReputationException
                        ? error
                        : new ReputationException("invalid reputation response", error));
    }

    /** 用于解码 marketplace 信封。 */
    private record LevelResponse(boolean success, LevelData data) {}

    private record LevelData(
            String accountId,
            String effectiveLevel,
            Integer levelNumber,
            Boolean judgeEligible,
            Long policyVersion,
            Long completedCount) {}

    private static LevelResult validate(String requestedAccountId, LevelResponse body) {
        if (body == null || !body.success() || body.data() == null) {
            throw new ReputationException("invalid reputation envelope");
        }
        LevelData data = body.data();
        if (!requestedAccountId.equals(data.accountId())) {
            throw new ReputationException("reputation account mismatch");
        }
        int number = data.levelNumber() == null ? -1 : data.levelNumber();
        String expectedLevel = number >= 1 && number <= 5 ? "Lv" + number : null;
        if (expectedLevel == null || !expectedLevel.equals(data.effectiveLevel())
                || data.judgeEligible() == null
                || data.judgeEligible() != (number == 5)
                || data.policyVersion() == null || data.policyVersion() < 0) {
            throw new ReputationException("invalid reputation data");
        }
        // 任务书 #74 卡 E：completedCount 为可选新字段（旧版 marketplace 无此列 → -1），缺失不拒响应。
        long completed = data.completedCount() == null ? -1L : data.completedCount();
        return new LevelResult(data.accountId(), data.effectiveLevel(), number,
                data.judgeEligible(), data.policyVersion(), completed);
    }

    /**
     * 任务书 #74 卡 D：查询审判官各平台完成履约数（口径=confirmed 履约按任务 platform 聚合）。
     * 垂类硬配额抽签（涉案平台完成 ≥3 的熟手席 ≥4/7）用；上游失败由调用方 fail-closed。
     */
    public Mono<PlatformCompletions> getPlatformCompletions(String accountId) {
        return webClient.get()
                .uri("/internal/marketplace/reputation/{accountId}/platform-completions", accountId)
                .header(headerName, issuer.issueService("grassland-marketplace"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.debug("platform-completions HTTP {} accountId={}", code, accountId);
                    if (code == 200) {
                        return resp.bodyToMono(PlatformCompletionsResponse.class)
                                .switchIfEmpty(Mono.error(new ReputationException("empty completions response")))
                                .map(body -> validateCompletions(accountId, body));
                    }
                    return resp.releaseBody().then(Mono.error(
                            new ReputationException("platform-completions endpoint returned HTTP " + code)));
                })
                .timeout(timeout)
                .onErrorMap(error -> error instanceof ReputationException
                        ? error
                        : new ReputationException("invalid platform-completions response", error));
    }

    /** 平台完成数聚合结果（不可变，供分池抽签判定熟手席）。 */
    public record PlatformCompletions(String accountId, java.util.Map<String, Integer> completionsByPlatform) {
        public static final PlatformCompletions EMPTY = new PlatformCompletions("", java.util.Map.of());

        public int completionsOf(String platform) {
            if (platform == null || completionsByPlatform() == null) {
                return 0;
            }
            return completionsByPlatform().getOrDefault(platform, 0);
        }
    }

    private static PlatformCompletions validateCompletions(String requestedAccountId,
                                                           PlatformCompletionsResponse body) {
        if (body == null || !body.success() || body.data() == null
                || !requestedAccountId.equals(body.data().accountId())) {
            throw new ReputationException("invalid platform-completions envelope");
        }
        java.util.Map<String, Integer> map = new java.util.LinkedHashMap<>();
        if (body.data().completions() != null) {
            body.data().completions().forEach((platform, count) -> {
                if (platform != null && !platform.isBlank() && count != null && count >= 0) {
                    map.put(platform, count);
                }
            });
        }
        return new PlatformCompletions(requestedAccountId, java.util.Map.copyOf(map));
    }

    private record PlatformCompletionsResponse(boolean success, PlatformCompletionsData data) {}

    private record PlatformCompletionsData(String accountId, java.util.Map<String, Integer> completions) {}

    /** marketplace 调用失败（transport/未知状态）。 */
    public static final class ReputationException extends RuntimeException {
        public ReputationException(String message) {
            super(message);
        }

        public ReputationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
