package com.grassland.intelligence.articleimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Bing 图片 HTML/JSON 搜索客户端，兼容 legacy 的 HTTPS 过滤和解析顺序。 */
@Component
public class BingImageSearchClient {

    private final String endpoint;
    private final Duration timeout;
    private final IntSupplier offsetSupplier;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public BingImageSearchClient(
            @Value("${article-images.search.endpoint:https://www.bing.com/images/search}") String endpoint,
            @Value("${article-images.search.timeout-ms:15000}") long timeoutMs) {
        this(endpoint, Duration.ofMillis(timeoutMs), () -> ThreadLocalRandom.current().nextInt(1, 31));
    }

    BingImageSearchClient(String endpoint, Duration timeout, IntSupplier offsetSupplier) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.offsetSupplier = offsetSupplier;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(timeout)
                .followRedirect(true);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    public Mono<List<ImageSearchResult>> search(String keywords, int count) {
        String uri = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("q", keywords)
                .queryParam("first", offsetSupplier.getAsInt())
                .build().encode().toUriString();
        return webClient.get().uri(uri)
                .header("User-Agent", "Mozilla/5.0 (compatible; ArticleBot/1.0)")
                .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return Mono.error(new IntelligenceException(
                                response.statusCode().is5xxServerError() ? 502 : 400,
                                "搜图失败，请稍后重试"));
                    }
                    MediaType type = response.headers().contentType().orElse(MediaType.TEXT_HTML);
                    return response.bodyToMono(String.class).map(body -> parse(body, type, count));
                })
                .timeout(timeout)
                .onErrorMap(error -> {
                    if (error instanceof IntelligenceException) {
                        return error;
                    }
                    if (isTimeout(error)) {
                        return new IntelligenceException(504, "搜图超时，请稍后重试");
                    }
                    return new IntelligenceException(502, "搜图失败，请稍后重试");
                });
    }

    private List<ImageSearchResult> parse(String body, MediaType type, int count) {
        if (MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
            List<ImageSearchResult> jsonResults = parseJson(body, count);
            if (!jsonResults.isEmpty()) {
                return jsonResults;
            }
        }
        Document document = Jsoup.parse(body);
        List<ImageSearchResult> results = parseMetadata(document, count);
        if (results.isEmpty()) {
            results = parseFallback(document, count);
        }
        if (results.isEmpty()) {
            throw new IntelligenceException(502, "搜图失败，请稍后重试");
        }
        return List.copyOf(results);
    }

    private List<ImageSearchResult> parseJson(String body, int count) {
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray()) {
                return List.of();
            }
            List<ImageSearchResult> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : root) {
                add(results, seen, result(
                        text(item, "url"), text(item, "thumbnailUrl"), text(item, "sourceUrl"),
                        text(item, "description"), integer(item, "width"), integer(item, "height")), count);
            }
            return results;
        } catch (Exception error) {
            return List.of();
        }
    }

    private List<ImageSearchResult> parseMetadata(Document document, int count) {
        List<ImageSearchResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element element : document.select(".iusc[m]")) {
            try {
                JsonNode item = mapper.readTree(element.attr("m"));
                add(results, seen, result(
                        text(item, "murl"), text(item, "turl"), text(item, "purl"),
                        firstNonBlank(text(item, "desc"), text(item, "title")),
                        integer(item, "w"), integer(item, "h")), count);
            } catch (Exception ignored) {
                // skip malformed metadata entries
            }
            if (results.size() >= count) {
                break;
            }
        }
        return results;
    }

    private List<ImageSearchResult> parseFallback(Document document, int count) {
        List<ImageSearchResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element container : document.select(".iusc, .imgpt, .dgControl, .img_cont, .mimg")) {
            Element image = "img".equals(container.tagName()) ? container : container.selectFirst("img");
            if (image == null) {
                continue;
            }
            Element link = image.closest("a") != null ? image.closest("a") : container.closest("a");
            add(results, seen, result(
                    firstNonBlank(image.attr("data-src"), image.attr("src")),
                    firstNonBlank(image.attr("src"), image.attr("data-src")),
                    link == null ? null : link.attr("href"),
                    firstNonBlank(image.attr("alt"), container.attr("aria-label")),
                    parseInt(image.attr("width")), parseInt(image.attr("height"))), count);
            if (results.size() >= count) {
                break;
            }
        }
        return results;
    }

    private static void add(
            List<ImageSearchResult> results, Set<String> seen, ImageSearchResult candidate, int count) {
        if (candidate == null || results.size() >= count || !seen.add(candidate.url())) {
            return;
        }
        results.add(candidate);
    }

    private static ImageSearchResult result(
            String rawUrl, String rawThumbnail, String rawSource,
            String description, Integer width, Integer height) {
        String url = https(rawUrl);
        String thumbnail = firstNonBlank(https(rawThumbnail), url);
        if (url == null || thumbnail == null) {
            return null;
        }
        return new ImageSearchResult(url, thumbnail, https(rawSource), blankToNull(description), width, height);
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String https(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) ? uri.toString() : null;
        } catch (Exception error) {
            return null;
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isTextual() ? blankToNull(value.asText()) : null;
    }

    private static Integer integer(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isNumber() ? value.intValue() : null;
    }

    private static Integer parseInt(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return blankToNull(first) != null ? blankToNull(first) : blankToNull(second);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
