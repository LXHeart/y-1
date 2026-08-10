package com.grassland.identity.notify.external;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ExternalDeliveryPublisherTest {
    private final ExternalDeliveryRepository repository = mock(ExternalDeliveryRepository.class);
    private final ExternalDeliveryGateway gateway = mock(ExternalDeliveryGateway.class);
    private final ExternalDeliveryProperties properties = new ExternalDeliveryProperties(
            true, 10, 2, Duration.ofMinutes(1), 3,
            Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofSeconds(5), "secret");
    private final ExternalDeliveryPublisher publisher =
            new ExternalDeliveryPublisher(repository, gateway, properties);

    @Test
    void successMarksSent() {
        var row = row(1);
        when(gateway.send(row)).thenReturn(Mono.empty());
        when(repository.markSent(row)).thenReturn(Mono.just(true));

        StepVerifier.create(publisher.deliver(row)).verifyComplete();
        verify(repository).markSent(row);
    }

    @Test
    void finalFailureMarksDead() {
        var row = row(3);
        when(gateway.send(row)).thenReturn(Mono.error(new RuntimeException("down")));
        when(repository.markFailure(row, true, Duration.ofSeconds(4), "RuntimeException"))
                .thenReturn(Mono.just(true));

        StepVerifier.create(publisher.deliver(row)).verifyComplete();
        verify(repository).markFailure(row, true, Duration.ofSeconds(4), "RuntimeException");
    }

    private static ExternalDeliveryRepository.Row row(int attempt) {
        return new ExternalDeliveryRepository.Row(
                UUID.randomUUID(), "push", "token", "expo", "title", "body", "/me",
                UUID.randomUUID(), attempt);
    }
}

