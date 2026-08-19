package com.grassland.identity.recommenderprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.identityprofile.IdentityProfileRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.user.AuthUser;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@SuppressWarnings("unchecked")
class RecommenderVerificationControllerTest {

    private CurrentAccountResolver accounts;
    private RecommenderVerificationRepository requests;
    private IdentityProfileRepository identities;
    private OutboxRepository outbox;
    private TransactionalOperator transactions;
    private RecommenderVerificationController controller;
    private final ServerHttpRequest request = mock(ServerHttpRequest.class);
    private final AuthUser user = new AuthUser(
            UUID.randomUUID().toString(), "recommender@example.com", "推荐官", "user", "active");

    @BeforeEach
    void setUp() {
        accounts = mock(CurrentAccountResolver.class);
        requests = mock(RecommenderVerificationRepository.class);
        identities = mock(IdentityProfileRepository.class);
        outbox = mock(OutboxRepository.class);
        transactions = mock(TransactionalOperator.class);
        when(accounts.resolve(any())).thenReturn(Mono.just(user));
        when(accounts.requireRole(any(), any(BackendRole[].class))).thenReturn(Mono.just(new AuthUser(
                UUID.randomUUID().toString(), "reviewer@example.com", "审核员", "admin", "active")));
        when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outbox.append(any())).thenReturn(Mono.empty());
        controller = new RecommenderVerificationController(
                accounts, requests, identities, outbox, transactions, 259200);
    }

    @Test
    void submitRequiresRecommenderIdentity() {
        when(identities.findByAccountAndType(anyString(), anyString())).thenReturn(Mono.empty());

        assertThatThrownBy(() -> controller.submit(
                new RecommenderVerificationController.SubmitVerificationRequest("{}"), request).block())
                .isInstanceOfSatisfying(com.grassland.identity.auth.IdentityException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void submitRejectsDuplicatePendingRequest() {
        when(identities.findByAccountAndType(anyString(), anyString()))
                .thenReturn(Mono.just(mock(com.grassland.identity.identityprofile.IdentityProfile.class)));
        when(requests.findLatestByAccount(user.id())).thenReturn(Mono.just(sample("pending")));

        assertThatThrownBy(() -> controller.submit(
                new RecommenderVerificationController.SubmitVerificationRequest("{}"), request).block())
                .isInstanceOfSatisfying(com.grassland.identity.auth.IdentityException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void rejectRequiresReviewNote() {
        assertThatThrownBy(() -> controller.reject(
                UUID.randomUUID().toString(), new RecommenderVerificationController.ReviewRequest("  "), request).block())
                .isInstanceOfSatisfying(com.grassland.identity.auth.IdentityException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void listPendingUsesReviewerRoleGate() {
        when(requests.findPending()).thenReturn(reactor.core.publisher.Flux.just(sample("pending")));
        var response = controller.listPending(request).block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
    }

    private RecommenderVerificationRequest sample(String status) {
        return new RecommenderVerificationRequest(
                UUID.randomUUID(), user.id(), "{}", status, null, null,
                Instant.now().plusSeconds(3600), Instant.now(), Instant.now());
    }
}
