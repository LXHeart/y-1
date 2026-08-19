package com.grassland.marketplace.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.analytics.MarketingAttributionModels.AlertCandidate;
import com.grassland.marketplace.analytics.MarketingAttributionModels.CampaignRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MarketingAttributionRepositoryIT extends MarketplaceItSupport {
    @Autowired
    private MarketingAttributionRepository repository;

    @Test
    void campaignWebhookInboxAndAlertsAreDurableAndIdempotent() {
        String org = UUID.randomUUID().toString();
        String account = UUID.randomUUID().toString();
        String external = "campaign-" + UUID.randomUUID();
        MarketingAttributionModels.Campaign campaign = repository.create(
                new CampaignRequest("meta", external, org, null, null, null), account).block();
        assertThat(campaign).isNotNull();
        assertThat(repository.findActive("meta", external).block().id()).isEqualTo(campaign.id());

        String eventId = "event-" + UUID.randomUUID();
        assertThat(repository.claimWebhook("meta", eventId, "hash").block())
                .isEqualTo(MarketingAttributionRepository.WebhookClaim.CLAIMED);
        assertThat(repository.claimWebhook("meta", eventId, "hash").block())
                .isEqualTo(MarketingAttributionRepository.WebhookClaim.CLAIMED);
        repository.markWebhookProcessed("meta", eventId).block();
        assertThat(repository.claimWebhook("meta", eventId, "hash").block())
                .isEqualTo(MarketingAttributionRepository.WebhookClaim.DUPLICATE);
        assertThat(repository.claimWebhook("meta", eventId, "other").block())
                .isEqualTo(MarketingAttributionRepository.WebhookClaim.PAYLOAD_CONFLICT);

        repository.syncAlerts(org, null, List.of(
                new AlertCandidate("negative_roi", "critical", "negative", -0.2d, 0d))).block();
        MarketingAttributionModels.Alert alert = repository.listAlerts(org, null, false).next().block();
        assertThat(alert).isNotNull();
        assertThat(repository.acknowledge(alert.id(), account).block()).isTrue();
        repository.syncAlerts(org, null, List.of(
                new AlertCandidate("negative_roi", "critical", "still negative", -0.3d, 0d))).block();
        assertThat(repository.listAlerts(org, null, false).next().block().status()).isEqualTo("acknowledged");
    }
}
