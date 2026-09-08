package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.benefit.ExperienceBenefit;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngagementActionContractTest {
    private final EngagementActionContract contract = new EngagementActionContract(null, null, null, null, 72, 48);
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final Task task = mock(Task.class);
    private final TaskApplication app = mock(TaskApplication.class);

    private EngagementActionContract.Next next(boolean manager, List<EngagementSubmission> submissions,
            ExperienceBenefit benefit, boolean extension) {
        return next(manager, submissions, benefit, extension, null);
    }

    private EngagementActionContract.Next next(boolean manager, List<EngagementSubmission> submissions,
            ExperienceBenefit benefit, boolean extension,
            EngagementExitRequestRepository.EngagementExitRequest exitPending) {
        when(app.status()).thenReturn("accepted");
        return contract.derive(task, app, manager, "not_confirmed", null, null,
                submissions, benefit, extension, exitPending, now);
    }

    /** 任务书 #97 C97-03（TC97-014 契约侧）：开放协商申请 → 双方新待办组与互斥 blockedReason。 */
    @Test
    void openExitRequestSurfacesConfirmOrAwaitPerViewerSide() {
        var pending = new EngagementExitRequestRepository.EngagementExitRequest(
                "exit-1", "app-1", "task-1", "merchant-1", "recommender", "档期冲突",
                "pending", now.plusSeconds(72 * 3600L), null, null, now.minusSeconds(60));
        // 推荐官发起 → 商家（manager）视角待确认（倒计时=响应窗）。
        assertThat(next(true, List.of(), null, false, pending).group()).isEqualTo("exit_pending_confirm");
        assertThat(next(true, List.of(), null, false, pending).dueAt()).isEqualTo(now.plusSeconds(72 * 3600L));
        // 推荐官本人（发起方）视角待回应，互斥入口 blockedReason=exit_request_open。
        var recommenderView = next(false, List.of(), null, false, pending);
        assertThat(recommenderView.group()).isEqualTo("exit_await_response");
        assertThat(recommenderView.blockedReason()).isEqualTo("exit_request_open");
        // 商家发起 → 推荐官视角待确认。
        var merchantInitiated = new EngagementExitRequestRepository.EngagementExitRequest(
                "exit-2", "app-1", "task-1", "merchant-1", "merchant", "内容方向调整",
                "pending", now.plusSeconds(3600), null, null, now.minusSeconds(60));
        assertThat(next(false, List.of(), null, false, merchantInitiated).group()).isEqualTo("exit_pending_confirm");
        assertThat(next(true, List.of(), null, false, merchantInitiated).group()).isEqualTo("exit_await_response");
    }

    @Test
    void draftReviewAndOverdueUseTheSubmissionClockForBothViewers() {
        EngagementSubmission draft = submission("submitted", true, now.minusSeconds(3600), null);
        assertThat(next(true, List.of(draft), null, false).label()).isEqualTo("待审稿");
        var recommender = next(false, List.of(draft), null, false);
        assertThat(recommender.dueAt()).isEqualTo(now.plusSeconds(71 * 3600L));
        assertThat(recommender.blockedReason()).contains("获批");
        draft = submission("submitted", true, now.minusSeconds(72 * 3600L), null);
        assertThat(next(true, List.of(draft), null, false).group()).isEqualTo("manual_review");
    }

    @Test
    void revisionUsesReviewedTimeAndExpiresWithoutInvitingAnotherSubmission() {
        var rejected = submission("rejected", true, now.minusSeconds(10000), now.minusSeconds(3600));
        assertThat(next(false, List.of(rejected), null, false).dueAt()).isEqualTo(now.plusSeconds(47 * 3600L));
        rejected = submission("rejected", true, now.minusSeconds(300000), now.minusSeconds(49 * 3600L));
        assertThat(next(false, List.of(rejected), null, false).group()).isEqualTo("exception");
    }

    @Test
    void publicationWaitsForAcceptanceWithThePersistedDeadline() {
        when(app.merchantConfirmDeadlineAt()).thenReturn(now.plusSeconds(500));
        var published = submission("submitted", false, now.minusSeconds(200), null);
        var result = next(false, List.of(published), null, false);
        assertThat(result.group()).isEqualTo("acceptance");
        assertThat(result.dueAt()).isEqualTo(now.plusSeconds(500));
        assertThat(result.blockedReason()).contains("等待验收");
    }

    @Test
    void benefitClaimPausesDeliveryAndUsesResponseDeadline() {
        ExperienceBenefit benefit = mock(ExperienceBenefit.class);
        when(benefit.defaultClaimOpen()).thenReturn(true);
        when(benefit.defaultDeadlineAt()).thenReturn(now.plusSeconds(600));
        var result = next(false, List.of(), benefit, false);
        assertThat(result.group()).isEqualTo("benefit_default");
        assertThat(result.dueAt()).isEqualTo(now.plusSeconds(600));
        assertThat(result.blockedReason()).contains("暂停");
    }

    @Test
    void extensionDoesNotInventAnApprovalDeadlineOrReplaceDeliveryClock() {
        when(app.deliveryDeadlineAt()).thenReturn(now.plusSeconds(1000));
        var result = next(false, List.of(), null, true);
        assertThat(result.group()).isEqualTo("extension");
        assertThat(result.dueAt()).isEqualTo(now.plusSeconds(1000));
        assertThat(result.blockedReason()).contains("原交付期限仍有效");
    }

    @Test
    void overdueDeliveryUsesRemedyAndLegacyNullDeadlineStaysNull() {
        assertThat(next(false, List.of(), null, false).dueAt()).isNull();
        when(app.deliveryDeadlineAt()).thenReturn(now.minusSeconds(1));
        when(app.remedyDeadlineAt()).thenReturn(now.plusSeconds(500));
        assertThat(next(false, List.of(), null, false).dueAt()).isEqualTo(now.plusSeconds(500));
    }

    @Test
    void observationAndTerminalStatesHaveNoInventedActions() {
        when(app.status()).thenReturn("accepted");
        when(app.confirmedAt()).thenReturn(now.minusSeconds(100));
        assertThat(contract.derive(task, app, false, "settling", null, now.plusSeconds(100),
                List.of(), null, false, null, now).group()).isEqualTo("observation");
        assertThat(contract.derive(task, app, false, "settled", null, null,
                List.of(), null, false, null, now).group()).isEqualTo("completed");
        when(app.status()).thenReturn("withdrawn");
        assertThat(contract.derive(task, app, false, "not_confirmed", null, null,
                List.of(), null, false, null, now).group()).isEqualTo("ended");
    }

    private EngagementSubmission submission(String status, boolean draft, Instant created, Instant reviewed) {
        return new EngagementSubmission("s", "a", "r", "", null, status, null, reviewed,
                created, null, null, null, draft ? "draft" : "published");
    }
}
