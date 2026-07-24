package com.grassland.finance.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * escrow 端到端（草场 Epic 4 Slice 4E）。继承 {@link FinanceItSupport}。
 *
 * <p>覆盖：credit（余额递增/未开户404/别家org403）、reserve（成功扣余额+幂等/余额不足409）、
 * release（还原余额/非reserved409/别家org403/未知404），均验 outbox 事件。
 */
class EscrowControllerIT extends FinanceItSupport {

    @Test
    void creditIncrementsBalanceAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", 1000))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.balanceCents").isEqualTo(1000);
        assertThat(balanceOf(org)).isEqualTo(1000L);
        assertThat(outboxCount("AccountCredited", org)).isEqualTo(1);
    }

    @Test
    void creditWithoutProvisionNotFound() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", 100))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void creditOtherOrgForbidden() {
        String merchant = UUID.randomUUID().toString();
        String ownOrg = UUID.randomUUID().toString();
        provision(merchant, ownOrg);
        client().post().uri("/api/finance/accounts/" + UUID.randomUUID() + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", ownOrg, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", 100))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void reserveDecrementBalanceAndIdempotent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);

        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "amountCents", 600))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("reserved")
                .jsonPath("$.data.amountCents").isEqualTo(600);
        assertThat(balanceOf(org)).isEqualTo(400L);
        assertThat(outboxCount("FundsReserved", org)).isEqualTo(1);

        // 幂等：同 ref 再 reserve → 200 既有，余额不再扣
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "amountCents", 600))
                .exchange().expectStatus().isOk();
        assertThat(balanceOf(org)).isEqualTo(400L);
        assertThat(outboxCount("FundsReserved", org)).isEqualTo(1);  // 不再写事件
    }

    @Test
    void reserveInsufficientFundsConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 500);
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", "eng-" + UUID.randomUUID(), "amountCents", 600))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(balanceOf(org)).isEqualTo(500L);  // 未扣
    }

    @Test
    void releaseRestoresBalance() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600);
        assertThat(balanceOf(org)).isEqualTo(400L);

        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("released");
        assertThat(balanceOf(org)).isEqualTo(1000L);  // 还原
        assertThat(outboxCount("FundsReleased", org)).isEqualTo(1);

        // 再 release → 409（已处理）
        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void releaseOtherOrgForbidden() {
        String merchant = UUID.randomUUID().toString();
        String ownOrg = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, ownOrg);
        credit(merchant, ownOrg, 1000);
        reserve(merchant, ownOrg, ref, 600);
        // 别家 org 的 merchant 试图释放
        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void releaseUnknownNotFound() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        client().post().uri("/api/finance/reservations/eng-missing/release")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void reserveWithoutAssertionUnauthorized() {
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", "x", "amountCents", 100))
                .exchange().expectStatus().isUnauthorized();
    }

    // ---------- helpers ----------

    private void provision(String merchant, String org) {
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isCreated();
    }

    private void credit(String merchant, String org, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", amount))
                .exchange().expectStatus().isOk();
    }

    private void reserve(String merchant, String org, String ref, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "amountCents", amount))
                .exchange().expectStatus().isCreated();
    }

    private long balanceOf(String org) {
        return db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org)
                .map(r -> r.get("balance_cents", Long.class)).one().block();
    }

    private long outboxCount(String eventType, String org) {
        return db.sql("SELECT COUNT(*)::int AS c FROM finance_outbox"
                        + " WHERE event_type = :et AND payload->>'organizationId' = :org")
                .bind("et", eventType).bind("org", org)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }
}
