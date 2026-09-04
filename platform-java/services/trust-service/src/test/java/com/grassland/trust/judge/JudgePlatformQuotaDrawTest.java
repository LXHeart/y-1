package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/**
 * 任务书 #74 卡 D：垂类硬配额抽签（涉案平台熟手 ≥4/7，D3 + 派生 3）。
 * mock 仓储与出站客户端，验证分池/降级/见习席位约束的合成逻辑。
 */
class JudgePlatformQuotaDrawTest {

    private JudgeRepository judges;
    private MarketplaceReputationClient reputation;
    private IdentityOrganizationMembershipClient memberships;
    private JudgeEligibilityService service;

    @BeforeEach
    void setUp() {
        judges = Mockito.mock(JudgeRepository.class);
        reputation = Mockito.mock(MarketplaceReputationClient.class);
        memberships = Mockito.mock(IdentityOrganizationMembershipClient.class);
        service = new JudgeEligibilityService(judges, reputation, memberships);
        when(memberships.organizationIds(anyString())).thenReturn(Mono.just(java.util.Set.of()));
    }

    private Judge judge() {
        return new Judge(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, 5, true, true,
                0, null, null, null, null, "full", null, null, null);
    }

    private void stubCompletions(List<Judge> candidates, Map<String, Integer> completionsByAccount) {
        for (Judge candidate : candidates) {
            int completions = completionsByAccount.getOrDefault(candidate.accountId(), 0);
            when(reputation.getPlatformCompletions(candidate.accountId())).thenReturn(Mono.just(
                    new MarketplaceReputationClient.PlatformCompletions(candidate.accountId(),
                            Map.of("xiaohongshu", completions))));
        }
    }

    @Test
    void skilledPoolFillsQuotaSeatsAndMarksMatched() {
        List<Judge> skilled = List.of(judge(), judge(), judge(), judge(), judge());
        List<Judge> general = List.of(judge(), judge(), judge());
        List<Judge> all = java.util.stream.Stream.concat(skilled.stream(), general.stream()).toList();
        all.forEach(j -> when(reputation.getLevel(j.accountId())).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(j.accountId(), "Lv5", 5, true, 0L))));
        stubCompletions(all, skilled.stream()
                .collect(java.util.stream.Collectors.toMap(Judge::accountId, j -> 5)));
        when(judges.streamEligibleCandidates(JudgeEligibilityService.DRAW_MIN_TIER, "org"))
                .thenReturn(reactor.core.publisher.Flux.fromIterable(all));

        List<JudgeEligibilityService.PanelPick> picks =
                service.drawVerifiedPanel(7, "org", "xiaohongshu", 4, 3, 2, Set.of()).block();

        assertThat(picks).hasSize(7);
        long matched = picks.stream().filter(JudgeEligibilityService.PanelPick::matchedPlatform).count();
        assertThat(matched).isEqualTo(4); // 硬配额：恰好 4/7 熟手席
    }

    @Test
    void scarceSkilledPoolDegradesGracefullyWithout503() {
        // 熟手只有 2 人（配额 4）→ 有多少取多少，通用池补齐，不 503
        List<Judge> skilled = List.of(judge(), judge());
        List<Judge> general = List.of(judge(), judge(), judge(), judge(), judge(), judge());
        List<Judge> all = java.util.stream.Stream.concat(skilled.stream(), general.stream()).toList();
        all.forEach(j -> when(reputation.getLevel(j.accountId())).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(j.accountId(), "Lv5", 5, true, 0L))));
        stubCompletions(all, skilled.stream()
                .collect(java.util.stream.Collectors.toMap(Judge::accountId, j -> 9)));
        when(judges.streamEligibleCandidates(JudgeEligibilityService.DRAW_MIN_TIER, "org"))
                .thenReturn(reactor.core.publisher.Flux.fromIterable(all));

        List<JudgeEligibilityService.PanelPick> picks =
                service.drawVerifiedPanel(7, "org", "xiaohongshu", 4, 3, 2, Set.of()).block();

        assertThat(picks).hasSize(7);
        long matched = picks.stream().filter(JudgeEligibilityService.PanelPick::matchedPlatform).count();
        assertThat(matched).isEqualTo(2); // 熟手不足 → 按可得数降级（派生 3）
    }

    @Test
    void probationSeatsCappedAtTwoButToleratesPoolExhaustion() {
        // 5 名见习熟手：配额 4 全取熟手会超见习帽 → 熟手按 full 优先……本例全见习：
        // 首轮见习帽 2 → 只收 2 席熟手，其余从通用 full 候选补；通用只有 2 人 → 后备轮放宽见习帽补足。
        List<Judge> probationSkilled = List.of(judge(), judge(), judge(), judge(), judge());
        List<Judge> general = List.of(judge(), judge());
        probationSkilled.forEach(j -> when(reputation.getLevel(j.accountId())).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(j.accountId(), "Lv5", 5, true, 0L))));
        general.forEach(j -> when(reputation.getLevel(j.accountId())).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(j.accountId(), "Lv5", 5, true, 0L))));
        List<Judge> all = java.util.stream.Stream.concat(probationSkilled.stream(), general.stream()).toList();
        stubCompletions(all, probationSkilled.stream()
                .collect(java.util.stream.Collectors.toMap(Judge::accountId, j -> 6)));

        // 见习审判官
        List<Judge> probationAll = all.stream().map(j -> new Judge(j.id(), j.accountId(), null, 5, true, true,
                0, null, null, null, null, "probation", null, null, null)).toList();
        when(judges.streamEligibleCandidates(JudgeEligibilityService.DRAW_MIN_TIER, "org"))
                .thenReturn(reactor.core.publisher.Flux.fromIterable(probationAll));

        List<JudgeEligibilityService.PanelPick> picks =
                service.drawVerifiedPanel(7, "org", "xiaohongshu", 4, 3, 2, Set.of()).block();

        // 池尽容忍超编：7 席全为见习（5 熟手 + 2 通用），快照 probationCount 由面板计数暴露
        assertThat(picks).hasSize(7);
        assertThat(picks).allSatisfy(p -> assertThat(p.judge().isProbation()).isTrue());
        long matched = picks.stream().filter(JudgeEligibilityService.PanelPick::matchedPlatform).count();
        assertThat(matched).isEqualTo(4);
    }

    @Test
    void insufficientTotalPoolStillFails503() {
        List<Judge> all = List.of(judge(), judge(), judge());
        all.forEach(j -> when(reputation.getLevel(j.accountId())).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(j.accountId(), "Lv5", 5, true, 0L))));
        when(judges.streamEligibleCandidates(JudgeEligibilityService.DRAW_MIN_TIER, "org"))
                .thenReturn(reactor.core.publisher.Flux.fromIterable(all));

        assertThatThrownBy(() -> service.drawVerifiedPanel(7, "org", "xiaohongshu", 4, 3, 2, Set.of()).block())
                .hasMessageContaining("无足够的合格审判官"); // 总池不足维持 503 现状语义
    }

    @Test
    void nullPlatformFallsBackToGeneralPool() {
        List<Judge> all = List.of(judge(), judge(), judge(), judge(), judge(), judge(), judge());
        all.forEach(j -> when(reputation.getLevel(j.accountId())).thenReturn(Mono.just(
                new MarketplaceReputationClient.LevelResult(j.accountId(), "Lv5", 5, true, 0L))));
        when(judges.streamEligibleCandidates(JudgeEligibilityService.DRAW_MIN_TIER, "org"))
                .thenReturn(reactor.core.publisher.Flux.fromIterable(all));

        // 存量案件无平台信息 → 全走通用池，不发起完成数查询
        List<JudgeEligibilityService.PanelPick> picks =
                service.drawVerifiedPanel(7, "org", null, 4, 3, 2, Set.of()).block();
        assertThat(picks).hasSize(7);
        assertThat(picks).allSatisfy(p -> assertThat(p.matchedPlatform()).isFalse());
    }
}
