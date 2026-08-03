package com.grassland.trust.judge;

import com.grassland.trust.security.TrustServiceAssertionIssuer;
import java.util.Optional;
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
 * <p>返回 {@code Optional}：有值=存在（含 level）；空=账号无声誉记录（未接任务）/不存在(404)，
 * 调用方可决定是否允许入池（当前策略：无声誉记录视为 Lv1，允许入池但 tier=1）。
 */
@Component
public class MarketplaceReputationClient {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceReputationClient.class);

    private final WebClient webClient;
    private final TrustServiceAssertionIssuer issuer;
    private final String headerName;

    public MarketplaceReputationClient(
            TrustServiceAssertionIssuer issuer,
            @Value("${marketplace.service.base-url:http://marketplace-service:8083}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /** 声誉等级结果：成功时携带 level 代码（1=Lv1, 2=Lv2, ..., 5=Lv5）。 */
    public record LevelResult(String accountId, int level) {}

    /**
     * 查询推荐官声誉等级。
     *
     * @param accountId 推荐官账号 ID
     * @return 声誉等级结果；账号无声誉记录时返回 empty
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
                                .map(body -> new LevelResult(body.data().accountId(), body.data().level()));
                    }
                    if (code == 404) {
                        log.info("reputation-level 404 accountId={} (无声誉记录)", accountId);
                        return Mono.empty();  // 无声誉记录视为 Lv1，允许入池
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<LevelResult>error(
                                    new ReputationException("get level failed: HTTP " + code + ": " + b)));
                });
    }

    /** 用于解码 marketplace 信封。 */
    private record LevelResponse(boolean success, LevelData data) {}

    private record LevelData(String accountId, int level) {}

    /** marketplace 调用失败（transport/未知状态）。 */
    public static final class ReputationException extends RuntimeException {
        public ReputationException(String message) {
            super(message);
        }
    }
}
