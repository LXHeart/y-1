package com.grassland.intelligence.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BilibiliResolveService} 纯逻辑单测：WBI mixin-key 置换（已知向量）、花括号 JSON 块抽取、
 * page URL 预检、buildMaterial（progressive/DASH/422）。HTTP 抓取/重定向/超时映射与 VideoRangeProxy、
 * DouyinHotItemsService 同构，不在此重复。
 */
@DisplayName("BilibiliResolveService 解析逻辑")
class BilibiliResolveServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BilibiliResolveService service = new BilibiliResolveService(new BilibiliFetchProperties(null, 0));

    @Test
    @DisplayName("WBI mixin-key 置换与已知向量一致（SocialSisterYi/bilibili-API-collect）")
    void wbiMixinKeyMatchesKnownVector() {
        assertThat(BilibiliResolveService.buildWbiMixinKey(
                "7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45"))
                .isEqualTo("ea1db124af3c7062474693fa704f4ff8");
    }

    @Test
    @DisplayName("extractFirstJsonBlock：花括号深度匹配，字符串内 brace 不误判")
    void extractsFirstJsonBlock() throws Exception {
        String html = "prefix window.__INITIAL_STATE__={\"a\":1,\"b\":{\"s\":\"}\"},\"c\":2} trailing";
        assertThat(BilibiliResolveService.extractFirstJsonBlock(html, "window.__INITIAL_STATE__="))
                .isEqualTo("{\"a\":1,\"b\":{\"s\":\"}\"},\"c\":2}");

        assertThat(BilibiliResolveService.extractFirstJsonBlock("no marker here", "window.__playinfo__="))
                .isNull();
    }

    @Test
    @DisplayName("containsAllowedPageUrl：bilibili page host 通过，其余拒绝")
    void containsAllowedPageUrl() {
        assertThat(BilibiliResolveService.containsAllowedPageUrl("看 https://www.bilibili.com/video/BV1x 酷")).isTrue();
        assertThat(BilibiliResolveService.containsAllowedPageUrl("https://b23.tv/abc)")).isTrue();
        assertThat(BilibiliResolveService.containsAllowedPageUrl("no url")).isFalse();
        assertThat(BilibiliResolveService.containsAllowedPageUrl("http://www.bilibili.com/x")).isFalse();
        assertThat(BilibiliResolveService.containsAllowedPageUrl("https://evil.com/x")).isFalse();
    }

    @Test
    @DisplayName("buildMaterial：progressive（data.durl[0].url）")
    void buildsProgressive() throws Exception {
        JsonNode state = MAPPER.readTree("""
                {"bvid":"BV1x","videoData":{"title":"标题","owner":{"name":"作者"},"pic":"https://pic","duration":120}}""");
        JsonNode playInfo = MAPPER.readTree("""
                {"data":{"durl":[{"url":"https://upos-sz-mirrorali.bilivideo.com/v.mp4"}],"timelength":120000}}""");

        BilibiliSourceMaterial material = service.buildMaterial(
                "https://www.bilibili.com/video/BV1x", "https://www.bilibili.com/video/BV1x", state, playInfo);

        assertThat(material).isInstanceOf(BilibiliSourceMaterial.Progressive.class);
        assertThat(material.playbackMode()).isEqualTo("progressive");
        assertThat(((BilibiliSourceMaterial.Progressive) material).playableVideoUrl())
                .isEqualTo("https://upos-sz-mirrorali.bilivideo.com/v.mp4");
        assertThat(material.title()).isEqualTo("标题");
        assertThat(material.durationSeconds()).isEqualTo(120L);
    }

    @Test
    @DisplayName("buildMaterial：DASH（video/audio 首轨，backupUrl 兜底）")
    void buildsDash() throws Exception {
        JsonNode state = MAPPER.readTree("""
                {"bvid":"BV1x","videoData":{"title":"t","duration":60}}""");
        JsonNode playInfo = MAPPER.readTree("""
                {"data":{"dash":{"video":[{"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/v.m4s","backupUrl":["https://upos-sz-mirrorhw.bilivideo.com/vb.m4s"]}],
                "audio":[{"baseUrl":"https://upos-sz-mirrorali.bilivideo.com/a.m4s"}]},"timelength":60000}}""");

        BilibiliSourceMaterial material = service.buildMaterial(
                "https://www.bilibili.com/video/BV1x", "https://www.bilibili.com/video/BV1x", state, playInfo);

        assertThat(material).isInstanceOf(BilibiliSourceMaterial.Dash.class);
        BilibiliSourceMaterial.Dash dash = (BilibiliSourceMaterial.Dash) material;
        assertThat(dash.videoTrackUrl()).isEqualTo("https://upos-sz-mirrorali.bilivideo.com/v.m4s");
        assertThat(dash.audioTrackUrl()).isEqualTo("https://upos-sz-mirrorali.bilivideo.com/a.m4s");
    }

    @Test
    @DisplayName("buildMaterial：progressive 与 DASH 均无可信轨 → 422")
    void noPlayableReturns422() throws Exception {
        JsonNode state = MAPPER.readTree("{\"bvid\":\"BV1x\",\"videoData\":{\"title\":\"t\"}}");
        JsonNode playInfo = MAPPER.readTree("{\"data\":{}}");

        assertThatThrownBy(() -> service.buildMaterial(
                "https://www.bilibili.com/video/BV1x", "https://www.bilibili.com/video/BV1x", state, playInfo))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(422));
    }
}
