package com.grassland.identity.notify.external;

import com.grassland.http.ManagedWebClientFactory;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Provider-neutral gateway contract used by an internal Push/SMS adapter service. */
@Component
public class ExternalDeliveryGateway {
    private final WebClient client = ManagedWebClientFactory.create(ExternalDeliveryGateway.class);
    private final String pushEndpoint;
    private final String pushToken;
    private final String smsEndpoint;
    private final String smsToken;
    private final boolean allowInsecureHttp;
    private final String allowedHosts;

    public ExternalDeliveryGateway(
            @Value("${identity.external-delivery.push.endpoint:}") String pushEndpoint,
            @Value("${identity.external-delivery.push.token:}") String pushToken,
            @Value("${identity.external-delivery.sms.endpoint:}") String smsEndpoint,
            @Value("${identity.external-delivery.sms.token:}") String smsToken,
            @Value("${identity.external-delivery.allow-insecure-http:false}") boolean allowInsecureHttp,
            @Value("${identity.external-delivery.allowed-hosts:}") String allowedHosts) {
        this.pushEndpoint = pushEndpoint;
        this.pushToken = pushToken;
        this.smsEndpoint = smsEndpoint;
        this.smsToken = smsToken;
        this.allowInsecureHttp = allowInsecureHttp;
        this.allowedHosts = allowedHosts;
    }

    public Mono<Void> send(ExternalDeliveryRepository.Row row) {
        String endpoint = "push".equals(row.channel()) ? pushEndpoint : smsEndpoint;
        String token = "push".equals(row.channel()) ? pushToken : smsToken;
        if (endpoint == null || endpoint.isBlank()) {
            return Mono.error(new IllegalStateException(row.channel() + " gateway is not configured"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", row.provider());
        payload.put("recipient", row.recipient());
        payload.put("title", row.title());
        payload.put("body", row.body());
        if (row.linkPath() != null) {
            payload.put("linkPath", row.linkPath());
        }
        URI target = ManagedWebClientFactory.requireConfiguredEndpoint(
                endpoint, allowInsecureHttp, allowedHosts);
        WebClient.RequestBodySpec request = client.post().uri(target);
        if (token != null && !token.isBlank()) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
        return request.bodyValue(payload).retrieve().toBodilessEntity().then();
    }
}
