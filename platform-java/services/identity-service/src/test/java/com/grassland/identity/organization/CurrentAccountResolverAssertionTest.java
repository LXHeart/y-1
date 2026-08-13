package com.grassland.identity.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.auth.IdentityException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.test.StepVerifier;

/**
 * CurrentAccountResolver 断言消费（Slice 2K / HLD 7.4）：有效断言解析（无需 cookie）、失效断言 fail-closed、断言优先于 cookie。
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
    void invalidAssertionDoesNotFallBackToCookie() {
        Seeded seeded = seedAccount("assert-fallback@grassland.local");

        // 内部断言头存在却无效，不能靠同时携带的 cookie 绕过 replay/验签失败。
        StepVerifier.create(resolver.resolvePrincipal(request("garbage.token", seeded.cookie())))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IdentityException.class);
                    assertThat(((IdentityException) error).status()).isEqualTo(401);
                })
                .verify();
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

    @Test
    void inactiveAccountIsRejectedForAssertionAndCookiePaths() {
        Seeded seeded = seedAccount("assert-inactive@grassland.local");
        db.sql("UPDATE app_users SET status = 'suspended' WHERE id = CAST(:id AS uuid)")
                .bind("id", seeded.accountId()).then().block();

        String token = sign(seeded.accountId(), "merchant", "sid-from-bff");
        StepVerifier.create(resolver.resolvePrincipal(request(token, null)))
                .expectErrorSatisfies(error -> assertInactive(error))
                .verify();
        StepVerifier.create(resolver.resolvePrincipal(request(null, seeded.cookie())))
                .expectErrorSatisfies(error -> assertInactive(error))
                .verify();
    }

    @Test
    void legacyAdminWithoutBackendRolesDoesNotRetainAdminAccess() {
        Seeded seeded = seedAccount("legacy-admin@grassland.local");
        db.sql("UPDATE app_users SET role = 'admin' WHERE id = CAST(:id AS uuid)")
                .bind("id", seeded.accountId()).then().block();

        StepVerifier.create(resolver.requireAdmin(request(null, seeded.cookie())))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IdentityException.class);
                    assertThat(((IdentityException) error).status()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    void backendRolesOverrideStaleLegacyAdminRole() {
        Seeded seeded = seedAccount("stale-legacy-admin@grassland.local");
        db.sql("UPDATE app_users SET role = 'admin' WHERE id = CAST(:id AS uuid)")
                .bind("id", seeded.accountId()).then().block();
        db.sql("INSERT INTO backend_role(account_id, role) VALUES (CAST(:id AS uuid), 'customer_service')")
                .bind("id", seeded.accountId()).then().block();

        StepVerifier.create(resolver.requireAdmin(request(null, seeded.cookie())))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IdentityException.class);
                    assertThat(((IdentityException) error).status()).isEqualTo(403);
                })
                .verify();
    }

    private static void assertInactive(Throwable error) {
        assertThat(error).isInstanceOf(IdentityException.class);
        assertThat(((IdentityException) error).status()).isEqualTo(403);
        assertThat(error).hasMessage("当前账号不可用");
    }

    private String sign(String accountId, String active, String sid) {
        Instant now = Instant.now();
        return com.grassland.identity.assertion.TestAssertionHelper.userSigner("edge-bff", "grassland-identity").sign(new IdentityAssertion(
                accountId, active, sid, null, null, "cookie-session", "level1", null, "r", "t",
                "grassland-identity", now, now.plusSeconds(60), null, null));
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
