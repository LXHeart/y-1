package com.grassland.trust.judge;

import com.grassland.trust.security.TrustServiceAssertionIssuer;
import com.grassland.http.ManagedWebClientFactory;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Reads an account's complete, authoritative organization memberships from identity. */
@Component
public class IdentityOrganizationMembershipClient {

    private final WebClient webClient;
    private final TrustServiceAssertionIssuer issuer;
    private final String headerName;
    private final Duration timeout;

    public IdentityOrganizationMembershipClient(
            TrustServiceAssertionIssuer issuer,
            @Value("${identity.service.base-url:http://identity-service:8082}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName,
            @Value("${identity.service.membership-timeout-seconds:3}") long timeoutSeconds) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.timeout = Duration.ofSeconds(Math.max(1, Math.min(timeoutSeconds, 30)));
        this.webClient = ManagedWebClientFactory.create(
                IdentityOrganizationMembershipClient.class, baseUrl, timeout);
    }

    public Mono<Set<String>> organizationIds(String accountId) {
        return webClient.get()
                .uri("/internal/identity/accounts/{accountId}/organization-memberships", accountId)
                .header(headerName, issuer.issueService("grassland-identity"))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 200) {
                        return response.bodyToMono(MembershipResponse.class)
                                .switchIfEmpty(Mono.error(new MembershipException(
                                        "empty identity membership response")))
                                .map(body -> validate(accountId, body));
                    }
                    return response.releaseBody().then(Mono.error(new MembershipException(
                            "identity membership endpoint returned HTTP " + status)));
                })
                .timeout(timeout)
                .onErrorMap(error -> error instanceof MembershipException
                        ? error
                        : new MembershipException("invalid identity membership response", error));
    }

    private record MembershipResponse(boolean success, MembershipData data) {}

    private record MembershipData(String accountId, List<String> organizationIds) {}

    private static Set<String> validate(String requestedAccountId, MembershipResponse response) {
        if (response == null || !response.success() || response.data() == null
                || !requestedAccountId.equals(response.data().accountId())
                || response.data().organizationIds() == null) {
            throw new MembershipException("invalid identity membership envelope");
        }
        List<String> organizationIds = response.data().organizationIds();
        HashSet<String> unique = new HashSet<>();
        for (String organizationId : organizationIds) {
            if (organizationId == null || !isCanonicalUuid(organizationId) || !unique.add(organizationId)) {
                throw new MembershipException("invalid identity organization memberships");
            }
        }
        return Set.copyOf(unique);
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    public static final class MembershipException extends RuntimeException {
        public MembershipException(String message) {
            super(message);
        }

        public MembershipException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
