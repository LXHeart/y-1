package com.grassland.finance.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 账户端到端（草场 Epic 4 Slice 4D）。继承 {@link FinanceItSupport}（注入 signer + db）。
 *
 * <p>覆盖：开户首次 201 + outbox AccountProvisioned + 余额 0；重复开户 200（既有，不再写 outbox）；
 * 查余额 200；查别家 org 403；非 merchant 403；无断言 401；未知 org 404。org/account 用随机 UUID（跨服务无 FK）。
 */
class AccountControllerIT extends FinanceItSupport {

    @Test
    void provisionFirstTimeAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.organizationId").isEqualTo(org)
                .jsonPath("$.data.balanceCents").isEqualTo(0)
                .jsonPath("$.data.currency").isEqualTo("CNY");

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM finance_outbox"
                        + " WHERE event_type = 'AccountProvisioned' AND payload->>'organizationId' = :org")
                .bind("org", org)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void provisionIsIdempotent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isCreated();
        // 重复开户（同 org）→ 200 既有，不再写 outbox
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk();

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM finance_outbox"
                        + " WHERE event_type = 'AccountProvisioned' AND payload->>'organizationId' = :org")
                .bind("org", org)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void readBalance() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        client().get().uri("/api/finance/accounts/" + org)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.balanceCents").isEqualTo(0);
    }

    @Test
    void readOtherOrgBalanceForbidden() {
        String merchant = UUID.randomUUID().toString();
        String ownOrg = UUID.randomUUID().toString();
        String otherOrg = UUID.randomUUID().toString();
        provision(merchant, ownOrg);
        client().get().uri("/api/finance/accounts/" + otherOrg)
                .header("X-Grassland-Identity", sign(merchant, "merchant", ownOrg, "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void nonMerchantForbidden() {
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender", UUID.randomUUID().toString(), null))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void missingAssertionUnauthorized() {
        client().post().uri("/api/finance/accounts")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void unknownAccountNotFound() {
        String merchant = UUID.randomUUID().toString();
        String ownOrg = UUID.randomUUID().toString();
        // caller 属 ownOrg，但查的 org 从未开户 → 404（路径 orgId != caller.org 会先 403，故用 caller 自己的 org 但未开户）
        client().get().uri("/api/finance/accounts/" + ownOrg)
                .header("X-Grassland-Identity", sign(merchant, "merchant", ownOrg, "basic_publish"))
                .exchange().expectStatus().isNotFound();
    }

    private void provision(String merchant, String org) {
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isCreated();
    }
}
