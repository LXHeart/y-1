package com.grassland.trust.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.trust.risk.RiskModels.RegisterSignalRequest;
import com.grassland.trust.risk.RiskModels.Registration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

class MarketplaceReputationEventConsumerTest {
    @Test
    void merchantCancelCreatesIdempotentAccountSignalAndAcknowledges() {
        RiskService risk = mock(RiskService.class);
        RiskRepository repository = mock(RiskRepository.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        when(repository.countSignals("account", "merchant-1", "merchant_cancelled_engagement"))
                .thenReturn(Mono.just(2));
        when(risk.register(any(), any())).thenReturn(Mono.just(new Registration(null, null, true)));
        MarketplaceReputationEventConsumer consumer = new MarketplaceReputationEventConsumer(
                new ObjectMapper().findAndRegisterModules(), risk, repository);
        String json = """
                {"eventId":"event-1","eventType":"EngagementRefundedOnCancel",
                 "occurredAt":"2026-08-15T02:00:00Z","payload":{"reason":"merchant_cancel",
                 "taskId":"task-1","applicationId":"app-1","organizationId":"org-1",
                 "taskOwnerId":"merchant-1","recommenderAccountId":"rec-1"}}
                """;

        consumer.onEvent(new ConsumerRecord<>("marketplace", 0, 1L, "event-1", json), acknowledgment).block();

        ArgumentCaptor<RegisterSignalRequest> request = ArgumentCaptor.forClass(RegisterSignalRequest.class);
        verify(risk).register(request.capture(), any());
        assertThat(request.getValue().sourceRef()).isEqualTo("event-1");
        assertThat(request.getValue().subjectRef()).isEqualTo("merchant-1");
        assertThat(request.getValue().ruleCode()).isEqualTo("merchant_cancelled_engagement");
        assertThat(request.getValue().occurrenceCount()).isEqualTo(3);
        assertThat(request.getValue().evidence()).containsEntry("applicationId", "app-1");
        verify(acknowledgment).acknowledge();
    }
}
