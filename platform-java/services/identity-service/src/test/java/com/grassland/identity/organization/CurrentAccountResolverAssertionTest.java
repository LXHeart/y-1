package com.grassland.identity.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.test.StepVerifier;

/**
 * CurrentAccountResolver 断言消费（Slice 2K / HLD 7.4）：有效断言解析（无需 cookie）、失效断言回退 cookie、断言优先于 cookie。
 * 继承 {@link IdentityItSupport}（已注入 signer bean + seedAccount）。
 */
class CurrentAccountResolverAssertionTest extends IdentityItSupport {

    @Autowired
    CurrentAccountResolver resolver;

    @Autowired
    IdentityAssertionSigner signer;

    @Test
    void validAssertion_resolvesWithoutCookie() {
        Seeded seeded = seedAccount("assert-valid@grassland.local");
        String token = sign(seeded.accountId(), "merchant", "sid-from-bff");

        StepVerifier.create(resolver.resolvePrincipal(request(token, null)))
                .assertNext(p -> {
                    assertThat(p.user().id()).isEqualTo(seeded.accountId());
                    assertThat(p.sid()).isEqualTo("sid-from-bff");
                })
                .verifyComplete();
    }

    @Test
    void invalidAssertion_fallsBackToCookie() {
        Seeded seeded = seedAccount("assert-fallback@grassland.local");

        // 垃圾断言 + 有效 cookie → 回退 cookie 路径解析成功。
        StepVerifier.create(resolver.resolvePrincipal(request("garbage.token", seeded.cookie())))
                .assertNext(p -> assertThat(p.user().id()).isEqualTo(seeded.accountId()))
                .verifyComplete();
    }

    @Test
    void invalidAssertion_noCookie_isUnauthorized() {
        StepVerifier.create(resolver.resolvePrincipal(request("garbage.token", null)))
                .verifyError();
    }

    @Test
    void validAssertion_beatsCookie() {
        Seeded a = seedAccount("assert-wins-a@grassland.local");
        Seeded b = seedAccount("assert-wins-b@grassland.local");

        // 断言指向 b、cookie 指向 a → 断言优先（b）。
        String tokenForB = sign(b.accountId(), "merchant", "sid-b");
        StepVerifier.create(resolver.resolvePrincipal(request(tokenForB, a.cookie())))
                .assertNext(p -> assertThat(p.user().id()).isEqualTo(b.accountId()))
                .verifyComplete();
    }

    private String sign(String accountId, String active, String sid) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                accountId, active, sid, "cookie-session", "level1", null, "r", "t",
                "grassland-internal", now, now.plusSeconds(60)));
    }

    private static ServerHttpRequest request(String assertion, String cookie) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/auth/me");
        if (assertion != null) {
            builder.header("X-Grassland-Identity", assertion);
        }
        if (cookie != null) {
            builder.cookie(new HttpCookie("y1.sid", cookie));
        }
        return builder.build();
    }
}
