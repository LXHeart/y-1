package com.grassland.trust.judge;

import com.grassland.trust.security.TrustServiceAssertionIssuer;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
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
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(timeout.toMillis()))
                .responseTimeout(timeout);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(baseUrl)
                .build();
    }

    /** marketplace 的有效等级及审判资格判定。 */
    public record LevelResult(
            String accountId,
            String effectiveLevel,
            int levelNumber,
            boolean judgeEligible,
            long policyVersion) {

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
            Long policyVersion) {}

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
        return new LevelResult(data.accountId(), data.effectiveLevel(), number,
                data.judgeEligible(), data.policyVersion());
    }

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
