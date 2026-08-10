package com.grassland.identity.identityprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** 启用单活动设备策略的独立上下文；默认 0 的既有多设备并存语义由 IdentitySessionControllerIT 覆盖。 */
@TestPropertySource(properties = "identity.active-identity.session-policy.max-active-per-account=1")
class IdentitySessionPolicyControllerIT extends IdentityItSupport {

    @Test
    void activatingSecondDeviceDeactivatesOldestWithoutLoggingItOut() {
        var acc = seedAccount("is-policy-one@example.com");
        String device2 = cookieFor(acc.accountId());
        openIdentity(acc.cookie());

        activate(acc.cookie());
        activate(device2);

        client().get().uri("/api/me/active-identity").header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.activeIdentityType").isEmpty();
        client().get().uri("/api/me/active-identity").header("Cookie", "y1.sid=" + device2)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.activeIdentityType").isEqualTo("recommender");

        // 策略只切回消费者，不撤销登录态。
        client().get().uri("/api/auth/me").header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isOk();

        Integer policyAudit = db.sql("SELECT COUNT(*)::int AS c FROM identity_audit_log"
                        + " WHERE account_id = CAST(:acct AS uuid) AND action = 'policy_deactivate'")
                .bind("acct", acc.accountId()).map(row -> row.get("c", Integer.class)).one().block();
        assertThat(policyAudit).isEqualTo(1);
    }

    @Test
    void concurrentActivationsCannotBypassAccountLimit() {
        var acc = seedAccount("is-policy-concurrent@example.com");
        String device2 = cookieFor(acc.accountId());
        openIdentity(acc.cookie());

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> activate(acc.cookie()));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> activate(device2));
        CompletableFuture.allOf(first, second).join();

        Integer active = db.sql("SELECT COUNT(*)::int AS c FROM identity_session"
                        + " WHERE account_id = CAST(:acct AS uuid) AND active_identity_type IS NOT NULL")
                .bind("acct", acc.accountId()).map(row -> row.get("c", Integer.class)).one().block();
        Integer policyAudit = db.sql("SELECT COUNT(*)::int AS c FROM identity_audit_log"
                        + " WHERE account_id = CAST(:acct AS uuid) AND action = 'policy_deactivate'")
                .bind("acct", acc.accountId()).map(row -> row.get("c", Integer.class)).one().block();

        assertThat(active).isEqualTo(1);
        assertThat(policyAudit).isEqualTo(1);
    }

    private void openIdentity(String cookie) {
        client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"type\":\"recommender\"}").exchange().expectStatus().isCreated();
    }

    private void activate(String cookie) {
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"type\":\"recommender\"}").exchange().expectStatus().isOk();
    }
}
