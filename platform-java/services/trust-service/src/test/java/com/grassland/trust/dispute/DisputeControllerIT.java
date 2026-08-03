package com.grassland.trust.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.trust.TrustItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 争议端到端（草场 Epic 6 Slice 6A）。继承 {@link TrustItSupport}。
 * 覆盖：open（成功+幂等/角色门禁）、活跃争议查询（marketplace 服务断言 200 / 404 / org 自查）、decide（open→final 手动终局/重复 409）。
 */
class DisputeControllerIT extends TrustItSupport {

    @Test
    void merchantOpensDisputeAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng, "reason", "未履约"))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("open")
                .jsonPath("$.data.openedByRole").isEqualTo("merchant")
                .jsonPath("$.data.engagementRef").isEqualTo(eng)
                .jsonPath("$.data.organizationId").isEqualTo(org);
        assertThat(outboxCount("DisputeOpened", eng)).isEqualTo(1);
    }

    @Test
    void openIsIdempotentPerEngagement() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
        client().post().uri("/api/trust/disputes").header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng)).exchange().expectStatus().isCreated();
        client().post().uri("/api/trust/disputes").header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng)).exchange().expectStatus().isOk();
        assertThat(outboxCount("DisputeOpened", eng)).isEqualTo(1);  // 不再写事件
    }

    @Test
    void openRequiresPartyRole() {
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "consumer", null, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", UUID.randomUUID().toString()))
                .exchange().expectStatus().isForbidden();
    }

    /** 非当事方：marketplace 授权拒绝 → 403，且不创建争议/不写事件（Slice 12 安全收口）。 */
    @Test
    void openRejectedWhenMarketplaceDeniesParty() {
        denyAuthorization();
        String merchant = UUID.randomUUID().toString();
        String eng = UUID.randomUUID().toString();
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng))
                .exchange().expectStatus().isForbidden();
        assertThat(outboxCount("DisputeOpened", eng)).isZero();
    }

    /** engagementRef 非 UUID → HTTP 边界 400，不进 DB cast / outbox。 */
    @Test
    void openRejectsNonUuidEngagementRef() {
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", "not-a-uuid"))
                .exchange().expectStatus().isBadRequest();
    }

    /** D-03：marketplace 服务可代商家开 merchant_rejection 案，并由 SLA 内部端点默认 for_recommender 终局。 */
    @Test
    void marketplaceOpensAndAutoFinalizesMerchantRejection() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
        Map<String, Object> resp = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "engagementRef", eng,
                        "kind", "merchant_rejection",
                        "openedByAccountId", merchant,
                        "organizationId", org,
                        "reason", "系统核实与实际不符"))
                .exchange().expectStatus().isCreated().expectBody(Map.class)
                .returnResult().getResponseBody();
        String disputeId = (String) ((Map<?, ?>) resp.get("data")).get("id");
        assertThat(disputeId).isNotBlank();

        client().get().uri("/api/trust/engagements/" + eng + "/open-dispute")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.kind").isEqualTo("merchant_rejection")
                .jsonPath("$.data.openedByAccountId").isEqualTo(merchant);

        client().post().uri("/api/trust/internal/disputes/" + disputeId + "/auto-finalize")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.finalDecision").isEqualTo("for_recommender");
        assertThat(outboxCount("DisputeFinalized", eng)).isEqualTo(1);

        // 重试幂等：已 final 不重复发终局事件。
        client().post().uri("/api/trust/internal/disputes/" + disputeId + "/auto-finalize")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk();
        assertThat(outboxCount("DisputeFinalized", eng)).isEqualTo(1);
    }

    @Test
    void merchantRejectionInternalEndpointsRejectWrongCallerAndKind() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
        String standard = open(merchant, org, eng);
        // 普通争议不能走 SLA 自动终局。
        client().post().uri("/api/trust/internal/disputes/" + standard + "/auto-finalize")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isEqualTo(409);
        // 终端用户不能代开 merchant_rejection kind（请求 kind 被强制归 standard）。
        String another = UUID.randomUUID().toString();
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "engagementRef", another, "kind", "merchant_rejection"))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.kind").isEqualTo("standard");
    }

    @Test
    void marketplaceServiceQueriesOpenDispute() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
        open(merchant, org, eng);
        client().get().uri("/api/trust/engagements/" + eng + "/open-dispute")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.engagementRef").isEqualTo(eng)
                .jsonPath("$.data.status").isEqualTo("open");
    }

    @Test
    void openDisputeQuery404WhenNone() {
        client().get().uri("/api/trust/engagements/app-missing/open-dispute")
                .header("X-Grassland-Identity", signService(UUID.randomUUID().toString(), "marketplace"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void decideFlipsToFinalAndClearsActive() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
        String id = open(merchant, org, eng);
        client().post().uri("/api/trust/disputes/" + id + "/decide")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "in_merchant_favor"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.decision").isEqualTo("in_merchant_favor")
                .jsonPath("$.data.finalDecision").isEqualTo("in_merchant_favor");
        assertThat(outboxCount("DisputeDecided", eng)).isEqualTo(1);
        // decide 终局后活跃争议查询 → 404（DisputeChecker 返 false，结算不再 held）
        client().get().uri("/api/trust/engagements/" + eng + "/open-dispute")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void decideAlreadyDecidedConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
        String id = open(merchant, org, eng);
        decide(merchant, org, id);
        client().post().uri("/api/trust/disputes/" + id + "/decide")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "in_merchant_favor"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void decideOtherOrgForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
        String id = open(merchant, org, eng);
        client().post().uri("/api/trust/disputes/" + id + "/decide")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "in_merchant_favor"))
                .exchange().expectStatus().isForbidden();
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private String open(String merchant, String org, String eng) {
        Map<String, Object> resp = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private void decide(String merchant, String org, String id) {
        client().post().uri("/api/trust/disputes/" + id + "/decide")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "in_merchant_favor"))
                .exchange().expectStatus().isOk();
    }

    private long outboxCount(String eventType, String engagementRef) {
        return db.sql("SELECT COUNT(*)::int AS c FROM trust_outbox"
                        + " WHERE event_type = :et AND payload->>'engagementRef' = :ref")
                .bind("et", eventType).bind("ref", engagementRef)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }
}
