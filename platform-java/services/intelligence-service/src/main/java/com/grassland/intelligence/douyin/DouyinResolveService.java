package com.grassland.intelligence.douyin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * 抖音视频解析服务（移植 legacy {@code server/src/services/douyin-resolve.service.ts} 的匿名 HTTP 阶段）。
 *
 * <p>流程：分享文本抽取入口 URL → 桌面 HTTP 抓取（手动重定向、每跳 page host 守卫）→ 解析页面
 * （meta/标题/JSON 片段/挑战页检测/时长/可播放地址）→ 未解析出可播放地址时依次尝试规范化桌面视频页与
 * 移动分享页。本服务负责非浏览器 HTTP 阶段；解析不出可播放地址时返回
 * {@code playableVideoUrl=null}，由 controller 调用 Java Playwright 浏览器增强。
 *
 * <p>SSRF 边界：page host 与 video host 均经 {@link DouyinHosts} 静态白名单校验。
 * 超时→504，重定向到不受信目标/其余上游错误→502（错误文案逐字对齐 legacy {@code lib/http.ts}）。
 */
@Service
public class DouyinResolveService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PAGE_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";
    private static final String PAGE_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";

    // 视频 ID 匹配（对齐 legacy extractVideoId）
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("video/(\\d+)");
    private static final Pattern MODAL_ID_PATTERN = Pattern.compile("modal_id=(\\d+)");
    private static final Pattern AWEME_ID_PATTERN = Pattern.compile("aweme_id=(\\d+)");

    // URL 候选（对齐 legacy：/https:\/\/[^\s]+/g + 尾部标点裁剪，含中文标点）
    private static final Pattern URL_CANDIDATES = Pattern.compile("https://[^\\s]+");
    private static final Pattern TRAILING_PUNCT =
            Pattern.compile("[),.;!?，。！？；：、\u201C\u201D\u2018\u2019）\\]】》〉」』]+$");

    // 挑战页线索（对齐 legacy detectChallengePage）
    private static final List<String> CHALLENGE_HINTS = List.of(
            "Please wait", "waf_js", "_wafchallengeid", "captcha", "verify", "安全验证", "验证中",
            "window.WAFJS", "argus-csp-token", "verifyCenter", "secsdk", "bdms");

    private static final Pattern SCRIPT_BLOCKS = Pattern.compile("(?is)<script[^>]*>(.*?)</script>");
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern SCRIPT_OR_STYLE_BLOCKS = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    // 可播放地址抽取（对齐 legacy extractPlayAddressFromSnippet，逐字同式）
    private static final Pattern PLAYABLE_PLAYWM = Pattern.compile("https?://[^\"'\\\\]+playwm[^\"'\\\\]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYABLE_PLAY = Pattern.compile("https?://[^\"'\\\\]+play(?:/|\\?)[^\"'\\\\]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYABLE_DOWNLOAD = Pattern.compile("https?://[^\"'\\\\]+download[^\"'\\\\]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYABLE_COMBINED =
            Pattern.compile("https?://[^\"'\\\\]+(?:playwm|play(?:/|\\?)|download)[^\"'\\\\]*", Pattern.CASE_INSENSITIVE);

    // 时长 regex 兜底（对齐 legacy snippet.video.duration-regex）
    private static final Pattern DIRECT_DURATION =
            Pattern.compile("\"video\"\\s*:\\s*\\{[\\s\\S]{0,1600}?\"duration(?:_ms|Ms)?\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final String GENERIC_TITLE = "抖音-记录美好生活";
    private static final String GENERIC_DESCRIPTION = "抖音，记录美好生活";

    private final DouyinFetchProperties props;
    private final WebClient client;

    public DouyinResolveService(DouyinFetchProperties props) {
        this.props = props;
        // followRedirect(false)：手动处理重定向，每跳经 page host 守卫复验（SSRF 边界，对齐 legacy fetchText）
        HttpClient http = HttpClient.create().followRedirect(false).responseTimeout(props.timeout());
        this.client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    /**
     * 解析分享文本为 {@link DouyinSourceMaterial}。
     *
     * <p>无可用入口 URL→400；页面抓取超时→504；重定向不受信/抓取失败→502；HTTP 阶段解析不出
     * 可播放地址（挑战页/缺播放源）时返回 {@code playableVideoUrl=null} 的素材（不抛错，回落由 controller 决策）。
     */
    public Mono<DouyinSourceMaterial> resolve(String input) {
        return Mono.fromCallable(() -> extractEntryUrl(input))
                .flatMap(this::resolveFromUrl);
    }

    /** 对齐 legacy schema {@code hasAllowedDouyinUrl}：分享文本含允许 page host 的 https URL。 */
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

    /** 对齐 legacy {@code extractDouyinEntryUrl}。 */
    static String extractEntryUrl(String input) {
        Matcher matcher = URL_CANDIDATES.matcher(input);
        while (matcher.find()) {
            String candidate = TRAILING_PUNCT.matcher(matcher.group()).replaceAll("");
            if (isAllowedPageUrl(candidate)) {
                return candidate;
            }
        }
        throw new IntelligenceException(400, "未能从分享文本中提取有效的抖音链接");
    }

    private static boolean isAllowedPageUrl(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && DouyinHosts.isAllowedPageHost(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 抓取阶段编排（对齐 legacy 匿名路径）

    private Mono<DouyinSourceMaterial> resolveFromUrl(String entryUrl) {
        return fetchAndParse(entryUrl, props.cookieUserAgent(), "https://www.douyin.com/")
                .flatMap(initial -> {
                    if (initial.assetResolvable()) {
                        return Mono.just(initial);
                    }
                    DouyinSourceMaterial best = initial;
                    return canonicalDesktopRetry(entryUrl, best)
                            .flatMap(afterCanonical -> mobileStage(entryUrl, afterCanonical));
                });
    }

    /** 对齐 legacy {@code shouldRetryDesktopVideoPage} + canonical 桌面视频页重试。 */
    private Mono<DouyinSourceMaterial> canonicalDesktopRetry(String entryUrl, DouyinSourceMaterial best) {
        if (!shouldRetryDesktopVideoPage(best)) {
            return Mono.just(best);
        }
        String canonicalUrl = "https://www.douyin.com/video/" + best.videoId();
        return fetchAndParse(canonicalUrl, props.cookieUserAgent(), "https://www.douyin.com/")
                .map(canonical -> {
                    if (canonical.assetResolvable()) {
                        return canonical;
                    }
                    return selectPreferred(best, canonical);
                })
                // legacy：canonical 抓取失败仅记日志，继续后续阶段
                .onErrorResume(error -> Mono.just(best));
    }

    /** 对齐 legacy mobile_http 阶段（含 {@code shouldSkipMobileHttpForDirectVideoUrl} 跳过规则）。 */
    private Mono<DouyinSourceMaterial> mobileStage(String entryUrl, DouyinSourceMaterial best) {
        if (shouldSkipMobileHttpForDirectVideoUrl(entryUrl, best)) {
            return Mono.just(best);
        }
        return fetchAndParse(entryUrl, DouyinFetchProperties.MOBILE_SHARE_USER_AGENT, "https://www.iesdouyin.com/")
                .map(mobile -> mobile.assetResolvable() ? mobile : selectPreferred(best, mobile))
                // legacy：mobile 抓取失败仅记日志，返回 best
                .onErrorResume(error -> Mono.just(best));
    }

    private static boolean shouldRetryDesktopVideoPage(DouyinSourceMaterial source) {
        return !source.challengePage()
                && !source.assetResolvable()
                && source.videoId() != null
                && !source.resolvedUrl().contains("/share/video/")
                && !isCanonicalDesktopVideoUrl(source.resolvedUrl(), source.videoId());
    }

    private static boolean shouldSkipMobileHttpForDirectVideoUrl(String requestUrl, DouyinSourceMaterial best) {
        // legacy 还要求 bestMaterial.fetchStage === 'desktop_http'——Java 侧只有 HTTP 阶段，恒成立。
        return best.videoId() != null
                && isCanonicalDesktopVideoUrl(requestUrl, best.videoId())
                && !best.challengePage()
                && !best.assetResolvable();
    }

    private static boolean isCanonicalDesktopVideoUrl(String url, String videoId) {
        if (videoId == null) {
            return false;
        }
        try {
            URI parsed = URI.create(url);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            return ("www.douyin.com".equals(host) || "douyin.com".equals(host))
                    && path.matches("^/video/" + videoId + "/?$");
        } catch (Exception e) {
            return false;
        }
    }

    private static DouyinSourceMaterial selectPreferred(DouyinSourceMaterial current, DouyinSourceMaterial candidate) {
        boolean preferCandidate = shouldPrefer(candidate, current);
        DouyinSourceMaterial preferred = preferCandidate ? candidate : current;
        DouyinSourceMaterial fallback = preferCandidate ? current : candidate;
        return merge(preferred, fallback);
    }

    /** 对齐 legacy {@code shouldPreferMaterial}（去掉 browser hints 后：挑战页 → 可解析 → 标题 → 分享页）。 */
    private static boolean shouldPrefer(DouyinSourceMaterial candidate, DouyinSourceMaterial current) {
        if (candidate.challengePage() != current.challengePage()) {
            return !candidate.challengePage();
        }
        if (candidate.assetResolvable() != current.assetResolvable()) {
            return candidate.assetResolvable();
        }
        boolean candidateHasTitle = candidate.title() != null;
        boolean currentHasTitle = current.title() != null;
        if (candidateHasTitle != currentHasTitle) {
            return candidateHasTitle;
        }
        boolean candidateIsSharePage = candidate.resolvedUrl().contains("iesdouyin.com/share/video/");
        boolean currentIsSharePage = current.resolvedUrl().contains("iesdouyin.com/share/video/");
        return candidateIsSharePage && !currentIsSharePage;
    }

    /** 对齐 legacy {@code mergeMaterialFields}：优先素材缺失字段从回退素材补齐。 */
    private static DouyinSourceMaterial merge(DouyinSourceMaterial preferred, DouyinSourceMaterial fallback) {
        return new DouyinSourceMaterial(
                preferred.sourceUrl(),
                preferred.resolvedUrl(),
                firstNonNull(preferred.videoId(), fallback.videoId()),
                firstNonNull(preferred.author(), fallback.author()),
                firstNonNull(preferred.title(), fallback.title()),
                firstNonNull(preferred.coverUrl(), fallback.coverUrl()),
                preferred.durationSeconds() != null ? preferred.durationSeconds() : fallback.durationSeconds(),
                firstNonNull(preferred.playableVideoUrl(), fallback.playableVideoUrl()),
                preferred.requestHeaders(),
                preferred.usedSession(),
                preferred.fetchStage(),
                preferred.challengePage());
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    // ---------------------------------------------------------------- 页面抓取（对齐 legacy lib/http.ts fetchText）

    private record PageFetch(String finalUrl, String html) {}

    private Mono<DouyinSourceMaterial> fetchAndParse(String url, String userAgent, String referer) {
        return fetchPage(url, userAgent, referer, 0, new ArrayList<>())
                .map(fetch -> parseSourceMaterial(url, fetch.finalUrl(), fetch.html()));
    }

    private Mono<PageFetch> fetchPage(String url, String userAgent, String referer, int depth, List<String> visited) {
        if (depth >= props.maxRedirects()) {
            return Mono.error(new IntelligenceException(502, "抖音链接跳转次数过多"));
        }
        if (visited.contains(url)) {
            return Mono.error(new IntelligenceException(502, "抖音链接跳转次数过多"));
        }
        visited.add(url);

        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return Mono.error(new IntelligenceException(502, "请求抖音页面失败"));
        }

        return client.get()
                .uri(uri)
                .headers(headers -> {
                    headers.set(HttpHeaders.USER_AGENT, userAgent);
                    headers.set(HttpHeaders.ACCEPT, PAGE_ACCEPT);
                    headers.set(HttpHeaders.ACCEPT_LANGUAGE, PAGE_ACCEPT_LANGUAGE);
                    headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
                    headers.set(HttpHeaders.PRAGMA, "no-cache");
                    headers.set(HttpHeaders.REFERER, referer);
                })
                .exchangeToMono(response -> {
                    if (response.statusCode().is3xxRedirection()) {
                        String location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                        if (location == null || location.isBlank()) {
                            return response.releaseBody()
                                    .then(Mono.error(new IntelligenceException(502, "抖音链接返回了无效的跳转地址")));
                        }
                        String nextUrl;
                        try {
                            nextUrl = URI.create(url).resolve(location).toString();
                        } catch (Exception e) {
                            return response.releaseBody()
                                    .then(Mono.error(new IntelligenceException(502, "抖音链接返回了无效的跳转地址")));
                        }
                        return response.releaseBody().then(Mono.defer(() -> {
                            try {
                                assertAllowedRedirectTarget(nextUrl);
                            } catch (IntelligenceException e) {
                                return Mono.error(e);
                            }
                            return fetchPage(nextUrl, userAgent, referer, depth + 1, visited);
                        }));
                    }
                    // 对齐 legacy fetchText：非 3xx 一律读 body（挑战页/错误页由解析阶段判定）
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new PageFetch(url, body));
                })
                .onErrorMap(error -> {
                    if (error instanceof IntelligenceException) {
                        return error;
                    }
                    if (error instanceof java.util.concurrent.TimeoutException
                            || error instanceof io.netty.handler.timeout.ReadTimeoutException
                            || error instanceof io.netty.channel.ConnectTimeoutException) {
                        return new IntelligenceException(504, "请求抖音页面超时");
                    }
                    return new IntelligenceException(502, "请求抖音页面失败");
                });
    }

    /** 对齐 legacy {@code assertAllowedHost}：重定向目标必须 https + 受信 page host。 */
    private static void assertAllowedRedirectTarget(String url) {
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (Exception e) {
            throw new IntelligenceException(502, "抖音链接返回了无效的跳转地址");
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())) {
            throw new IntelligenceException(502, "抖音链接跳转到了不安全的协议");
        }
        if (!DouyinHosts.isAllowedPageHost(parsed.getHost())) {
            throw new IntelligenceException(502, "抖音页面跳转到了不受信任目标地址");
        }
    }

    // ---------------------------------------------------------------- 页面解析（对齐 legacy parseSourceMaterial 子集）

    /** 解析单页 HTML 为素材。包内可见，供单测直接驱动。 */
    DouyinSourceMaterial parseSourceMaterial(String sourceUrl, String finalUrl, String html) {
        String videoId = extractVideoId(finalUrl);
        String pageTitle = extractTitleTag(html);
        String rawTitle = firstNonBlank(
                metaContent(html, "property", "og:title"),
                metaContent(html, "name", "title"),
                metaContent(html, "property", "twitter:title"),
                pageTitle);
        String rawDescription = firstNonBlank(
                metaContent(html, "name", "description"),
                metaContent(html, "property", "og:description"),
                metaContent(html, "name", "twitter:description"));
        String coverUrl = firstNonBlank(
                metaContent(html, "property", "og:image"),
                metaContent(html, "name", "twitter:image"));
        String metaAuthor = firstNonBlank(
                metaContent(html, "name", "author"),
                metaContent(html, "property", "og:site_name"));

        String title = GENERIC_TITLE.equals(rawTitle) ? null : rawTitle;
        String metaDescription = GENERIC_DESCRIPTION.equals(rawDescription) ? null : rawDescription;
        ShareMeta shareMeta = parseShareMetaDescription(metaDescription);

        boolean isSharePage = isShareHost(finalUrl);
        boolean looksLikeShareMetadata = isSharePage && (metaDescription != null || videoId != null);

        List<String> snippets = collectJsonSnippets(html);
        Long durationSeconds = null;
        for (String snippet : snippets) {
            Optional<Long> extracted = extractDurationFromSnippet(snippet);
            if (extracted.isPresent()) {
                durationSeconds = extracted.get();
                break;
            }
        }
        String playableVideoUrl = findPlayableVideoUrl(snippets);
        boolean challengePage = detectChallengePage(html);

        String visibleText = extractVisibleText(html);
        String contentText = joinContent(shareMeta.rawText(), visibleText);
        if (!looksLikeShareMetadata && contentText.isEmpty() && title == null
                && metaDescription == null && snippets.isEmpty()) {
            throw new IntelligenceException(502, "未能从抖音页面提取有效内容");
        }

        return new DouyinSourceMaterial(
                sourceUrl,
                finalUrl,
                videoId,
                firstNonNull(shareMeta.author(), metaAuthor),
                firstNonNull(shareMeta.title(), title),
                coverUrl,
                durationSeconds,
                playableVideoUrl,
                Map.of(),
                false,
                "page_json",
                challengePage);
    }

    static String extractVideoId(String url) {
        Matcher matcher = VIDEO_ID_PATTERN.matcher(url);
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

    private static boolean isShareHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).endsWith("iesdouyin.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static String extractTitleTag(String html) {
        Matcher matcher = TITLE_TAG.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        String text = decodeEntities(matcher.group(1)).trim();
        return text.isEmpty() ? null : text;
    }

    /** meta 标签 content 提取（属性顺序双向匹配；对齐 legacy cheerio attr 语义：trim 后非空才返回）。 */
    static String metaContent(String html, String attrKind, String attrValue) {
        String quoted = Pattern.quote(attrValue);
        String forward = "(?is)<meta\\b[^>]*\\b" + attrKind + "=([\"'])" + quoted + "\\1[^>]*\\bcontent=([\"'])(.*?)\\2[^>]*/?>";
        String backward = "(?is)<meta\\b[^>]*\\bcontent=([\"'])(.*?)\\1[^>]*\\b" + attrKind + "=([\"'])" + quoted + "\\3[^>]*/?>";
        Matcher matcher = Pattern.compile(forward).matcher(html);
        if (matcher.find()) {
            String value = decodeEntities(matcher.group(3)).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        matcher = Pattern.compile(backward).matcher(html);
        if (matcher.find()) {
            String value = decodeEntities(matcher.group(2)).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** 包内可见供单测驱动（对齐 legacy {@code parseShareMetaDescription}）。 */
    record ShareMeta(String title, String author, String rawText) {}

    /** 对齐 legacy {@code parseShareMetaDescription}：分享页 description 拆「内容 - 作者于YYYYMMDD发布在抖音」。 */
    static ShareMeta parseShareMetaDescription(String metaDescription) {
        if (metaDescription == null) {
            return new ShareMeta(null, null, null);
        }
        String cleaned = WHITESPACE_RUN.matcher(metaDescription).replaceAll(" ").trim();
        String[] parts = cleaned.split("\\s+-\\s+");
        String contentPart = parts.length > 0 && !parts[0].trim().isEmpty() ? parts[0].trim() : null;
        String author = null;
        if (parts.length > 1) {
            Matcher matcher = Pattern.compile("(.+?)于\\d{8}发布在抖音").matcher(parts[1]);
            if (matcher.find()) {
                String candidate = matcher.group(1).trim();
                author = candidate.isEmpty() ? null : candidate;
            }
        }
        return new ShareMeta(contentPart, author, contentPart);
    }

    /** 对齐 legacy {@code collectJsonSnippets} + {@code rankJsonSnippet} 合并排序（上限 20）。 */
    static List<String> collectJsonSnippets(String html) {
        Matcher matcher = SCRIPT_BLOCKS.matcher(html);
        List<String> candidates = new ArrayList<>();
        while (matcher.find() && candidates.size() < 64) {
            String content = matcher.group(1).trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.startsWith("{")
                    || content.startsWith("window.__")
                    || content.contains("SIGI_STATE")
                    || content.contains("__INITIAL_STATE__")
                    || content.contains("__NEXT_DATA__")
                    || content.contains("RENDER_DATA")
                    || content.contains("aweme")
                    || content.contains("videoDetail")) {
                candidates.add(content);
            }
        }
        return IntStream.range(0, candidates.size())
                .mapToObj(index -> Map.entry(index, candidates.get(index)))
                .sorted(Comparator
                        .comparingInt((Map.Entry<Integer, String> entry) -> rankJsonSnippet(entry.getValue())).reversed()
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .distinct()
                .limit(20)
                .toList();
    }

    private static int rankJsonSnippet(String content) {
        int score = 0;
        if (content.matches("(?s).*(?i:aweme|aweme_detail|detail|videoDetail|itemInfo).*")) {
            score += 6;
        }
        if (content.matches("(?s).*(?i:desc|caption|author|nickname|unique_id).*")) {
            score += 4;
        }
        if (content.matches("(?s).*(?i:__INITIAL_STATE__|SIGI_STATE|__NEXT_DATA__|RENDER_DATA).*")) {
            score += 3;
        }
        return score;
    }

    // ---------------------------------------------------------------- 时长提取（对齐 legacy extractDurationSeconds*）

    static Optional<Long> extractDurationFromSnippet(String content) {
        String normalized = normalizeEscapedUrlContent(content);
        int jsonStart = normalized.indexOf('{');
        int jsonEnd = normalized.lastIndexOf('}');
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            try {
                JsonNode parsed = MAPPER.readTree(normalized.substring(jsonStart, jsonEnd + 1));
                Optional<Long> hit = extractDurationFromStructured(parsed);
                if (hit.isPresent()) {
                    return hit;
                }
                // 对齐 legacy：JSON 可解析但结构化未命中时不再 regex（legacy 转而收集 candidatePaths 仅记日志）。
                return Optional.empty();
            } catch (Exception ignored) {
                // fall through to regex fallback（对齐 legacy：仅 JSON 解析失败才走 regex）
            }
        }
        Matcher matcher = DIRECT_DURATION.matcher(normalized);
        if (matcher.find()) {
            return normalizeDuration(Long.parseLong(matcher.group(1)));
        }
        return Optional.empty();
    }

    private static Optional<Long> extractDurationFromStructured(JsonNode value) {
        Optional<Long> loaderHit = extractDurationFromLoaderData(value);
        if (loaderHit.isPresent()) {
            return loaderHit;
        }
        JsonNode itemInfo = getRecord(value, "itemInfo");
        JsonNode app = getRecord(value, "app");
        JsonNode[][] candidates = {
                {value},
                {getRecord(value, "data")},
                {getRecord(value, "aweme_detail")},
                {getRecord(value, "awemeDetail")},
                {itemInfo},
                {getRecord(itemInfo, "itemStruct")},
                {getRecord(value, "videoDetail")},
                {getRecord(app, "videoDetail")},
        };
        for (JsonNode[] candidateWrap : candidates) {
            JsonNode candidate = candidateWrap[0];
            if (candidate == null) {
                continue;
            }
            JsonNode video = getRecord(candidate, "video");
            JsonNode playAddr = getRecord(video, "play_addr") != null ? getRecord(video, "play_addr") : getRecord(video, "playAddr");
            Optional<Long> hit = firstPresent(
                    readDuration(candidate, "duration"),
                    readDuration(candidate, "duration_ms"),
                    readDuration(candidate, "durationMs"),
                    readDuration(video, "duration_ms"),
                    readDuration(video, "durationMs"),
                    readDuration(video, "duration"),
                    readDuration(playAddr, "duration"));
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> extractDurationFromLoaderData(JsonNode value) {
        JsonNode loaderData = getRecord(value, "loaderData");
        if (loaderData == null) {
            return Optional.empty();
        }
        var fields = loaderData.fields();
        while (fields.hasNext()) {
            JsonNode loaderValue = fields.next().getValue();
            if (loaderValue == null || !loaderValue.isObject()) {
                continue;
            }
            JsonNode videoInfoResponse = getRecord(loaderValue, "videoInfoRes");
            JsonNode itemList = videoInfoResponse == null ? null : videoInfoResponse.get("item_list");
            JsonNode firstItem = itemList != null && itemList.isArray() && !itemList.isEmpty() ? itemList.get(0) : null;
            JsonNode video = getRecord(firstItem, "video");
            Optional<Long> hit = firstPresent(
                    readDuration(video, "duration"),
                    readDuration(video, "duration_ms"),
                    readDuration(video, "durationMs"));
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<Long> firstPresent(Optional<Long>... candidates) {
        for (Optional<Long> candidate : candidates) {
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> readDuration(JsonNode node, String field) {
        if (node == null) {
            return Optional.empty();
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return Optional.empty();
        }
        return normalizeDuration((long) Math.ceil(value.asDouble()));
    }

    /** 对齐 legacy {@code normalizeDurationSeconds}：≥1000 视为毫秒。 */
    static Optional<Long> normalizeDuration(long value) {
        if (value <= 0) {
            return Optional.empty();
        }
        return Optional.of(value >= 1000 ? (long) Math.ceil(value / 1000.0) : value);
    }

    private static JsonNode getRecord(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(field);
        return child != null && child.isObject() ? child : null;
    }

    // ---------------------------------------------------------------- 可播放地址（对齐 legacy extractPlayAddressFromSnippet）

    private static String findPlayableVideoUrl(List<String> snippets) {
        for (String snippet : snippets) {
            String playable = extractPlayAddressFromSnippet(snippet);
            if (playable != null) {
                return playable;
            }
        }
        return null;
    }

    static String extractPlayAddressFromSnippet(String content) {
        String normalized = normalizeEscapedUrlContent(content);
        for (Pattern pattern : new Pattern[] {PLAYABLE_PLAYWM, PLAYABLE_PLAY, PLAYABLE_DOWNLOAD}) {
            Matcher matcher = pattern.matcher(normalized);
            if (matcher.find() && isAllowedVideoUrl(matcher.group())) {
                return matcher.group();
            }
        }
        try {
            JsonNode parsed = MAPPER.readTree(content);
            String serialized = normalizeEscapedUrlContent(MAPPER.writeValueAsString(parsed));
            Matcher matcher = PLAYABLE_COMBINED.matcher(serialized);
            if (matcher.find() && isAllowedVideoUrl(matcher.group())) {
                return matcher.group();
            }
        } catch (Exception ignored) {
            // 非 JSON 片段：regex 已覆盖
        }
        return null;
    }

    private static boolean isAllowedVideoUrl(String url) {
        try {
            return DouyinHosts.isAllowedVideoHost(URI.create(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** 对齐 legacy {@code normalizeEscapedUrlContent}。 */
    static String normalizeEscapedUrlContent(String content) {
        return content
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("\\/", "/");
    }

    // ---------------------------------------------------------------- 挑战页 / 可见文本

    static boolean detectChallengePage(String html) {
        for (String hint : CHALLENGE_HINTS) {
            if (html.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String extractVisibleText(String html) {
        String withoutBlocks = SCRIPT_OR_STYLE_BLOCKS.matcher(html).replaceAll(" ");
        String text = HTML_TAGS.matcher(withoutBlocks).replaceAll(" ");
        text = decodeEntities(text);
        text = WHITESPACE_RUN.matcher(text).replaceAll(" ").trim();
        return text.length() > 6000 ? text.substring(0, 6000) : text;
    }

    private static String joinContent(String shareMetaRawText, String visibleText) {
        StringBuilder builder = new StringBuilder();
        if (shareMetaRawText != null && !shareMetaRawText.isEmpty()) {
            builder.append(shareMetaRawText);
        }
        if (visibleText != null && !visibleText.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(visibleText);
        }
        return builder.toString();
    }

    private static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
