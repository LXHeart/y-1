package com.grassland.intelligence.bilibili;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Bilibili proxy/download 控制器端到端（草场 Slice 13 Stage 4）。progressive 的 Range 透传由
 * {@code VideoRangeProxyTest} 覆盖；本 IT 聚焦 DASH Java 合流路径与 token 凭证错误映射。
 */
@DisplayName("Bilibili proxy/download（草场 Slice 13 Stage 4）")
class BilibiliProxyControllerIT extends IntelligenceItSupport {

    private static final String SECRET = "test-bilibili-secret-32-chars-min!!";
    private static final String VIDEO_TRACK = "https://upos-sz-mirrorali.bilivideo.com/video.m4s";
    private static final String AUDIO_TRACK = "https://upos-sz-mirrorali.bilivideo.com/audio.m4s";

    @Autowired
    BilibiliProxyToken tokenCodec;

    @DynamicPropertySource
    static void bilibiliProps(DynamicPropertyRegistry registry) {
        registry.add("bilibili.proxy.token-secret", () -> SECRET);
    }

    @Test
    @DisplayName("无结构 token → 400")
    void malformedTokenReturns400() {
        client().get().uri("/api/bilibili/proxy/not-a-valid-token").exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("篡改签名 → 403")
    void tamperedTokenReturns403() {
        String token = tokenCodec.create(new BilibiliMediaTarget.Dash(VIDEO_TRACK, AUDIO_TRACK, null, null, null));
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        client().get().uri("/api/bilibili/proxy/" + tampered).exchange().expectStatus().isEqualTo(403);
    }

    @Test
    @DisplayName("过期 token → 410")
    void expiredTokenReturns410() throws InterruptedException {
        BilibiliProxyToken shortTtl = new BilibiliProxyToken(new BilibiliProxyTokenProperties(SECRET, 1));
        String token = shortTtl.create(new BilibiliMediaTarget.Dash(VIDEO_TRACK, AUDIO_TRACK, null, null, null));
        Thread.sleep(30);

        client().get().uri("/api/bilibili/proxy/" + token).exchange().expectStatus().isEqualTo(410);
    }

}
