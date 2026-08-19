package com.grassland.intelligence.ai;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 用户 BYOK base-url 的执行侧固定连接工厂（GL-P3-AI-001；settings 归一化遗留的「下一切片」）。
 *
 * <p>统一 {@code TextCompletionClient} 与 {@code ModelListingService}（listModels/verifyModel）
 * 的出站口径：先经 {@link ProviderUrlGuard#validateByokForExecution}（HTTPS、无凭据、全部
 * DNS 解析结果均为公网、与已固定集合一致），再用固定地址解析器建立连接——校验与连接之间
 * 不再有系统 DNS 参与，域名解析型内网目标与 DNS rebinding 均被拒绝。
 */
public final class PinnedByokClients {

    private PinnedByokClients() {}

    /**
     * 为用户提供的 base-url 构建固定连接的 WebClient。
     *
     * @throws IllegalArgumentException 校验失败（调用方应映射为 4xx，指向用户配置而非上游故障）
     */
    public static WebClient forBaseUrl(String baseUrl, DnsPinningResolver dnsPinning) {
        return forBaseUrl(baseUrl, dnsPinning, Duration.ofSeconds(10), 2 * 1024 * 1024);
    }

    /** Builds a DNS-pinned client with bounded connection, response time, and in-memory body size. */
    public static WebClient forBaseUrl(
            String baseUrl,
            DnsPinningResolver dnsPinning,
            Duration responseTimeout,
            int maxResponseBytes) {
        if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        URI target = ProviderUrlGuard.validateByokForExecution(baseUrl, dnsPinning);
        List<InetAddress> pinnedAddresses = dnsPinning.getPinnedAddresses(target.getHost()).stream()
                .sorted(Comparator.comparing(InetAddress::getHostAddress))
                .toList();
        if (pinnedAddresses.isEmpty()) {
            throw new IllegalArgumentException("BYOK provider 没有可用的固定地址");
        }
        HttpClient httpClient = HttpClient.create()
                .resolver(new PinnedAddressResolverGroup(target.getHost(), pinnedAddresses))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(responseTimeout)
                .compress(true)
                .followRedirect(false);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes))
                        .build())
                .baseUrl(withTrailingSlash(baseUrl))
                .build();
    }

    private static String withTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
