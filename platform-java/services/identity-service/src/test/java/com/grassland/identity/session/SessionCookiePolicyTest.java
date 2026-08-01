package com.grassland.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class SessionCookiePolicyTest {
    private static SessionCookiePolicy policy(String secureMode) {
        return new SessionCookiePolicy("y1.sid", secureMode, "Lax", 0);
    }

    private static ServerHttpRequest http() {
        return MockServerHttpRequest.get("http://backend:3000/api/auth/login").build();
    }

    private static ServerHttpRequest https() {
        return MockServerHttpRequest.get("https://example.com/api/auth/login").build();
    }

    private static ServerHttpRequest forwarded(String proto) {
        return MockServerHttpRequest.get("http://backend:3000/api/auth/login")
            .header("X-Forwarded-Proto", proto)
            .build();
    }

    @Test
    void autoModeFollowsForwardedProto() {
        SessionCookiePolicy p = policy("auto");
        assertThat(p.isSecure(forwarded("https"))).isTrue();
        assertThat(p.isSecure(forwarded("http"))).isFalse();
    }

    @Test
    void autoModeUsesLeftmostHopOfMultiValueForwardedProto() {
        // 多跳会追加成 "https, http"；最左是客户端到最外层入口的协议。
        assertThat(policy("auto").isSecure(forwarded("https, http"))).isTrue();
        assertThat(policy("auto").isSecure(forwarded("http, https"))).isFalse();
    }

    @Test
    void autoModeFallsBackToRequestScheme() {
        assertThat(policy("auto").isSecure(https())).isTrue();
        assertThat(policy("auto").isSecure(http())).isFalse();
    }

    @Test
    void alwaysModeIgnoresRequestScheme() {
        assertThat(policy("always").isSecure(http())).isTrue();
        assertThat(policy("always").isSecure(forwarded("http"))).isTrue();
    }

    @Test
    void neverModeIgnoresHttps() {
        assertThat(policy("never").isSecure(https())).isFalse();
        assertThat(policy("never").isSecure(forwarded("https"))).isFalse();
    }

    @Test
    void booleanStyleSecureValuesAreAccepted() {
        assertThat(policy("true").isSecure(http())).isTrue();
        assertThat(policy("1").isSecure(http())).isTrue();
        assertThat(policy("false").isSecure(https())).isFalse();
        assertThat(policy("0").isSecure(https())).isFalse();
    }

    @Test
    void blankSecureModeDefaultsToAuto() {
        assertThat(policy("").isSecure(https())).isTrue();
        assertThat(policy(null).isSecure(https())).isTrue();
        assertThat(policy("").isSecure(http())).isFalse();
    }

    @Test
    void unsupportedSecureModeFailsFast() {
        assertThatThrownBy(() -> policy("maybe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cookie-secure");
    }

    @Test
    void setCookieCarriesSecureOnlyWhenRequested() {
        SessionCookiePolicy p = policy("auto");
        assertThat(p.buildSetCookie("abc", true))
            .isEqualTo("y1.sid=abc; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800; Secure");
        assertThat(p.buildSetCookie("abc", false))
            .isEqualTo("y1.sid=abc; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800");
    }

    @Test
    void clearCookieMirrorsWriteAttributes() {
        // 属性不一致时浏览器可能留下同名 cookie，登出后旧 sid 仍随请求发出。
        SessionCookiePolicy p = policy("always");
        String cleared = p.buildClearCookie(true);
        assertThat(cleared).isEqualTo(
            "y1.sid=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax; Secure");
        assertThat(p.buildClearCookie(false)).doesNotContain("Secure");
    }

    @Test
    void maxAgeOverrideAppliesToSetCookie() {
        SessionCookiePolicy p = new SessionCookiePolicy("y1.sid", "never", "Strict", 3_600_000L);
        assertThat(p.maxAgeMs()).isEqualTo(3_600_000L);
        assertThat(p.buildSetCookie("v", false)).contains("SameSite=Strict").contains("Max-Age=3600");
    }

    @Test
    void sameSiteIsNormalizedAndValidated() {
        assertThat(new SessionCookiePolicy("y1.sid", "auto", "strict", 0).sameSite()).isEqualTo("Strict");
        assertThat(new SessionCookiePolicy("y1.sid", "auto", "  none ", 0).sameSite()).isEqualTo("None");
        assertThat(new SessionCookiePolicy("y1.sid", "auto", "", 0).sameSite()).isEqualTo("Lax");
        assertThatThrownBy(() -> new SessionCookiePolicy("y1.sid", "auto", "sometimes", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SameSite");
    }

    @Test
    void customCookieNameIsUsed() {
        assertThat(new SessionCookiePolicy("app.sid", "auto", "Lax", 0).buildSetCookie("v", true))
            .startsWith("app.sid=v;");
    }
}
