package com.grassland.intelligence.douyin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 抖音热点抓取（移植 legacy {@code server/src/services/douyin-hot.service.ts}）。
 *
 * <p>GET 上游（强制 {@code ?encoding=json}）→ 解析 {@code {code,message,data:[...]}}；
 * {@code code!==200}→502、{@code data} 非数组→502；逐项归一化（title 缺失丢弃、{@code link/cover} 经
 * {@link DouyinHosts} 受信校验未过置空）、按 {@code limit} 截断后重排 rank。超时→504，其余错误→502。
 * 无 auth/credits（公开热点）。
 */
@Service
public class DouyinHotItemsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE = "60sapi";
    private static final String TIMEOUT_MESSAGE = "获取抖音热点超时，请稍后再试";
    private static final String UPSTREAM_MESSAGE = "获取抖音热点失败，请稍后再试";
    private static final String INVALID_MESSAGE = "抖音热点服务返回了无效数据";

    private final DouyinHotItemsProperties props;
    private final WebClient client;

    public DouyinHotItemsService(DouyinHotItemsProperties props) {
        this.props = props;
        this.client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(512 * 1024))
                .build();
    }

    public Mono<List<DouyinHotItem>> load() {
        return client.get()
                .uri(buildRequestUrl(props.apiBaseUrl()))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                // 用 Mono.timeout 而非 HttpClient.responseTimeout：前者稳定抛 java.util.concurrent.TimeoutException，
                // 后者的异常类型随 reactor-netty 版本变化，难以可靠映射到 504。
                .timeout(props.apiTimeout())
                .map(this::parse)
                .onErrorResume(DouyinHotItemsService::isTimeout,
                        e -> Mono.error(new IntelligenceException(504, TIMEOUT_MESSAGE)))
                .onErrorResume(e -> e instanceof IntelligenceException
                        ? Mono.error(e)
                        : Mono.error(new IntelligenceException(502, UPSTREAM_MESSAGE)));
    }

    private String buildRequestUrl(String baseUrl) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl);
        if (!builder.build().getQueryParams().containsKey("encoding")) {
            builder.queryParam("encoding", "json");
        }
        return builder.build().toUriString();
    }

    private List<DouyinHotItem> parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IntelligenceException(502, INVALID_MESSAGE);
        }

        JsonNode code = root.path("code");
        if (!code.isNumber() || code.asInt() != 200) {
            throw new IntelligenceException(502, UPSTREAM_MESSAGE);
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new IntelligenceException(502, INVALID_MESSAGE);
        }

        List<DouyinHotItem> trusted = new ArrayList<>();
        for (JsonNode entry : data) {
            DouyinHotItem item = normalize(entry, trusted.size() + 1);
            if (item != null) {
                trusted.add(item);
            }
        }

        int limit = Math.max(props.limit(), 1);
        List<DouyinHotItem> sliced = trusted.size() > limit ? trusted.subList(0, limit) : trusted;
        List<DouyinHotItem> result = new ArrayList<>(sliced.size());
        for (int i = 0; i < sliced.size(); i++) {
            DouyinHotItem it = sliced.get(i);
            result.add(new DouyinHotItem(i + 1, it.title(), it.hotValue(), it.url(), it.cover(), SOURCE));
        }
        return result;
    }

    private static DouyinHotItem normalize(JsonNode entry, int rank) {
        String title = optionalString(entry.path("title"));
        if (title == null) {
            return null;
        }
        return new DouyinHotItem(
                rank,
                title,
                hotValue(entry.path("hot_value")),
                trustedUrl(entry.path("link"), DouyinHosts::isAllowedPageHost),
                trustedUrl(entry.path("cover"), DouyinHosts::isAllowedVideoHost),
                SOURCE);
    }

    private static String optionalString(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** 对齐 legacy {@code normalizeHotValue}：数字→字符串；字符串→trim；空→null。 */
    private static String hotValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? Long.toString(node.asLong()) : Double.toString(node.asDouble());
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /** 对齐 legacy {@code normalizeTrustedUrl}：https + 受信主机，否则 null（丢弃）。 */
    private static String trustedUrl(JsonNode node, Predicate<String> hostCheck) {
        String value = optionalString(node);
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !hostCheck.test(uri.getHost())) {
                return null;
            }
            return value;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
