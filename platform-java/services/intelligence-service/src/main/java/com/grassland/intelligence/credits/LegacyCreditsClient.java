package com.grassland.intelligence.credits;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 经 legacy 内部端点扣费（草场 intelligence Slice 1 过渡实现）。
 *
 * <p>legacy 不识别 {@code X-Grassland-Identity} 断言，故走共享密钥 {@code X-Internal-Key}（两端同
 * {@code INTERNAL_API_KEY}）。直连 legacy 容器（{@code credits.legacy.base-url}），不经 edge-bff
 * （避免 intelligence↔edge-bff 循环；与 marketplace→finance 直连同模式）。
 * 退役：草场 {@code usage-account} 落地后，此实现整体替换为本地用量记账。
 */
@Component
public class LegacyCreditsClient implements CreditsClient {

    private final WebClient webClient;
    private final String consumePath;
    private final String internalKey;

    public LegacyCreditsClient(@Value("${credits.legacy.base-url:http://backend:3000}") String baseUrl,
                               @Value("${credits.legacy.consume-path:/api/internal/credits/consume}") String consumePath,
                               @Value("${credits.legacy.internal-key:}") String internalKey) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.consumePath = consumePath;
        this.internalKey = internalKey;
    }

    @Override
    public Mono<Void> consume(String accountId, CreditFeature feature) {
        return webClient.post().uri(consumePath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Key", internalKey)
                .bodyValue(Map.of("accountId", accountId, "feature", feature.key()))
                .retrieve()
                .onStatus(s -> s.value() == 402, r -> Mono.error(new InsufficientCreditsException()))
                .onStatus(s -> s.is4xxClientError(),
                        r -> Mono.error(new IntelligenceException(400, "积分扣减请求无效")))
                .onStatus(s -> s.is5xxServerError(),
                        r -> Mono.error(new IntelligenceException(502, "积分服务暂不可用")))
                .bodyToMono(Void.class);
    }
}
