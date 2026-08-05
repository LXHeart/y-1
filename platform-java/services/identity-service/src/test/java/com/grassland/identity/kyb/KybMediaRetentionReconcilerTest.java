package com.grassland.identity.kyb;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class KybMediaRetentionReconcilerTest {

    @Test
    void renewsClaimedLiveCommandAndMarksItSynced() {
        KybMediaRetentionCommandRepository repository = mock(KybMediaRetentionCommandRepository.class);
        KybMediaClient mediaClient = mock(KybMediaClient.class);
        KybMediaRetentionProperties properties = properties();
        UUID mediaId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        KybMediaRetentionCommand command = new KybMediaRetentionCommand(
                mediaId, referenceId, organizationId, "attachment", "live", null,
                Instant.now(), "pending", 1, claimToken);
        Instant leaseUntil = Instant.now().plusSeconds(properties.liveLeaseSeconds());
        when(repository.expireSealed()).thenReturn(Mono.just(0L));
        when(repository.claimBatch(eq(properties.batchSize()), any(),
                eq(properties.claimLease()), eq(properties.renewAhead())))
                .thenReturn(Flux.just(command));
        when(mediaClient.acquireLease(mediaId, organizationId, referenceId, "attachment",
                properties.liveLeaseSeconds()))
                .thenReturn(Mono.just(new KybMediaRetentionReceipt(
                        mediaId, referenceId, "attachment", leaseUntil, null)));
        when(repository.markSynced(mediaId, referenceId, claimToken, leaseUntil))
                .thenReturn(Mono.just(true));

        new KybMediaRetentionReconciler(repository, mediaClient, properties).reconcileOnce().block();

        verify(repository).markSynced(mediaId, referenceId, claimToken, leaseUntil);
    }

    @Test
    void failedRemoteCallLeavesCommandPendingWithBackoff() {
        KybMediaRetentionCommandRepository repository = mock(KybMediaRetentionCommandRepository.class);
        KybMediaClient mediaClient = mock(KybMediaClient.class);
        KybMediaRetentionProperties properties = properties();
        UUID mediaId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        KybMediaRetentionCommand command = new KybMediaRetentionCommand(
                mediaId, referenceId, organizationId, "attachment", "released", null,
                null, "pending", 2, claimToken);
        when(repository.expireSealed()).thenReturn(Mono.just(0L));
        when(repository.claimBatch(eq(properties.batchSize()), any(),
                eq(properties.claimLease()), eq(properties.renewAhead())))
                .thenReturn(Flux.just(command));
        when(mediaClient.release(mediaId, organizationId, referenceId))
                .thenReturn(Mono.error(new IllegalStateException("unavailable")));
        when(repository.markFailure(eq(mediaId), eq(referenceId), eq(claimToken),
                any(Duration.class), eq("IllegalStateException"))).thenReturn(Mono.just(true));

        new KybMediaRetentionReconciler(repository, mediaClient, properties).reconcileOnce().block();

        verify(repository).markFailure(eq(mediaId), eq(referenceId), eq(claimToken),
                any(Duration.class), eq("IllegalStateException"));
    }

    private static KybMediaRetentionProperties properties() {
        return new KybMediaRetentionProperties(
                true, 2_000, 50, 4, 60_000,
                604_800, 172_800, 1_000, 60_000, 2_555, 365);
    }
}
