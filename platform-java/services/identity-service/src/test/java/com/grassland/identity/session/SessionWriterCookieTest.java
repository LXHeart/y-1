package com.grassland.identity.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.user.AuthUser;
import org.junit.jupiter.api.Test;

/**
 * GL-P0-AUTH-001：Set-Cookie 与落库的 {@code sess.cookie} 必须表达同一套属性。
 *
 * <p>express-session 的 rolling 续期会读库里的 cookie 属性重发 Set-Cookie。
 * 若 Java 写 {@code secure:false} 而实际需要 Secure，下一次续期就会把 Secure 抹掉，
 * 会话 cookie 从此可在明文连接上回传 —— 这是本项要修的核心缺口，故单独断言。
 */
class SessionWriterCookieTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AuthUser USER = new AuthUser("u-1", "a@b.com", "Tester", "user", "active");

    private static SessionWriter writer(SessionCookiePolicy policy) {
        // buildSessJson / buildSetCookieHeader 不触库，DatabaseClient 传 null 即可。
        return new SessionWriter(null, new CookieSigner("0123456789abcdef0123456789abcdef"), policy);
    }

    @Test
    void secureSessionWritesSecureCookieAndMatchingSessJson() throws Exception {
        SessionWriter w = writer(new SessionCookiePolicy("y1.sid", "always", "Lax", 0));

        assertThat(w.buildSetCookieHeader("sid-1", true)).contains("; Secure");

        JsonNode cookie = MAPPER.readTree(w.buildSessJson(USER, true)).path("cookie");
        assertThat(cookie.path("secure").asBoolean()).isTrue();
        assertThat(cookie.path("httpOnly").asBoolean()).isTrue();
        assertThat(cookie.path("sameSite").asText()).isEqualTo("lax");
        assertThat(cookie.path("path").asText()).isEqualTo("/");
    }

    @Test
    void insecureSessionWritesNoSecureFlagOnBothSides() throws Exception {
        SessionWriter w = writer(new SessionCookiePolicy("y1.sid", "never", "Lax", 0));

        assertThat(w.buildSetCookieHeader("sid-1", false)).doesNotContain("Secure");
        assertThat(MAPPER.readTree(w.buildSessJson(USER, false)).path("cookie").path("secure").asBoolean()).isFalse();
    }

    @Test
    void sessJsonMaxAgeTracksPolicy() throws Exception {
        SessionWriter w = writer(new SessionCookiePolicy("y1.sid", "auto", "Strict", 3_600_000L));

        JsonNode cookie = MAPPER.readTree(w.buildSessJson(USER, true)).path("cookie");
        assertThat(cookie.path("originalMaxAge").asLong()).isEqualTo(3_600_000L);
        assertThat(cookie.path("sameSite").asText()).isEqualTo("strict");
        assertThat(w.buildSetCookieHeader("sid-1", true)).contains("Max-Age=3600");
    }

    @Test
    void signedCookieValueStaysExpressCompatible() {
        SessionWriter w = writer(new SessionCookiePolicy("y1.sid", "always", "Lax", 0));

        // express-session 值形如 s:<sid>.<mac>，URL 编码后 "s:" → "s%3A"。
        assertThat(w.buildSetCookieHeader("sid-1", true)).contains("y1.sid=s%3Asid-1.");
    }

    @Test
    void sessJsonKeepsUserPayload() throws Exception {
        SessionWriter w = writer(new SessionCookiePolicy("y1.sid", "auto", "Lax", 0));

        JsonNode user = MAPPER.readTree(w.buildSessJson(USER, false)).path("user");
        assertThat(user.path("id").asText()).isEqualTo("u-1");
        assertThat(user.path("email").asText()).isEqualTo("a@b.com");
        assertThat(user.path("displayName").asText()).isEqualTo("Tester");
        assertThat(user.path("role").asText()).isEqualTo("user");
    }
}
