package com.grassland.intelligence.douyin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DouyinResolveService} 解析逻辑单测（草场 GL-P3-MEDIA-001）。HTTP 抓取链路（重定向守卫）由
 * controller IT + legacy 对齐契约覆盖；此处覆盖纯解析：入口 URL 抽取（中文尾部标点）、videoId、
 * meta/分享描述、JSON 片段收集与排序、时长结构化提取、可播放地址抽取、挑战页检测、空内容 502。
 */
@DisplayName("DouyinResolveService（移植 legacy douyin-resolve.service.ts 解析子集）")
class DouyinResolveServiceTest {

    private final DouyinResolveService service =
            new DouyinResolveService(new DouyinFetchProperties(null, null, 0, 0));

    // ---------------------------------------------------------------- 入口 URL 抽取

    @Test
    @DisplayName("containsAllowedPageUrl：分享文本含抖音链接（中文标点收尾）通过")
    void containsAllowedPageUrlWithCjkPunctuation() {
        assertThat(DouyinResolveService.containsAllowedPageUrl(
                "7.43 abc复制打开抖音，看看作品！ https://v.douyin.com/iAbCdEf/ 复制此链接")).isTrue();
        assertThat(DouyinResolveService.containsAllowedPageUrl(
                "https://www.iesdouyin.com/share/video/7123456789012345678）")).isTrue();
        assertThat(DouyinResolveService.containsAllowedPageUrl("https://www.douyin.com/video/123，")).isTrue();
    }

    @Test
    @DisplayName("containsAllowedPageUrl：非抖音/非 https 拒绝")
    void containsAllowedPageUrlRejectsOthers() {
        assertThat(DouyinResolveService.containsAllowedPageUrl("https://evil.com/douyin")).isFalse();
        assertThat(DouyinResolveService.containsAllowedPageUrl("http://v.douyin.com/x")).isFalse();
        assertThat(DouyinResolveService.containsAllowedPageUrl("没有链接")).isFalse();
        assertThat(DouyinResolveService.containsAllowedPageUrl(null)).isFalse();
    }

    @Test
    @DisplayName("extractEntryUrl：取第一个受信链接并裁掉尾部标点；无则 400")
    void extractEntryUrl() {
        assertThat(DouyinResolveService.extractEntryUrl(
                "看看 https://v.douyin.com/iAbCdEf/ 这个作品"))
                .isEqualTo("https://v.douyin.com/iAbCdEf/");
        // 尾部中文标点裁剪（CJK 标点全集见 TRAILING_PUNCT）
        assertThat(DouyinResolveService.extractEntryUrl(
                "链接：https://www.douyin.com/video/9，")).isEqualTo("https://www.douyin.com/video/9");
        assertThatThrownBy(() -> DouyinResolveService.extractEntryUrl("https://evil.com/x"))
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    // ---------------------------------------------------------------- videoId

    @Test
    @DisplayName("extractVideoId：video/modal_id/aweme_id 三式")
    void extractVideoIdPatterns() {
        assertThat(DouyinResolveService.extractVideoId("https://www.douyin.com/video/7123456789")).isEqualTo("7123456789");
        assertThat(DouyinResolveService.extractVideoId("https://www.douyin.com/x?modal_id=7111")).isEqualTo("7111");
        assertThat(DouyinResolveService.extractVideoId("https://www.iesdouyin.com/share/video/7222?aweme_id=7333"))
                .isEqualTo("7222");
        assertThat(DouyinResolveService.extractVideoId("https://www.douyin.com/user/x")).isNull();
    }

    // ---------------------------------------------------------------- 页面解析

    @Test
    @DisplayName("分享页 fixture：meta/分享描述/时长/可播放地址全量解析")
    void parsesSharePageFixture() {
        String html = """
                <html><head>
                <title>抖音-记录美好生活</title>
                <meta name="description" content="探店vlog：这家面馆绝了 - 美食博主小王于20240515发布在抖音">
                <meta property="og:image" content="https://p3.douyinpic.com/cover.jpg">
                </head><body>
                <script>window.__INITIAL_STATE__ = {"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"video":{"duration":45000,"play_addr":{"uri":"v0200fg10000"}}}]}}}};</script>
                <script id="RENDER_DATA">{"aweme_detail":{"desc":"探店vlog：这家面馆绝了","video":{"play_addr":{"url_list":["https:\\/\\/www.douyinvod.com\\/aaa\\/playwm\\/video.mp4"]}}}}</script>
                </body></html>""";

        DouyinSourceMaterial material = service.parseSourceMaterial(
                "https://v.douyin.com/iAbCdEf/", "https://www.iesdouyin.com/share/video/7123456789/", html);

        assertThat(material.videoId()).isEqualTo("7123456789");
        assertThat(material.title()).isEqualTo("探店vlog：这家面馆绝了");
        assertThat(material.author()).isEqualTo("美食博主小王");
        assertThat(material.coverUrl()).isEqualTo("https://p3.douyinpic.com/cover.jpg");
        assertThat(material.durationSeconds()).isEqualTo(45L);
        assertThat(material.challengePage()).isFalse();
        // url_list 里的地址经 \\/ 归一后由 playwm 正则抽出，host 受信 → 可解析。
        assertThat(material.playableVideoUrl()).isEqualTo("https://www.douyinvod.com/aaa/playwm/video.mp4");
        assertThat(material.assetResolvable()).isTrue();
        assertThat(material.fetchStage()).isEqualTo("page_json");
        assertThat(material.usedSession()).isFalse();
    }

    @Test
    @DisplayName("可播放地址抽取：playwm URL（转义归一后）命中受信主机")
    void extractsPlayableVideoUrlFromSnippet() {
        String snippet = "{\"aweme_detail\":{\"video\":{\"play_addr\":{\"url_list\":[\"https:\\/\\/v26-web.douyinvod.com\\/abc\\/playwm\\/vid.mp4\"]}}}}";
        assertThat(DouyinResolveService.extractPlayAddressFromSnippet(snippet))
                .isEqualTo("https://v26-web.douyinvod.com/abc/playwm/vid.mp4");
    }

    @Test
    @DisplayName("可播放地址抽取：非受信主机 → null（SSRF 边界）")
    void rejectsUntrustedPlayableHost() {
        String snippet = "{\"x\":\"https://evil.com/playwm/vid.mp4\"}";
        assertThat(DouyinResolveService.extractPlayAddressFromSnippet(snippet)).isNull();
    }

    @Test
    @DisplayName("snippet 排序：aweme/RENDER_DATA 关键词权重高者在前")
    void collectsAndRanksJsonSnippets() {
        String html = """
                <html><body>
                <script>var unrelated = 1;</script>
                <script>{"noise": true}</script>
                <script>window.__INITIAL_STATE__ = {"aweme_detail": {}};</script>
                </body></html>""";

        List<String> snippets = DouyinResolveService.collectJsonSnippets(html);

        assertThat(snippets).hasSize(2);
        assertThat(snippets.get(0)).contains("__INITIAL_STATE__");
    }

    // ---------------------------------------------------------------- 时长提取

    @Test
    @DisplayName("时长：loaderData.videoInfoRes.item_list[0].video.duration（毫秒归一）")
    void extractsDurationFromLoaderData() {
        String snippet = "{\"loaderData\":{\"video_(id)/page\":{\"videoInfoRes\":{\"item_list\":[{\"video\":{\"duration\":45000}}]}}}}";
        assertThat(DouyinResolveService.extractDurationFromSnippet(snippet)).contains(45L);
    }

    @Test
    @DisplayName("时长：aweme_detail.video.duration_ms 与秒值直读")
    void extractsDurationFromStructuredCandidates() {
        assertThat(DouyinResolveService.extractDurationFromSnippet(
                "{\"aweme_detail\":{\"video\":{\"duration_ms\":12340}}}")).contains(13L);
        assertThat(DouyinResolveService.extractDurationFromSnippet(
                "{\"awemeDetail\":{\"duration\":58}}")).contains(58L);
    }

    @Test
    @DisplayName("时长：JSON 解析失败时 regex 兜底（snippet.video.duration-regex）")
    void extractsDurationByRegexFallback() {
        // 花括号内容非法 JSON → 结构化提取不可用 → regex 兜底（对齐 legacy fall-through 条件）。
        String snippet = "not json \"video\" : { broken, \"duration_ms\" : 25000 } tail";
        assertThat(DouyinResolveService.extractDurationFromSnippet(snippet)).contains(25L);
    }

    @Test
    @DisplayName("时长归一：≥1000 视为毫秒向上取整，<1000 视为秒")
    void normalizesDurationUnits() {
        assertThat(DouyinResolveService.normalizeDuration(45000)).contains(45L);
        assertThat(DouyinResolveService.normalizeDuration(45001)).contains(46L);
        assertThat(DouyinResolveService.normalizeDuration(45)).contains(45L);
        assertThat(DouyinResolveService.normalizeDuration(0)).isEqualTo(Optional.empty());
    }

    // ---------------------------------------------------------------- 挑战页 / 空内容

    @Test
    @DisplayName("挑战页检测：WAF 线索命中即 challengePage")
    void detectsChallengePage() {
        assertThat(DouyinResolveService.detectChallengePage("<html>window.WAFJS.verify()</html>")).isTrue();
        assertThat(DouyinResolveService.detectChallengePage("<html>安全验证中</html>")).isTrue();
        assertThat(DouyinResolveService.detectChallengePage("<html>正常内容</html>")).isFalse();
    }

    @Test
    @DisplayName("空内容页（非分享元数据）→ 502 未能提取有效内容")
    void emptyPageThrows502() {
        assertThatThrownBy(() -> service.parseSourceMaterial(
                "https://v.douyin.com/x/", "https://www.douyin.com/fail", "<html><head></head><body></body></html>"))
                .isInstanceOfSatisfying(IntelligenceException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.getMessage()).isEqualTo("未能从抖音页面提取有效内容");
                });
    }

    @Test
    @DisplayName("分享页即使正文为空，有 videoId 也不算空内容（looksLikeShareMetadata）")
    void sharePageWithVideoIdIsNotEmpty() {
        DouyinSourceMaterial material = service.parseSourceMaterial(
                "https://v.douyin.com/x/",
                "https://www.iesdouyin.com/share/video/7123456789/",
                "<html><head></head><body></body></html>");
        assertThat(material.videoId()).isEqualTo("7123456789");
        assertThat(material.assetResolvable()).isFalse();
    }

    // ---------------------------------------------------------------- 分享描述解析

    @Test
    @DisplayName("parseShareMetaDescription：拆「内容 - 作者于YYYYMMDD发布在抖音」")
    void parsesShareMetaDescription() {
        var meta = DouyinResolveService.parseShareMetaDescription(
                "  探店vlog   -   美食博主小王于20240515发布在抖音 ");
        assertThat(meta.title()).isEqualTo("探店vlog");
        assertThat(meta.author()).isEqualTo("美食博主小王");

        var noAuthor = DouyinResolveService.parseShareMetaDescription("只有标题");
        assertThat(noAuthor.title()).isEqualTo("只有标题");
        assertThat(noAuthor.author()).isNull();

        var empty = DouyinResolveService.parseShareMetaDescription(null);
        assertThat(empty.title()).isNull();
    }
}
