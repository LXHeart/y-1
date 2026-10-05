package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.Test;

class DigitalHumanRetirementIT extends IntelligenceItSupport {
    @Test void activeHttpRoutesAreGoneEvenWithInvalidBody() {
        client().post().uri("/api/digital-human/sessions").bodyValue("invalid-json")
                .exchange().expectStatus().isEqualTo(410).expectBody().jsonPath("$.code").isEqualTo("dh_retired");
        client().post().uri("/api/digital-human/sessions/any/resume")
                .exchange().expectStatus().isEqualTo(410);
        client().put().uri("/api/admin/digital-human/config").bodyValue("invalid-json")
                .exchange().expectStatus().isEqualTo(410);
    }
    @Test void historyRetainsItsAuthenticationBoundary() {
        client().get().uri("/api/digital-human/sessions")
                .exchange().expectStatus().isUnauthorized();
    }
}
