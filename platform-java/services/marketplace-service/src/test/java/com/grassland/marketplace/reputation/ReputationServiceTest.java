package com.grassland.marketplace.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ReputationServiceTest {

    @Mock
    private ReputationRepository reputations;
    @Mock
    private ReputationPolicyRepository policies;
    @Mock
    private Lv5AdmissionRepository admissions;

    @Test
    void snapshotsEvaluateMultipleAccountsThroughOneBatchReadPerSource() {
        String lv1 = "00000000-0000-0000-0000-000000000001";
        String lv2 = "00000000-0000-0000-0000-000000000002";
        List<String> accountIds = List.of(lv1, lv2);
        ReputationStats lv2Stats = new ReputationStats(6, 6, 0, 0, 0, 0,
                null, null, Instant.now());

        when(reputations.statsOfAccounts(accountIds)).thenReturn(Mono.just(Map.of(
                lv1, ReputationStats.empty(), lv2, lv2Stats)));
        when(policies.findCurrent()).thenReturn(Mono.just(ReputationPolicy.defaults()));
        when(admissions.findAll(accountIds)).thenReturn(Mono.just(Map.of()));

        ReputationService service = new ReputationService(reputations, policies, admissions);
        Map<String, ReputationSnapshot> snapshots = service.snapshots(accountIds).block();

        assertThat(snapshots).isNotNull();
        assertThat(snapshots.get(lv2).evaluation().taskPriorityWeight()).isEqualTo(110);
        assertThat(snapshots.get(lv1).evaluation().taskPriorityWeight()).isEqualTo(100);
        assertThatThrownBy(() -> snapshots.put(lv1, snapshots.get(lv2)))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(reputations).statsOfAccounts(accountIds);
        verify(policies).findCurrent();
        verify(admissions).findAll(accountIds);
        verify(reputations, never()).statsOf(lv1);
        verify(reputations, never()).statsOf(lv2);
        verify(admissions, never()).find(lv1);
        verify(admissions, never()).find(lv2);
    }

    @Test
    void snapshotsDeduplicatesAccountsAndAppliesLv5Admission() {
        String accountId = "00000000-0000-0000-0000-000000000005";
        List<String> deduplicated = List.of(accountId);
        ReputationStats lv5Stats = new ReputationStats(100, 100, 0, 0, 0, 10,
                5.0, null, Instant.now());
        Lv5Admission admitted = new Lv5Admission(accountId, true, 1, null, "approved", Instant.now());

        when(reputations.statsOfAccounts(deduplicated)).thenReturn(Mono.just(Map.of(accountId, lv5Stats)));
        when(policies.findCurrent()).thenReturn(Mono.just(ReputationPolicy.defaults()));
        when(admissions.findAll(deduplicated)).thenReturn(Mono.just(Map.of(accountId, admitted)));

        ReputationService service = new ReputationService(reputations, policies, admissions);
        Map<String, ReputationSnapshot> snapshots = service.snapshots(List.of(accountId, accountId)).block();

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(accountId).evaluation().effectiveLevel()).isEqualTo(RecommenderLevel.LV5);
        verify(reputations).statsOfAccounts(deduplicated);
        verify(admissions).findAll(deduplicated);
    }

    @Test
    void snapshotsOfEmptyInputSkipsAllDataSources() {
        ReputationService service = new ReputationService(reputations, policies, admissions);

        assertThat(service.snapshots(List.of()).block()).isEmpty();

        verify(reputations, never()).statsOfAccounts(anyCollection());
        verify(policies, never()).findCurrent();
        verify(admissions, never()).findAll(anyCollection());
    }
}
