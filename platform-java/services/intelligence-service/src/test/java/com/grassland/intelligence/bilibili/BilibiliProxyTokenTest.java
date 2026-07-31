package com.grassland.intelligence.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link BilibiliProxyToken} 编解码单测（移植 legacy {@code bilibili-proxy.service.ts} 契约）。 */
class BilibiliProxyTokenTest {

    private static final String SECRET = "test-bilibili-secret-32-chars-min!!";
    private static final String BILIBILI_VIDEO = "https://upos-sz-mirrorali.bilivideo.com/v.mp4";

    private final BilibiliProxyToken token = new BilibiliProxyToken(new BilibiliProxyTokenProperties(SECRET, 90_000));

    @Test
    @DisplayName("progressive create → parse 往返")
    void progressiveRoundtrip() {
        Map<String, String> headers = Map.of("Referer", "https://www.bilibili.com/", "User-Agent", "ua");
        String encoded = token.create(new BilibiliMediaTarget.Progressive(BILIBILI_VIDEO, headers, "v.mp4", 120L));

        BilibiliMediaTarget parsed = token.parse(encoded);
        assertThat(parsed).isInstanceOf(BilibiliMediaTarget.Progressive.class);
        BilibiliMediaTarget.Progressive p = (BilibiliMediaTarget.Progressive) parsed;
        assertThat(p.playableVideoUrl()).isEqualTo(BILIBILI_VIDEO);
        assertThat(p.filename()).isEqualTo("v.mp4");
        assertThat(p.durationSeconds()).isEqualTo(120L);
        assertThat(p.requestHeaders()).containsEntry("Referer", "https://www.bilibili.com/");
    }

    @Test
    @DisplayName("dash create → parse 往返")
    void dashRoundtrip() {
        String encoded = token.create(new BilibiliMediaTarget.Dash(
                "https://upos-sz-mirrorali.bilivideo.com/video.m4s",
                "https://upos-sz-mirrorali.bilivideo.com/audio.m4s",
                Map.of(), null, 60L));

        BilibiliMediaTarget parsed = token.parse(encoded);
        assertThat(parsed).isInstanceOf(BilibiliMediaTarget.Dash.class);
        BilibiliMediaTarget.Dash d = (BilibiliMediaTarget.Dash) parsed;
        assertThat(d.videoTrackUrl()).isEqualTo("https://upos-sz-mirrorali.bilivideo.com/video.m4s");
        assertThat(d.audioTrackUrl()).isEqualTo("https://upos-sz-mirrorali.bilivideo.com/audio.m4s");
        assertThat(d.durationSeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("请求头白名单清洗：仅保留 referer/user-agent/origin")
    void headerSanitization() {
        Map<String, String> headers = Map.of("Referer", "https://www.bilibili.com/", "Cookie", "leak", "X-Evil", "x");
        String encoded = token.create(new BilibiliMediaTarget.Progressive(BILIBILI_VIDEO, headers, null, null));

        BilibiliMediaTarget.Progressive p = (BilibiliMediaTarget.Progressive) token.parse(encoded);
        assertThat(p.requestHeaders()).containsOnlyKeys("Referer");
    }

    @Test
    @DisplayName("篡改签名 → 403")
    void tamperedSignatureRejected() {
        String encoded = token.create(new BilibiliMediaTarget.Progressive(BILIBILI_VIDEO, null, null, null));
        char last = encoded.charAt(encoded.length() - 1);
        char swapped = last == 'A' ? 'B' : 'A';
        String tampered = encoded.substring(0, encoded.length() - 1) + swapped;

        assertThatThrownBy(() -> token.parse(tampered))
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    @DisplayName("结构非法（无分隔点）→ 400")
    void malformedTokenRejected() {
        assertThatThrownBy(() -> token.parse("not-a-valid-token"))
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("progressive 非 https 地址 → 400")
    void nonHttpsProgressiveRejected() {
        // create 不校验 scheme；parse 校验（与 legacy 一致）
        String encoded = token.create(new BilibiliMediaTarget.Progressive("http://insecure/v.mp4", null, null, null));
        assertThatThrownBy(() -> token.parse(encoded))
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("过期 → 410")
    void expiredTokenRejected() throws InterruptedException {
        BilibiliProxyToken shortTtl = new BilibiliProxyToken(new BilibiliProxyTokenProperties(SECRET, 1));
        String encoded = shortTtl.create(new BilibiliMediaTarget.Progressive(BILIBILI_VIDEO, null, null, null));
        Thread.sleep(30);

        assertThatThrownBy(() -> token.parse(encoded))
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(410));
    }

    @Test
    @DisplayName("密钥缺失/过短 → 500（懒校验）")
    void missingSecretRejected() {
        BilibiliProxyToken noSecret = new BilibiliProxyToken(new BilibiliProxyTokenProperties("short", 90_000));
        assertThatThrownBy(() -> noSecret.create(new BilibiliMediaTarget.Progressive(BILIBILI_VIDEO, null, null, null)))
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(500));
    }
}
