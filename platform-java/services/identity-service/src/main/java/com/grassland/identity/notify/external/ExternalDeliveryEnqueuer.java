package com.grassland.identity.notify.external;

import com.grassland.identity.event.IdentityEventEnvelope;
import com.grassland.identity.notification.NotificationTemplates;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ExternalDeliveryEnqueuer {
    private final ExternalDeliveryRepository repository;

    public ExternalDeliveryEnqueuer(ExternalDeliveryRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> enqueue(
            IdentityEventEnvelope envelope,
            NotificationTemplates.Template template,
            List<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Mono.empty();
        }
        String category = template.category().dbValue();
        return Flux.fromIterable(accountIds)
                .concatMap(accountId -> repository.findActiveEndpoints(accountId, category)
                        .concatMap(endpoint -> repository.append(new ExternalDeliveryRepository.Message(
                                envelope.eventId(), accountId, endpoint.channel(), endpoint.address(),
                                endpoint.provider(), template.title(), value(template.body()),
                                template.linkPath(), category))))
                .then();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}

