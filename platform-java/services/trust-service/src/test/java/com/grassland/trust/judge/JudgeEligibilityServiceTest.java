package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.trust.security.TrustException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JudgeEligibilityServiceTest {

    private final JudgeRepository judges = mock(JudgeRepository.class);
    private final MarketplaceReputationClient reputation = mock(MarketplaceReputationClient.class);
    private final IdentityOrganizationMembershipClient identityMemberships =
            mock(IdentityOrganizationMembershipClient.class);
    private final JudgeEligibilityService service = new JudgeEligibilityService(
            judges, reputation, identityMemberships);

    @BeforeEach
    void noOrganizationMembershipsByDefault() {
        when(identityMemberships.organizationIds(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(Set.of()));
    }

    @Test
    void continuesPastThirtyIneligibleCandidatesUntilSevenAreFound() {
        String organizationId = UUID.randomUUID().toString();
        List<Judge> candidates = new ArrayList<>();
        for (int i = 0; i < 37; i++) {
            Judge judge = judge();
            candidates.add(judge);
            int level = i < 30 ? 4 : 5;
            when(reputation.getLevel(judge.accountId())).thenReturn(Mono.just(
                    new MarketplaceReputationClient.LevelResult(
                            judge.accountId(), "Lv" + level, level, level == 5, 3L)));
        }
        when(judges.streamEligibleCandidates(5, organizationId)).thenReturn(Flux.fromIterable(candidates));

        List<Judge> result = service.drawVerifiedPool(5, organizationId, 7).block();

        assertThat(result).extracting(Judge::accountId)
                .containsExactlyElementsOf(candidates.subList(30, 37).stream().map(Judge::accountId).toList());
        verify(judges).streamEligibleCandidates(5, organizationId);
    }

    @Test
    void excludesExistingPanelMembersBeforeCallingMarketplace() {
        String organizationId = UUID.randomUUID().toString();
        Judge existing = judge();
        List<Judge> candidates = new ArrayList<>();
        candidates.add(existing);
        for (int i = 0; i < 7; i++) {
            Judge candidate = judge();
            candidates.add(candidate);
            when(reputation.getLevel(candidate.accountId())).thenReturn(Mono.just(
                    new MarketplaceReputationClient.LevelResult(
                            candidate.accountId(), "Lv5", 5, true, 3L)));
        }
        when(judges.streamEligibleCandidates(5, organizationId)).thenReturn(Flux.fromIterable(candidates));

        List<Judge> result = service.drawVerifiedPool(5, organizationId, 7, Set.of(existing.accountId())).block();

        assertThat(result).extracting(Judge::accountId)
                .containsExactlyElementsOf(candidates.subList(1, 8).stream().map(Judge::accountId).toList());
        verify(reputation, never()).getLevel(existing.accountId());
    }

    @Test
    void excludesCandidateWhoseCompleteMembershipsContainDisputeOrganization() {
        String disputeOrg = UUID.randomUUID().toString();
        Judge sameOrg = judge();
        Judge independent = judge();
        when(judges.streamEligibleCandidates(5, disputeOrg)).thenReturn(Flux.just(sameOrg, independent));
        when(reputation.getLevel(sameOrg.accountId())).thenReturn(eligible(sameOrg));
        when(reputation.getLevel(independent.accountId())).thenReturn(eligible(independent));
        when(identityMemberships.organizationIds(sameOrg.accountId())).thenReturn(Mono.just(
                Set.of(UUID.randomUUID().toString(), disputeOrg)));

        List<Judge> result = service.drawVerifiedPool(5, disputeOrg, 1).block();

        assertThat(result).containsExactly(independent);
    }

    @Test
    void identityUnavailableAbortsCandidateSelection() {
        String disputeOrg = UUID.randomUUID().toString();
        Judge candidate = judge();
        when(judges.streamEligibleCandidates(5, disputeOrg)).thenReturn(Flux.just(candidate));
        when(reputation.getLevel(candidate.accountId())).thenReturn(eligible(candidate));
        when(identityMemberships.organizationIds(candidate.accountId())).thenReturn(Mono.error(
                new IdentityOrganizationMembershipClient.MembershipException("identity unavailable")));

        assertThatThrownBy(() -> service.drawVerifiedPool(5, disputeOrg, 1).block())
                .isInstanceOf(TrustException.class)
                .hasMessage("身份服务暂时不可用");
    }

    @Test
    void writeBoundaryRechecksEveryFinalPanelAccount() {
        String disputeOrg = UUID.randomUUID().toString();
        String existingAccount = UUID.randomUUID().toString();
        String newAccount = UUID.randomUUID().toString();
        when(identityMemberships.organizationIds(existingAccount)).thenReturn(Mono.just(Set.of()));
        when(identityMemberships.organizationIds(newAccount)).thenReturn(Mono.just(Set.of(
                UUID.randomUUID().toString(), disputeOrg)));

        assertThatThrownBy(() -> service.validateNoOrganizationConflicts(
                        List.of(existingAccount, newAccount), disputeOrg).block())
                .isInstanceOf(TrustException.class)
                .hasMessage("审判官组织归属已变化，请重试抽签");

        verify(identityMemberships).organizationIds(existingAccount);
        verify(identityMemberships).organizationIds(newAccount);
    }

    private Mono<MarketplaceReputationClient.LevelResult> eligible(Judge judge) {
        return Mono.just(new MarketplaceReputationClient.LevelResult(
                judge.accountId(), "Lv5", 5, true, 3L));
    }

    private static Judge judge() {
        return new Judge(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, 5,
                true, true, 1L, Instant.now(), UUID.randomUUID().toString(), Instant.now());
    }
}
