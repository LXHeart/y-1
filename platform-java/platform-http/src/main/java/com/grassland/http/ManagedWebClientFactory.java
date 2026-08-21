package com.grassland.http;

import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** Shared Reactor Netty policy for internal and configured-external HTTP clients. */
public final class ManagedWebClientFactory {

    private static final int CONNECT_TIMEOUT_MS = positiveInt("GRASSLAND_HTTP_CONNECT_TIMEOUT_MS", 3_000);
    private static final int RESPONSE_TIMEOUT_MS = positiveInt("GRASSLAND_HTTP_RESPONSE_TIMEOUT_MS", 10_000);
    private static final int MAX_CONNECTIONS = positiveInt("GRASSLAND_HTTP_MAX_CONNECTIONS", 50);
    private static final int MAX_RESPONSE_BYTES = positiveInt("GRASSLAND_HTTP_MAX_RESPONSE_BYTES", 2 * 1024 * 1024);
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofMillis(
            positiveInt("GRASSLAND_HTTP_ACQUIRE_TIMEOUT_MS", 5_000));
    private static final Duration MAX_IDLE_TIME = Duration.ofSeconds(
            positiveInt("GRASSLAND_HTTP_MAX_IDLE_SECONDS", 30));
    private static final Duration MAX_LIFE_TIME = Duration.ofSeconds(
            positiveInt("GRASSLAND_HTTP_MAX_LIFE_SECONDS", 300));
    private static final boolean METRICS_ENABLED = Boolean.parseBoolean(
            value("GRASSLAND_HTTP_METRICS_ENABLED", "true"));

    private static final Map<String, ConnectionProvider> PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<Integer, ExchangeStrategies> EXCHANGE_STRATEGIES = new ConcurrentHashMap<>();

    private ManagedWebClientFactory() {}

    public static WebClient create(Class<?> owner, String baseUrl) {
        return builder(owner, Duration.ofMillis(RESPONSE_TIMEOUT_MS)).baseUrl(requireBaseUrl(baseUrl)).build();
    }

    public static WebClient create(Class<?> owner, String baseUrl, Duration responseTimeout) {
        return builder(owner, responseTimeout).baseUrl(requireBaseUrl(baseUrl)).build();
    }

    public static WebClient create(Class<?> owner) {
        return builder(owner, Duration.ofMillis(RESPONSE_TIMEOUT_MS)).build();
    }

    public static WebClient.Builder builder(Class<?> owner, Duration responseTimeout) {
        return builder(owner, responseTimeout, MAX_RESPONSE_BYTES);
    }

    public static WebClient.Builder builder(
            Class<?> owner, Duration responseTimeout, int maxResponseBytes) {
        return builder(owner, Duration.ofMillis(CONNECT_TIMEOUT_MS), responseTimeout, maxResponseBytes);
    }

    public static WebClient.Builder builder(
            Class<?> owner, Duration connectTimeout, Duration responseTimeout, int maxResponseBytes) {
        return builder(owner, connectTimeout, responseTimeout, maxResponseBytes, null);
    }

    /**
     * 带 Netty 地址解析器的构建入口：调用方（DNS pinning 场景）以固定地址解析替换系统 DNS，
     * 关闭「URL 校验后、连接前」的 DNS rebinding TOCTOU 窗口；{@code resolver} 为 null 时与
     * 四参版完全一致。
     */
    public static WebClient.Builder builder(
            Class<?> owner, Duration connectTimeout, Duration responseTimeout, int maxResponseBytes,
            io.netty.resolver.AddressResolverGroup<?> resolver) {
        String clientName = normalizeName(owner.getSimpleName());
        Duration boundedConnectTimeout = requirePositive(connectTimeout, "connectTimeout");
        Duration boundedTimeout = requirePositive(responseTimeout, "responseTimeout");
        int boundedResponseBytes = requirePositive(maxResponseBytes, "maxResponseBytes");
        ConnectionProvider provider = PROVIDERS.computeIfAbsent(clientName,
                ignored -> ConnectionProvider.builder("grassland-" + clientName)
                        .maxConnections(MAX_CONNECTIONS)
                        .pendingAcquireTimeout(ACQUIRE_TIMEOUT)
                        .maxIdleTime(MAX_IDLE_TIME)
                        .maxLifeTime(MAX_LIFE_TIME)
                        .metrics(METRICS_ENABLED)
                        .build());
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(boundedConnectTimeout.toMillis()))
                .responseTimeout(boundedTimeout)
                .compress(true)
                .followRedirect(false);
        if (resolver != null) {
            httpClient = httpClient.resolver(resolver);
        }
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(EXCHANGE_STRATEGIES.computeIfAbsent(
                        boundedResponseBytes,
                        size -> ExchangeStrategies.builder()
                                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                                .build()));
    }

    /** Validates an operator-configured endpoint before a request is attempted. */
    public static URI requireConfiguredEndpoint(String endpoint, boolean allowInsecureHttp, String allowedHosts) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("outbound endpoint is not a valid URI", error);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || allowInsecureHttp && "http".equals(scheme))) {
            throw new IllegalArgumentException("outbound endpoint must use HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("outbound endpoint must have a host and no user info");
        }
        Set<String> allowlist = Arrays.stream((allowedHosts == null ? "" : allowedHosts).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!allowlist.isEmpty() && !allowlist.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("outbound endpoint host is not allowlisted");
        }
        return uri;
    }

    static int providerCount() {
        return PROVIDERS.size();
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        return baseUrl.trim();
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String normalizeName(String value) {
        String normalized = value.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9-]", "-")
                .toLowerCase(Locale.ROOT);
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }

    private static int positiveInt(String name, int fallback) {
        String configured = value(name, Integer.toString(fallback));
        try {
            int parsed = Integer.parseInt(configured);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String value(String name, String fallback) {
        String system = System.getProperty(name);
        if (system != null && !system.isBlank()) {
            return system.trim();
        }
        String environment = System.getenv(name);
        return environment == null || environment.isBlank() ? fallback : environment.trim();
    }
}
