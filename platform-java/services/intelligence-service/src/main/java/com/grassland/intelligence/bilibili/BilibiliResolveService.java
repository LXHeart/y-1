package com.grassland.intelligence.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.http.ManagedWebClientFactory;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Bilibili 视频解析（移植 legacy {@code server/src/services/bilibili-resolve.service.ts}）。
 *
 * <p>流程：从分享文本抽取 page URL → 跟随 ≤5 跳重定向抓页面 HTML → 花括号匹配抽
 * {@code window.__INITIAL_STATE__} / {@code window.__playinfo__} → 缺 {@code __playinfo__} 时用 WBI 签名调
 * {@code api.bilibili.com/x/player/wbi/playurl} → prefer progressive（{@code data.durl[0].url}），
 * fallback DASH（{@code data.dash.video/audio} 首轨 {@code baseUrl}/backup）→ 都无→422。
 *
 * <p>SSRF 边界：page host 与 video host 均经 {@link BilibiliHosts} 静态白名单校验（不做 DNS）。超时→504，
 * 其余上游/解析错误→502（对齐 legacy 文案）。
 */
@Service
public class BilibiliResolveService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INITIAL_STATE_MARKER = "window.__INITIAL_STATE__=";
    private static final String PLAYINFO_MARKER = "window.__playinfo__=";
    private static final int MAX_REDIRECTS = 5;
    private static final String PAGE_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final String PAGE_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";
    private static final String BILIBILI_ORIGIN = "https://www.bilibili.com";

    // 与 legacy SocialSisterYi/bilibili-API-collect 的 WBI mixin-key 置换表逐字一致。
    private static final int[] WBI_MIXIN_KEY_TABLE = {
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static final Pattern URL_CANDIDATES = Pattern.compile("https://[^\\s]+");
    // schema 预检（zod hasAllowedBilibiliUrl）只剥 ASCII 标点。
    private static final Pattern SCHEMA_TRAILING_PUNCT = Pattern.compile("[),.;!?]+$");
    // resolve 抽取（extractBilibiliUrlCandidates）剥 ASCII + CJK 标点/引号/括号。
    private static final Pattern RESOLVE_TRAILING_PUNCT =
            Pattern.compile("[),.;!?，。！？；：、”“'')\\]】》〉」』]+$");
    private static final Pattern WBI_VALUE_STRIP = Pattern.compile("[!'()*]");
    private static final Pattern BV_ID_PATTERN = Pattern.compile("/video/(BV[0-9A-Za-z]+)");

    private final BilibiliFetchProperties props;
    private final WebClient client;

    public BilibiliResolveService(BilibiliFetchProperties props) {
        this.props = props;
        // followRedirect(false)：手动处理 ≤5 跳，每跳经 page host 守卫复验（SSRF 边界）。Bilibili 分享页可达 ~1MB。
        this.client = ManagedWebClientFactory.builder(
                        BilibiliResolveService.class, props.timeout(), 4 * 1024 * 1024)
                .build();
    }

    /**
     * 解析分享文本为 {@link BilibiliSourceMaterial}。无可用 page URL→400；页面缺 {@code __INITIAL_STATE__}→502；
     * 无可用音视频轨→422；上游超时→504；其余上游/解析错误→502。
     */
    public Mono<BilibiliSourceMaterial> resolve(String input) {
        return Mono.fromCallable(() -> extractEntryUrl(input))
                .flatMap(entryUrl -> fetchHtml(entryUrl).flatMap(page -> assembleMaterial(entryUrl, page)));
    }

    /** schema 预检（controller 用）：分享文本是否含允许 page host 的 https URL。对齐 legacy {@code hasAllowedBilibiliUrl}。 */
    static boolean containsAllowedPageUrl(String input) {
        if (input == null) {
            return false;
        }
        Matcher matcher = URL_CANDIDATES.matcher(input);
        while (matcher.find()) {
            String candidate = SCHEMA_TRAILING_PUNCT.matcher(matcher.group()).replaceAll("");
            if (isAllowedPageUrl(candidate)) {
                return true;
            }
        }
        return false;
    }

    // ---- URL 抽取 ----

    /** 取首个允许 page host 的 https URL（剥 CJK 标点）；无→400。对齐 legacy {@code extractBilibiliEntryUrl}。 */
    private static String extractEntryUrl(String input) {
        Matcher matcher = URL_CANDIDATES.matcher(input);
        while (matcher.find()) {
            String candidate = RESOLVE_TRAILING_PUNCT.matcher(matcher.group()).replaceAll("");
            if (isAllowedPageUrl(candidate)) {
                return candidate;
            }
        }
        throw new IntelligenceException(400, "未能从分享文本中提取有效的 B 站链接");
    }

    private static boolean isAllowedPageUrl(String candidate) {
        try {
            URI uri = URI.create(candidate);
            return "https".equalsIgnoreCase(uri.getScheme()) && BilibiliHosts.isAllowedPageHost(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    // ---- HTML 抓取（≤5 跳重定向） ----

    private Mono<PageResponse> fetchHtml(String url) {
        return fetchHtmlHop(url, 0)
                .timeout(props.timeout())
                .onErrorResume(BilibiliResolveService::isTimeout,
                        e -> Mono.error(new IntelligenceException(504, "请求 B 站页面超时")))
                .onErrorResume(e -> e instanceof IntelligenceException
                        ? Mono.error(e)
                        : Mono.error(new IntelligenceException(502, "请求 B 站页面失败")));
    }

    private Mono<PageResponse> fetchHtmlHop(String url, int redirectCount) {
        if (redirectCount >= MAX_REDIRECTS) {
            return Mono.error(new IntelligenceException(502, "B 站链接跳转次数过多"));
        }
        return client.get()
                .uri(url)
                .headers(headers -> applyPageHeaders(headers, url))
                .exchangeToMono(response -> handlePageResponse(response, url, redirectCount));
    }

    private Mono<PageResponse> handlePageResponse(ClientResponse response, String url, int redirectCount) {
        HttpStatusCode status = response.statusCode();
        if (status.value() >= 300 && status.value() < 400) {
            String location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
            return response.releaseBody().then(Mono.fromSupplier(() -> resolveRedirectTarget(location, url))
                    .flatMap(next -> fetchHtmlHop(next, redirectCount + 1)));
        }
        // 对齐 legacy：非 3xx 一律读 body（4xx/5xx 页面交给后续标记缺失判定→502）。
        return response.bodyToMono(String.class).map(body -> new PageResponse(url, body));
    }

    private void applyPageHeaders(HttpHeaders headers, String referer) {
        headers.set(HttpHeaders.USER_AGENT, props.userAgent());
        headers.set(HttpHeaders.ACCEPT, PAGE_ACCEPT);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, PAGE_ACCEPT_LANGUAGE);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
        headers.set(HttpHeaders.PRAGMA, "no-cache");
        headers.set(HttpHeaders.REFERER, referer);
    }

    private static String resolveRedirectTarget(String location, String baseUrl) {
        if (location == null || location.isBlank()) {
            throw new IntelligenceException(502, "B 站链接返回了无效的跳转地址");
        }
        URI resolved = URI.create(baseUrl).resolve(location);
        if (!"https".equalsIgnoreCase(resolved.getScheme()) || !BilibiliHosts.isAllowedPageHost(resolved.getHost())) {
            throw new IntelligenceException(502, "B 站页面跳转到了不受信任的目标地址");
        }
        return resolved.toString();
    }

    // ---- 组装 SourceMaterial ----

    private Mono<BilibiliSourceMaterial> assembleMaterial(String entryUrl, PageResponse page) {
        String initialStateBlock = extractFirstJsonBlock(page.body(), INITIAL_STATE_MARKER);
        if (initialStateBlock == null) {
            return Mono.error(new IntelligenceException(502, "未能从 B 站页面中解析到可预览视频信息"));
        }
        JsonNode initialState;
        try {
            initialState = MAPPER.readTree(initialStateBlock);
        } catch (Exception e) {
            return Mono.error(new IntelligenceException(502, "B 站页面返回了无法解析的视频数据"));
        }
        if (!initialState.isObject()) {
            return Mono.error(new IntelligenceException(502, "B 站页面返回了无法解析的视频数据"));
        }

        String playInfoBlock = extractFirstJsonBlock(page.body(), PLAYINFO_MARKER);
        Mono<JsonNode> playInfoMono;
        if (playInfoBlock != null) {
            try {
                playInfoMono = Mono.just(MAPPER.readTree(playInfoBlock));
            } catch (Exception e) {
                return Mono.error(new IntelligenceException(502, "B 站页面返回了无法解析的视频数据"));
            }
        } else {
            playInfoMono = fetchPlayInfoByApi(initialState, page.finalUrl());
        }
        return playInfoMono.map(playInfo -> buildMaterial(entryUrl, page.finalUrl(), initialState, playInfo));
    }

    BilibiliSourceMaterial buildMaterial(
            String entryUrl, String finalUrl, JsonNode initialState, JsonNode playInfo) {
        String videoId = extractVideoId(initialState, finalUrl);
        String author = extractAuthor(initialState);
        String title = extractTitle(initialState);
        String coverUrl = extractCoverUrl(initialState);
        Long durationSeconds = extractDurationSeconds(initialState, playInfo);
        Map<String, String> requestHeaders = buildMediaRequestHeaders(finalUrl);

        String progressiveUrl = extractProgressiveUrl(playInfo);
        if (progressiveUrl != null) {
            return new BilibiliSourceMaterial.Progressive(
                    entryUrl, finalUrl, videoId, author, title, coverUrl, durationSeconds,
                    progressiveUrl, requestHeaders);
        }
        String videoTrackUrl = extractDashTrackUrl(playInfo, "video");
        String audioTrackUrl = extractDashTrackUrl(playInfo, "audio");
        if (videoTrackUrl == null || audioTrackUrl == null) {
            throw new IntelligenceException(422, "当前 B 站视频缺少可用的音视频双轨，暂不支持预览或下载");
        }
        return new BilibiliSourceMaterial.Dash(
                entryUrl, finalUrl, videoId, author, title, coverUrl, durationSeconds,
                videoTrackUrl, audioTrackUrl, requestHeaders);
    }

    /** 代理视频流时发给上游 bilivideo CDN 的请求头（对齐 legacy {@code buildMediaRequestHeaders}）。 */
    private Map<String, String> buildMediaRequestHeaders(String referer) {
        // 小写键与 BilibiliProxyToken 白名单 {referer,user-agent,origin} 对齐（token 清洗后下发）。
        return new java.util.LinkedHashMap<>(Map.of(
                "user-agent", props.userAgent(),
                "referer", referer,
                "origin", BILIBILI_ORIGIN));
    }

    // ---- 花括号匹配抽 JSON 块 ----

    /** 从 {@code marker} 后首个 {@code {} 起，按字符串感知的花括号深度匹配到配对 {@code }} 的子串。 */
    static String extractFirstJsonBlock(String html, String marker) {
        int markerIndex = html.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int scriptStart = html.indexOf('{', markerIndex);
        if (scriptStart < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = scriptStart; index < html.length(); index++) {
            char character = html.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (character == '\\') {
                    escaped = true;
                    continue;
                }
                if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return html.substring(scriptStart, index + 1);
                }
            }
        }
        return null;
    }

    // ---- WBI playurl fallback ----

    private Mono<JsonNode> fetchPlayInfoByApi(JsonNode initialState, String referer) {
        Long aid = extractAid(initialState);
        String bvid = extractVideoId(initialState, referer);
        Long cid = extractCid(initialState, referer);
        WbiKeys wbiKeys = extractWbiKeys(initialState);
        if (aid == null || bvid == null || cid == null || wbiKeys == null) {
            return Mono.error(new IntelligenceException(502, "未能从 B 站页面中解析到可预览视频信息"));
        }

        String query = buildSignedPlayurlQuery(aid, bvid, cid, wbiKeys);
        String requestUrl = "https://api.bilibili.com/x/player/wbi/playurl?" + query;
        return client.get()
                .uri(requestUrl)
                .headers(headers -> applyPlayInfoHeaders(headers, referer))
                .exchangeToMono(response -> handlePlayInfoResponse(response))
                .timeout(props.timeout())
                .onErrorResume(BilibiliResolveService::isTimeout,
                        e -> Mono.error(new IntelligenceException(504, "请求 B 站播放信息超时")))
                .onErrorResume(e -> e instanceof IntelligenceException
                        ? Mono.error(e)
                        : Mono.error(new IntelligenceException(502, "请求 B 站播放信息失败")));
    }

    private void applyPlayInfoHeaders(HttpHeaders headers, String referer) {
        headers.set(HttpHeaders.USER_AGENT, props.userAgent());
        headers.set(HttpHeaders.REFERER, referer);
        headers.set(HttpHeaders.ORIGIN, BILIBILI_ORIGIN);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
    }

    private Mono<JsonNode> handlePlayInfoResponse(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.is4xxClientError() || status.is5xxServerError()) {
            // 对齐 legacy：412（风控）→502，其余透传上游状态码。
            return response.releaseBody().then(Mono.error(new IntelligenceException(
                    status.value() == 412 ? 502 : status.value(), "请求 B 站播放信息失败")));
        }
        return response.bodyToMono(String.class).flatMap(this::parsePlayInfoBody);
    }

    private Mono<JsonNode> parsePlayInfoBody(String body) {
        JsonNode payload;
        try {
            payload = MAPPER.readTree(body);
        } catch (Exception e) {
            return Mono.error(new IntelligenceException(502, "B 站播放信息返回了无效数据"));
        }
        if (payload == null || !payload.isObject()) {
            return Mono.error(new IntelligenceException(502, "B 站播放信息返回了无效数据"));
        }
        JsonNode codeNode = payload.path("code");
        int code = codeNode.isNumber() ? codeNode.asInt() : -1;
        JsonNode data = payload.path("data");
        if (code != 0 || !data.isObject()) {
            return Mono.error(new IntelligenceException(502, "请求 B 站播放信息失败"));
        }
        return Mono.just(payload);
    }

    // ---- WBI 签名 ----

    private String buildSignedPlayurlQuery(long aid, String bvid, long cid, WbiKeys wbiKeys) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("avid", Long.toString(aid));
        params.put("bvid", bvid);
        params.put("cid", Long.toString(cid));
        params.put("fnval", "16");
        params.put("fnver", "0");
        params.put("fourk", "1");
        params.put("qn", "0");
        params.put("wts", Long.toString(Instant.now().getEpochSecond()));

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            // 对齐 legacy：值剔除 [!'()*]（WBI 参数均为字母数字，此处为 noop，但保持一致）。
            query.append(entry.getKey()).append('=').append(stripWbiValue(entry.getValue()));
        }
        String mixinKey = buildWbiMixinKey(wbiKeys.imgKey(), wbiKeys.subKey());
        String wRid = md5Hex(query + mixinKey);
        query.append("&w_rid=").append(wRid);
        return query.toString();
    }

    /** WBI mixin-key：imgKey+subKey 按置换表取字符、截前 32。 */
    static String buildWbiMixinKey(String imgKey, String subKey) {
        String combined = imgKey + subKey;
        StringBuilder sb = new StringBuilder(32);
        for (int index : WBI_MIXIN_KEY_TABLE) {
            if (index < combined.length()) {
                sb.append(combined.charAt(index));
            }
        }
        return sb.length() > 32 ? sb.substring(0, 32) : sb.toString();
    }

    private static String stripWbiValue(String value) {
        return WBI_VALUE_STRIP.matcher(value).replaceAll("");
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IntelligenceException(500, "WBI 签名计算失败");
        }
    }

    private record WbiKeys(String imgKey, String subKey) {}

    private static WbiKeys extractWbiKeys(JsonNode initialState) {
        JsonNode defaultWbiKey = initialState.path("defaultWbiKey");
        String imgKey = readTextCandidate(defaultWbiKey.path("wbiImgKey"));
        String subKey = readTextCandidate(defaultWbiKey.path("wbiSubKey"));
        if (imgKey == null || subKey == null) {
            return null;
        }
        return new WbiKeys(imgKey, subKey);
    }

    // ---- playInfo / initialState 字段抽取 ----

    private static String extractProgressiveUrl(JsonNode playInfo) {
        JsonNode durl = playInfo.path("data").path("durl");
        if (!durl.isArray()) {
            return null;
        }
        for (JsonNode entry : durl) {
            String url = readTextCandidate(entry.path("url"));
            if (url != null && isTrustedVideoUrl(url)) {
                return url;
            }
        }
        return null;
    }

    private static String extractDashTrackUrl(JsonNode playInfo, String trackType) {
        JsonNode entries = playInfo.path("data").path("dash").path(trackType);
        if (!entries.isArray()) {
            return null;
        }
        for (JsonNode entry : entries) {
            List<String> candidates = new ArrayList<>();
            addTextCandidate(candidates, entry.path("baseUrl"));
            addTextCandidate(candidates, entry.path("base_url"));
            addAllTextCandidates(candidates, entry.path("backupUrl"));
            addAllTextCandidates(candidates, entry.path("backup_url"));
            for (String candidate : candidates) {
                if (isTrustedVideoUrl(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String extractTitle(JsonNode initialState) {
        String title = readTextCandidate(initialState.path("videoData").path("title"));
        return title != null ? title : readTextCandidate(initialState.path("h1Title"));
    }

    private static String extractAuthor(JsonNode initialState) {
        return readTextCandidate(initialState.path("videoData").path("owner").path("name"));
    }

    private static String extractCoverUrl(JsonNode initialState) {
        return readTextCandidate(initialState.path("videoData").path("pic"));
    }

    private static String extractVideoId(JsonNode initialState, String resolvedUrl) {
        String bvid = readTextCandidate(initialState.path("bvid"));
        if (bvid != null) {
            return bvid;
        }
        bvid = readTextCandidate(initialState.path("videoData").path("bvid"));
        if (bvid != null) {
            return bvid;
        }
        Matcher matcher = BV_ID_PATTERN.matcher(resolvedUrl);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Long extractDurationSeconds(JsonNode initialState, JsonNode playInfo) {
        Double directDuration = readNumberCandidate(initialState.path("videoData").path("duration"));
        if (directDuration != null) {
            return (long) Math.ceil(directDuration);
        }
        Double timelengthMs = readNumberCandidate(playInfo.path("data").path("timelength"));
        if (timelengthMs != null) {
            return (long) Math.ceil(timelengthMs / 1000);
        }
        return null;
    }

    private static Long extractAid(JsonNode initialState) {
        return readPositiveLong(initialState.path("aid"), initialState.path("videoData").path("aid"));
    }

    private static Long extractCid(JsonNode initialState, String resolvedUrl) {
        Long directCid = readPositiveLong(initialState.path("cid"));
        if (directCid != null) {
            return directCid;
        }
        JsonNode pages = initialState.path("videoData").path("pages");
        if (!pages.isArray()) {
            return null;
        }
        int requestedPage = extractRequestedPageNumber(initialState, resolvedUrl);
        Long firstCid = null;
        for (JsonNode page : pages) {
            Long cid = readPositiveLong(page.path("cid"));
            if (cid == null) {
                continue;
            }
            if (firstCid == null) {
                firstCid = cid;
            }
            Double pageNumber = readNumberCandidate(page.path("page"));
            if (pageNumber != null && pageNumber.intValue() == requestedPage) {
                return cid;
            }
        }
        return firstCid;
    }

    private static int extractRequestedPageNumber(JsonNode initialState, String resolvedUrl) {
        try {
            MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(resolvedUrl).build().getQueryParams();
            String pageParam = query.getFirst("p");
            if (pageParam != null) {
                int pageNumber = Integer.parseInt(pageParam.trim());
                if (pageNumber > 0) {
                    return pageNumber;
                }
            }
        } catch (Exception ignored) {
            // URL 畸形时回落到 initialState.p / 默认 1。
        }
        Double fromState = readNumberCandidate(initialState.path("p"));
        return fromState != null ? fromState.intValue() : 1;
    }

    private static boolean isTrustedVideoUrl(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && BilibiliHosts.isAllowedVideoHost(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private static void addTextCandidate(List<String> candidates, JsonNode node) {
        String value = readTextCandidate(node);
        if (value != null) {
            candidates.add(value);
        }
    }

    private static void addAllTextCandidates(List<String> candidates, JsonNode node) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode entry : node) {
            addTextCandidate(candidates, entry);
        }
    }

    private static String readTextCandidate(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Double readNumberCandidate(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        return Double.isFinite(value) && value > 0 ? value : null;
    }

    private static Long readPositiveLong(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            Double value = readNumberCandidate(node);
            if (value != null) {
                return value.longValue();
            }
        }
        return null;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private record PageResponse(String finalUrl, String body) {}
}
