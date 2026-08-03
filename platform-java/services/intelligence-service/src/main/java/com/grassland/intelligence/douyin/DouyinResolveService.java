package com.grassland.intelligence.douyin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 抖音视频解析服务（移植 legacy {@code server/src/services/douyin-resolve.service.ts}）。
 *
 * <p>流程：从分享文本抽取抖音 URL → 跟随重定向抓页面 HTML → 解析页面 JSON 提取视频信息。
 * 首期简化实现：支持基础 URL 解析和重定向，完整 JSON 解析后续扩展。
 *
 * <p>SSRF 边界：page host 与 video host 均经 {@link DouyinHosts} 静态白名单校验。
 * 超时→504，其余上游/解析错误→502。
 */
@Service
public class DouyinResolveService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_REDIRECTS = 5;
    private static final String PAGE_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final String PAGE_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";

    // 抖音视频 ID 匹配模式
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("video/(\\d+)");
    private static final Pattern MODAL_ID_PATTERN = Pattern.compile("modal_id=(\\d+)");
    private static final Pattern AWEME_ID_PATTERN = Pattern.compile("aweme_id=(\\d+)");

    // URL 候选匹配（从分享文本中提取 URL）
    private static final Pattern URL_CANDIDATES = Pattern.compile("https://[^\\s]+");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[),.;!?]+$");

    private final DouyinFetchProperties props;
    private final WebClient client;

    public DouyinResolveService(DouyinFetchProperties props) {
        this.props = props;
        // followRedirect(false)：手动处理重定向，每跳经 page host 守卫复验（SSRF 边界）
        this.client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    /**
     * 解析分享文本为 {@link DouyinSourceMaterial}。
     * 无可用 page URL→400；页面解析失败→502；上游超时→504。
     */
    public Mono<DouyinSourceMaterial> resolve(String input) {
        return Mono.fromCallable(() -> extractEntryUrl(input))
                .flatMap(this::followRedirects)
                .flatMap(this::parsePage);
    }

    /**
     * 从分享文本中提取第一个有效的抖音页面 URL（对齐 legacy）。
     */
    public static boolean containsAllowedPageUrl(String input) {
        if (input == null) {
            return false;
        }
        Matcher matcher = URL_CANDIDATES.matcher(input);
        while (matcher.find()) {
            String candidate = TRAILING_PUNCT.matcher(matcher.group()).replaceAll("");
            if (isAllowedPageUrl(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取分享文本中的入口 URL。
     */
    private String extractEntryUrl(String input) {
        Matcher matcher = URL_CANDIDATES.matcher(input);
        while (matcher.find()) {
            String candidate = TRAILING_PUNCT.matcher(matcher.group()).replaceAll("");
            if (isAllowedPageUrl(candidate)) {
                return candidate;
            }
        }
        throw new IntelligenceException(400, "请输入包含抖音链接的分享文本或链接");
    }

    /**
     * 校验 URL 是否为允许的抖音页面。
     */
    private static boolean isAllowedPageUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return "https".equalsIgnoreCase(scheme) && DouyinHosts.isAllowedPageHost(host);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 跟随重定向获取最终页面 URL（SSRF 边界校验每跳）。
     */
    private Mono<String> followRedirects(String entryUrl) {
        return followRedirects(entryUrl, 0, new ArrayList<>());
    }

    private Mono<String> followRedirects(String url, int depth, List<String> visited) {
        if (depth >= MAX_REDIRECTS) {
            throw new IntelligenceException(502, "重定向次数过多");
        }
        if (visited.contains(url)) {
            throw new IntelligenceException(502, "检测到重定向循环");
        }
        visited.add(url);

        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IntelligenceException(400, "链接格式无效");
        }

        String host = uri.getHost();
        if (!DouyinHosts.isAllowedPageHost(host)) {
            throw new IntelligenceException(400, "不受信任的链接来源");
        }

        return client.get()
                .uri(uri)
                .headers(headers -> {
                    headers.set(HttpHeaders.ACCEPT, PAGE_ACCEPT);
                    headers.set(HttpHeaders.ACCEPT_LANGUAGE, PAGE_ACCEPT_LANGUAGE);
                    headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                })
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();
                    if (status.is3xxRedirection()) {
                        String location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                        if (location == null) {
                            throw new IntelligenceException(502, "重定向地址缺失");
                        }
                        // 处理相对路径重定向
                        String nextUrl = URI.create(url).resolve(location).toString();
                        return followRedirects(nextUrl, depth + 1, visited);
                    }
                    if (!status.is2xxSuccessful()) {
                        throw new IntelligenceException(502, "页面请求失败：" + status.value());
                    }
                    // 返回最终 URL 和响应体
                    return response.bodyToMono(String.class)
                            .map(body -> url + "\n" + body);
                })
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        e -> new IntelligenceException(504, "页面请求超时"));
    }

    /**
     * 解析页面 HTML，提取视频信息（简化版本）。
     * 完整版本需要解析页面中的 window.__INITIAL_STATE__ 等内嵌 JSON。
     */
    private Mono<DouyinSourceMaterial> parsePage(String result) {
        int newline = result.indexOf('\n');
        if (newline < 0) {
            throw new IntelligenceException(502, "页面解析失败");
        }
        String finalUrl = result.substring(0, newline);
        String html = result.substring(newline + 1);

        return Mono.fromCallable(() -> parseHtmlForVideo(finalUrl, html));
    }

    /**
     * 从 HTML 中解析视频信息（简化版本，首期实现）。
     * 完整版本需要解析复杂的内嵌 JSON 结构。
     */
    private DouyinSourceMaterial parseHtmlForVideo(String url, String html) {
        String videoId = extractVideoId(url);
        String title = extractMetaContent(html, "title");
        String author = extractMetaContent(html, "author");
        String coverUrl = extractMetaContent(html, "cover");

        // 简化版本：暂时返回基础信息，后续扩展完整解析
        return new DouyinSourceMaterial(
                url,
                url,
                videoId,
                author,
                title,
                coverUrl,
                null, // durationSeconds 需要完整解析
                null, // playableVideoUrl 需要完整解析
                Map.of(), // requestHeaders
                false); // usedSession
    }

    /**
     * 从 URL 中提取视频 ID。
     */
    private String extractVideoId(String url) {
        Matcher matcher;
        matcher = VIDEO_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = MODAL_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = AWEME_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 从 HTML 中提取 meta 标签内容（简化实现）。
     */
    private String extractMetaContent(String html, String field) {
        // 简化版本，后续扩展完整解析
        try {
            int idx = html.indexOf("\"" + field + "\":");
            if (idx < 0) {
                return null;
            }
            // 简化提取，后续完善
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
