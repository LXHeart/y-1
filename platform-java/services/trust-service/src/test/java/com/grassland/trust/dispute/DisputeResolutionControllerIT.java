package com.grassland.trust.dispute;

import com.grassland.trust.TrustItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DisputeResolutionControllerIT extends TrustItSupport {

    @Autowired
    private DisputeCaseRepository disputes;

    @Test
    void marketplaceReadsAuthoritativeFinalResolution() {
        String org = UUID.randomUUID().toString();
        String engagementRef = "app-" + UUID.randomUUID();
        DisputeCase dispute = disputes.create(
                engagementRef,
                org,
                UUID.randomUUID().toString(),
                "merchant",
                "reason",
                "standard").block();
        disputes.decide(dispute.id(), "for_recommender").block();

        client().get().uri("/api/trust/disputes/" + dispute.id() + "/resolution")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.disputeId").isEqualTo(dispute.id())
                .jsonPath("$.data.engagementRef").isEqualTo(engagementRef)
                .jsonPath("$.data.organizationId").isEqualTo(org)
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.finalDecision").isEqualTo("for_recommender")
                .jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.updatedAt").isNotEmpty();
    }

    @Test
    void resolutionRejectsTerminalUsersAndOtherServices() {
        String org = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        DisputeCase dispute = disputes.create(
                "app-" + UUID.randomUUID(), org, merchant, "merchant", "reason", "standard").block();
        disputes.decide(dispute.id(), "for_merchant").block();

        client().get().uri("/api/trust/disputes/" + dispute.id() + "/resolution")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/trust/disputes/" + dispute.id() + "/resolution")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void resolutionRejectsMismatchedOrganization() {
        String org = UUID.randomUUID().toString();
        DisputeCase dispute = disputes.create(
                "app-" + UUID.randomUUID(),
                org,
                UUID.randomUUID().toString(),
                "merchant",
                "reason",
                "standard").block();
        disputes.decide(dispute.id(), "for_merchant").block();

        client().get().uri("/api/trust/disputes/" + dispute.id() + "/resolution")
                .header("X-Grassland-Identity", signService(
                        UUID.randomUUID().toString(), "marketplace"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void nonFinalDisputeReturnsConflict() {
        String org = UUID.randomUUID().toString();
        DisputeCase dispute = disputes.create(
                "app-" + UUID.randomUUID(),
                org,
                UUID.randomUUID().toString(),
                "merchant",
                "reason",
                "standard").block();

        client().get().uri("/api/trust/disputes/" + dispute.id() + "/resolution")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void unknownDisputeReturnsNotFound() {
        String org = UUID.randomUUID().toString();
        client().get().uri("/api/trust/disputes/" + UUID.randomUUID() + "/resolution")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isNotFound();
    }
}
