package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.event.IdentityEventEnvelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 外部服务（marketplace / trust / finance）事件 → 收件人 + 文案路由单测。草场 Slice 12 Stage 3。
 *
 * <p>外部事件的收件人解析<b>只读 payload</b>，不查库；故 {@link DatabaseClient} 用 mock 且不打桩，
 * 若实现偷偷去查库会因 strict stubbing / NPE 立刻暴露。
 */
@ExtendWith(MockitoExtension.class)
class ExternalNotificationRoutingTest {

    private static final String OWNER = "owner-1";
    private static final String RECOMMENDER = "rec-1";

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock private DatabaseClient db;

    @Test
    void merchantSideEventsGoToTaskOwner() {
        for (String eventType : List.of("ApplicationSubmitted", "ApplicationWithdrawn", "DeliverableSubmitted")) {
            assertThat(resolve(eventType, Map.of(
                    "taskId", "task-1", "recommenderAccountId", RECOMMENDER, "taskOwnerId", OWNER)))
                    .as(eventType)
                    .containsExactly(OWNER);
        }
    }

    @Test
    void deliverableRejectedGoesToRecommender() {
        assertThat(resolve("DeliverableRejected", Map.of(
                "taskId", "task-1", "recommenderAccountId", RECOMMENDER, "taskOwnerId", OWNER)))
                .containsExactly(RECOMMENDER);
    }

    @Test
    void applicationResultEventsNotifyRecommenderOnlyWithEngagementTemplate() {
        // 报名结果（#28）：被接受/被拒绝只通知推荐官——商家是操作者，按约定不通知自己刚做的动作。
        for (String eventType : List.of("ApplicationAccepted", "ApplicationRejected")) {
            String status = "ApplicationAccepted".equals(eventType) ? "accepted" : "rejected";
            Map<String, Object> fields = Map.of(
                    "taskId", "task-1", "applicationId", "app-1", "status", status,
                    "taskOwnerId", OWNER, "recommenderAccountId", RECOMMENDER);
            assertThat(resolve(eventType, fields)).as(eventType).containsExactly(RECOMMENDER);

            NotificationTemplates.Template template = NotificationTemplates.template(eventType, payload(fields));
            assertThat(template).as(eventType).isNotNull();
            assertThat(template.category()).as(eventType).isEqualTo(NotificationCategory.ENGAGEMENT);
            assertThat(template.linkPath()).as(eventType).isEqualTo("/me/engagements");
            assertThat(template.payload()).as(eventType)
                    .containsEntry("taskId", "task-1")
                    .containsEntry("applicationId", "app-1")
                    .containsEntry("status", status)
                    .doesNotContainKey("taskOwnerId")
                    .doesNotContainKey("recommenderAccountId");
        }
    }

    @Test
    void applicationResultWithoutRecommenderFieldYieldsNoRecipient() {
        // 发射端缺 recommenderAccountId（防御）→ 空收件人，不产生通知也不报错重试。
        assertThat(resolve("ApplicationAccepted", Map.of("taskId", "task-1", "applicationId", "app-1"))).isEmpty();
        assertThat(resolve("ApplicationRejected", Map.of("taskId", "task-1", "applicationId", "app-1"))).isEmpty();
    }

    @Test
    void acceptanceStartedIntermediateEventProducesNoTemplate() {
        // 资金型任务 accept 的中间态事件不产生通知——预留成功后的终态 ApplicationAccepted 才通知。
        assertThat(NotificationTemplates.template("ApplicationAcceptanceStarted", payload(Map.of(
                "taskId", "task-1", "applicationId", "app-1", "recommenderAccountId", RECOMMENDER)))).isNull();
    }

    @Test
    void taskInvitationGoesToRecommenderAndCarriesDedicatedDeepLink() {
        Map<String, Object> fields = Map.of(
                "taskId", "task-1", "invitationId", "invite-1",
                "recommenderAccountId", RECOMMENDER, "taskOwnerId", OWNER);

        assertThat(resolve("TaskRecommenderInvited", fields)).containsExactly(RECOMMENDER);
        NotificationTemplates.Template template = NotificationTemplates.template(
                "TaskRecommenderInvited", payload(fields));

        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.ENGAGEMENT);
        assertThat(template.linkPath()).isEqualTo("/me/task-invitations");
        assertThat(template.payload())
                .containsEntry("taskId", "task-1")
                .containsEntry("invitationId", "invite-1")
                .doesNotContainKey("recommenderAccountId")
                .doesNotContainKey("taskOwnerId");
    }

    @Test
    void bothPartiesNotifiedOnVerificationAndSettlement() {
        for (String eventType : List.of("VerificationChecked", "EngagementSettled", "SettlementHeld", "EngagementRefundedOnCancel")) {
            assertThat(resolve(eventType, Map.of(
                    "taskId", "task-1", "recommenderAccountId", RECOMMENDER, "taskOwnerId", OWNER)))
                    .as(eventType)
                    .containsExactly(OWNER, RECOMMENDER);
        }
    }

    @Test
    void confirmationWindowEventsNotifyBothPartiesWithEngagementTemplates() {
        for (String eventType : List.of("ConfirmationWindowEntered", "ConfirmationWindowExpiring", "AutoSettledOnTimeout")) {
            Map<String, Object> fields = Map.of(
                    "taskId", "task-1", "applicationId", "app-1", "submissionId", "sub-1",
                    "taskOwnerId", OWNER, "recommenderAccountId", RECOMMENDER);
            assertThat(resolve(eventType, fields)).as(eventType).containsExactly(OWNER, RECOMMENDER);

            NotificationTemplates.Template template = NotificationTemplates.template(eventType, payload(fields));
            assertThat(template).as(eventType).isNotNull();
            assertThat(template.category()).as(eventType).isEqualTo(NotificationCategory.ENGAGEMENT);
            // 渲染 payload 只留业务定位字段，不泄露收件人账号。
            assertThat(template.payload()).as(eventType)
                    .containsEntry("taskId", "task-1")
                    .containsEntry("applicationId", "app-1")
                    .doesNotContainKey("taskOwnerId")
                    .doesNotContainKey("recommenderAccountId");
        }
    }

    @Test
    void merchantContestedNotifiesBothPartiesWithDisputeTemplate() {
        Map<String, Object> fields = Map.of(
                "applicationId", "app-1", "submissionId", "sub-1", "disputeId", "d-1",
                "taskOwnerId", OWNER, "recommenderAccountId", RECOMMENDER);
        assertThat(resolve("MerchantContested", fields)).containsExactly(OWNER, RECOMMENDER);
        NotificationTemplates.Template template = NotificationTemplates.template("MerchantContested", payload(fields));
        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.DISPUTE);
        assertThat(template.payload())
                .containsEntry("disputeId", "d-1")
                .containsEntry("applicationId", "app-1")
                .doesNotContainKey("taskOwnerId")
                .doesNotContainKey("recommenderAccountId");
    }

    @Test
    void sameAccountOnBothSidesNotifiedOnce() {
        assertThat(resolve("EngagementSettled", Map.of(
                "recommenderAccountId", OWNER, "taskOwnerId", OWNER)))
                .containsExactly(OWNER);
    }

    @Test
    void missingEnrichedFieldYieldsNoRecipient() {
        // 发射端未补 taskOwnerId（例如任务已删）→ 空列表，不产生通知也不报错重试。
        assertThat(resolve("ApplicationSubmitted", Map.of("taskId", "task-1"))).isEmpty();
    }

    @Test
    void disputeEventsGoToOpener() {
        for (String eventType : List.of(
                "DisputeAssigned", "AdjudicationReopened", "DisputeDecided",
                "DisputeAppealed", "AdjudicationEscalated", "DisputeFinalized")) {
            assertThat(resolve(eventType, Map.of(
                    "disputeId", "d-1", "openedByAccountId", OWNER, "openedByRole", "merchant")))
                    .as(eventType)
                    .containsExactly(OWNER);
        }
    }

    @Test
    void engagementDisputedNotifiesCounterpartyResolvedByOpenerRole() {
        // merchant 开争议 → 对方是推荐官
        assertThat(resolve("EngagementDisputed", Map.of(
                "disputeId", "d-1", "engagementRef", "app-1",
                "openedByAccountId", OWNER, "openedByRole", "merchant",
                "counterpartyAccountId", RECOMMENDER)))
                .containsExactly(RECOMMENDER);
        // recommender 开争议 → 对方是任务归属商家
        assertThat(resolve("EngagementDisputed", Map.of(
                "disputeId", "d-2", "engagementRef", "app-2",
                "openedByAccountId", RECOMMENDER, "openedByRole", "recommender",
                "counterpartyAccountId", OWNER)))
                .containsExactly(OWNER);
    }

    @Test
    void engagementDisputedTemplateIsRoleAwareAndDoesNotLeakAccounts() {
        NotificationTemplates.Template merchantOpened = NotificationTemplates.template(
                "EngagementDisputed", payload(Map.of(
                        "disputeId", "d-1", "engagementRef", "app-1",
                        "openedByAccountId", OWNER, "counterpartyAccountId", RECOMMENDER,
                        "openedByRole", "merchant")));

        assertThat(merchantOpened).isNotNull();
        assertThat(merchantOpened.category()).isEqualTo(NotificationCategory.DISPUTE);
        assertThat(merchantOpened.linkPath()).isEqualTo("/me/disputes");
        assertThat(merchantOpened.body()).contains("商家对你提交的履约");
        // 渲染 payload 不泄露任何账号；disputeId/engagementRef 供前端定位。
        assertThat(merchantOpened.payload()).containsEntry("disputeId", "d-1")
                .doesNotContainKey("openedByAccountId")
                .doesNotContainKey("counterpartyAccountId");

        NotificationTemplates.Template recommenderOpened = NotificationTemplates.template(
                "EngagementDisputed", payload(Map.of(
                        "disputeId", "d-2", "openedByRole", "recommender")));
        assertThat(recommenderOpened.body()).contains("推荐官对你发布的任务");
    }

    @Test
    void fundEventsGoToPayeeUserAccountNotLedgerAccount() {
        for (String eventType : List.of(
                "FundsReserved", "FundsCaptured", "FundsReleased", "FundsReversed", "AccountCredited")) {
            // accountId 是 finance ledger account，绝不能当收件人。
            assertThat(resolve(eventType, Map.of(
                    "accountId", "ledger-1", "payeeAccountId", RECOMMENDER, "amountCents", 600)))
                    .as(eventType)
                    .containsExactly(RECOMMENDER);
        }
    }

    @Test
    void unknownExternalEventProducesNoTemplate() {
        assertThat(NotificationTemplates.template("SomethingElse", payload(Map.of()))).isNull();
    }

    @Test
    void engagementTemplateCarriesTaskContextAndCategory() {
        NotificationTemplates.Template template = NotificationTemplates.template(
                "DeliverableRejected", payload(Map.of("taskId", "task-1", "reason", "画面模糊")));

        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.ENGAGEMENT);
        assertThat(template.payload()).containsEntry("taskId", "task-1").containsEntry("reason", "画面模糊");
    }

    @Test
    void walletTemplateCarriesNumericAmounts() {
        NotificationTemplates.Template template = NotificationTemplates.template(
                "FundsCaptured", payload(Map.of("engagementRef", "eng-1", "payoutCents", 600, "amountCents", 600)));

        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.WALLET);
        assertThat(template.payload()).containsEntry("payoutCents", 600L).containsEntry("amountCents", 600L);
    }

    @Test
    void disputeTemplateDoesNotLeakOpenerAccount() {
        NotificationTemplates.Template template = NotificationTemplates.template(
                "DisputeFinalized",
                payload(Map.of("disputeId", "d-1", "openedByAccountId", OWNER, "finalDecision", "for_merchant")));

        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.DISPUTE);
        assertThat(template.payload()).doesNotContainKey("openedByAccountId");
        assertThat(template.body()).doesNotContain(OWNER);
    }

    @Test
    void disputeDecidedAndReopenedTemplatesAreDisputeCategoryWithoutLeak() {
        // GL-P1-NOTIFY-001 残留补全：DisputeDecided 携带判决方向供前端渲染，但不泄露账号。
        NotificationTemplates.Template decided = NotificationTemplates.template(
                "DisputeDecided",
                payload(Map.of("disputeId", "d-1", "openedByAccountId", OWNER, "decision", "for_recommender")));

        assertThat(decided).isNotNull();
        assertThat(decided.category()).isEqualTo(NotificationCategory.DISPUTE);
        assertThat(decided.payload()).containsEntry("decision", "for_recommender")
                .doesNotContainKey("openedByAccountId");
        assertThat(decided.body()).doesNotContain(OWNER);

        NotificationTemplates.Template reopened = NotificationTemplates.template(
                "AdjudicationReopened",
                payload(Map.of("disputeId", "d-2", "openedByAccountId", OWNER, "round", 2)));
        assertThat(reopened).isNotNull();
        assertThat(reopened.category()).isEqualTo(NotificationCategory.DISPUTE);
        assertThat(reopened.payload()).doesNotContainKey("openedByAccountId");
    }

    @Test
    void reviewRejectionAndVerificationOverrideNotifyOnlyTaskOwner() {
        for (String eventType : List.of("TaskReviewRejected", "VerificationOverridden")) {
            Map<String, Object> fields = Map.of(
                    "taskId", "task-1", "taskOwnerId", OWNER,
                    "recommenderAccountId", RECOMMENDER, "reason", "人工复核");
            assertThat(resolve(eventType, fields)).as(eventType).containsExactly(OWNER);
            NotificationTemplates.Template template = NotificationTemplates.template(eventType, payload(fields));
            assertThat(template).isNotNull();
            assertThat(template.category()).isEqualTo(NotificationCategory.ENGAGEMENT);
            assertThat(template.payload()).containsEntry("taskId", "task-1")
                    .doesNotContainKey("taskOwnerId").doesNotContainKey("recommenderAccountId");
        }
    }

    @Test
    void fundsReservedAndReversedTemplatesAreWalletCategoryWithAmounts() {
        // GL-P1-NOTIFY-001 残留补全：FundsReserved/FundsReversed 走钱包类，携带金额供前端渲染。
        for (String eventType : List.of("FundsReserved", "FundsReversed")) {
            NotificationTemplates.Template template = NotificationTemplates.template(
                    eventType, payload(Map.of("engagementRef", "eng-1", "amountCents", 600, "payeeAccountId", RECOMMENDER)));

            assertThat(template).as(eventType).isNotNull();
            assertThat(template.category()).as(eventType).isEqualTo(NotificationCategory.WALLET);
            assertThat(template.payload()).as(eventType).containsEntry("amountCents", 600L)
                    .doesNotContainKey("payeeAccountId");
        }
    }


    // ---------- 审判官激励（任务书 #31 / ADR-D15 D7） ----------

    @Test
    void judgeVoteRewardedNotifiesVotingJudgeWithDisputeTemplate() {
        Map<String, Object> fields = Map.of(
                "disputeId", "d-1", "round", 2,
                "judgeAccountId", RECOMMENDER, "credits", 20);
        assertThat(resolve("JudgeVoteRewarded", fields)).containsExactly(RECOMMENDER);

        NotificationTemplates.Template template = NotificationTemplates.template(
                "JudgeVoteRewarded", payload(fields));
        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.DISPUTE);
        assertThat(template.linkPath()).isEqualTo("/me/disputes");
        assertThat(template.body()).contains("投票奖励");
        assertThat(template.payload()).containsEntry("disputeId", "d-1")
                .containsEntry("round", 2L)
                .containsEntry("credits", 20L)
                .doesNotContainKey("judgeAccountId");   // 不泄露账号进渲染 payload
    }

    // ---------- 霸王餐押金（ADR-D12 / 任务书 #22 Stage B3） ----------

    @Test
    void freebieReserveAndRefundNotifyRecommenderWithWalletTemplate() {
        for (String eventType : List.of("FreebieReserved", "FreebieRefunded")) {
            Map<String, Object> fields = Map.of(
                    "engagementRef", "eng-1", "amountCents", 600,
                    "recommenderAccountId", RECOMMENDER, "taskOwnerId", OWNER);
            assertThat(resolve(eventType, fields)).as(eventType).containsExactly(RECOMMENDER);

            NotificationTemplates.Template template = NotificationTemplates.template(eventType, payload(fields));
            assertThat(template).as(eventType).isNotNull();
            assertThat(template.category()).as(eventType).isEqualTo(NotificationCategory.WALLET);
            assertThat(template.linkPath()).as(eventType).isEqualTo("/me/wallet");
            assertThat(template.payload()).as(eventType)
                    .containsEntry("engagementRef", "eng-1")
                    .containsEntry("amountCents", 600L)
                    .doesNotContainKey("recommenderAccountId")
                    .doesNotContainKey("taskOwnerId");
        }
    }

    @Test
    void freebieCompensatedNotifiesBothPartiesWithoutLeakingAccounts() {
        Map<String, Object> fields = Map.of(
                "engagementRef", "eng-1", "amountCents", 600,
                "recommenderAccountId", RECOMMENDER, "taskOwnerId", OWNER);
        assertThat(resolve("FreebieCompensated", fields)).containsExactlyInAnyOrder(OWNER, RECOMMENDER);

        NotificationTemplates.Template template = NotificationTemplates.template("FreebieCompensated", payload(fields));
        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.ENGAGEMENT);
        assertThat(template.linkPath()).isEqualTo("/me/engagements");
        assertThat(template.payload()).containsEntry("amountCents", 600L)
                .doesNotContainKey("recommenderAccountId")
                .doesNotContainKey("taskOwnerId");
    }

    @Test
    void acceptanceFailureNotifiesMerchantOnly() {
        Map<String, Object> fields = Map.of(
                "taskId", "task-1", "applicationId", "app-1", "reason", "insufficient_funds",
                "taskOwnerId", OWNER, "recommenderAccountId", RECOMMENDER);
        assertThat(resolve("ApplicationReservationFailed", fields)).containsExactly(OWNER);

        NotificationTemplates.Template template = NotificationTemplates.template(
                "ApplicationReservationFailed", payload(fields));
        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.ENGAGEMENT);
        assertThat(template.payload()).containsEntry("taskId", "task-1")
                .containsEntry("reason", "insufficient_funds")
                .doesNotContainKey("taskOwnerId")
                .doesNotContainKey("recommenderAccountId");
    }

    @Test
    void engagementRefundedOnCancelBodyIsFundingDirectionAware() {
        NotificationTemplates.Template freebie = NotificationTemplates.template(
                "EngagementRefundedOnCancel", payload(Map.of(
                        "taskId", "task-1", "applicationId", "app-1",
                        "refundDirection", "recommender", "reason", "merchant_cancel")));
        assertThat(freebie).isNotNull();
        assertThat(freebie.body()).contains("押金已全额退还");

        NotificationTemplates.Template bounty = NotificationTemplates.template(
                "EngagementRefundedOnCancel", payload(Map.of(
                        "taskId", "task-1", "applicationId", "app-1",
                        "refundDirection", "merchant", "reason", "merchant_cancel")));
        assertThat(bounty.body()).contains("退还商家");
    }

    // ---------- 满员自动关闭（任务书 #26 / D11） ----------

    @Test
    void taskClosedOnSlotsFullNotifiesOwnerWithEngagementTemplate() {
        // 自动关闭（slots_full）：marketplace 同时下发 taskOwnerId/ownerAccountId（同值）→ 去重只通知一次。
        Map<String, Object> fields = Map.of(
                "taskId", "task-1", "taskOwnerId", OWNER, "ownerAccountId", OWNER,
                "closeReason", "slots_full");
        assertThat(resolve("TaskClosed", fields)).containsExactly(OWNER);

        NotificationTemplates.Template template = NotificationTemplates.template("TaskClosed", payload(fields));
        assertThat(template).isNotNull();
        assertThat(template.category()).isEqualTo(NotificationCategory.ENGAGEMENT);
        assertThat(template.title()).isEqualTo("任务名额已满，已自动关闭");
        assertThat(template.body()).isEqualTo("你的任务报名名额已满，系统已自动关闭报名");
        assertThat(template.linkPath()).isEqualTo("/me/engagements");
        assertThat(template.payload()).containsEntry("taskId", "task-1")
                .doesNotContainKey("taskOwnerId")
                .doesNotContainKey("ownerAccountId");
    }

    @Test
    void taskClosedManualOrMissingReasonProducesNoTemplate() {
        // 商家手动关闭是自身操作，不自我通知；closeReason 缺失同样不产生通知。
        assertThat(NotificationTemplates.template("TaskClosed", payload(Map.of(
                "taskId", "task-1", "taskOwnerId", OWNER, "closeReason", "manual")))).isNull();
        assertThat(NotificationTemplates.template("TaskClosed", payload(Map.of(
                "taskId", "task-1", "taskOwnerId", OWNER)))).isNull();
    }

    @Test
    void taskClosedRecipientsFallbackToOwnerAccountIdAndDedup() {
        // taskOwnerId 缺失时回退 ownerAccountId；两字段同值去重为一人。
        assertThat(resolve("TaskClosed", Map.of(
                "taskId", "task-1", "ownerAccountId", OWNER, "closeReason", "slots_full")))
                .containsExactly(OWNER);
        assertThat(resolve("TaskClosed", Map.of(
                "taskId", "task-1", "taskOwnerId", OWNER, "ownerAccountId", OWNER)))
                .containsExactly(OWNER);
        assertThat(resolve("TaskClosed", Map.of("taskId", "task-1"))).isEmpty();
    }

    // ---- helpers ----


    @Test
    void personalBudgetThresholdAlertNotifiesTheUserThemselves() {
        // 个人 AI 预算阈值告警（GL-P3-AI-001 登记项）：收件人=用户本人（payload.accountId）
        String account = "51515151-5151-5151-5151-515151515151";
        assertThat(resolve("AiPersonalBudgetThresholdCrossed", Map.of(
                "accountId", account, "level", "warning", "window", "daily", "unit", "tokens",
                "periodKey", "2026-08-21", "usage", 80, "limit", 100)))
                .containsExactly(account);
    }

    @Test
    void personalBudgetThresholdAlertWithInvalidAccountIsSilentlySkipped() {
        // 非法 accountId 不投递也不抛错（不阻塞分区重试）
        assertThat(resolve("AiPersonalBudgetThresholdCrossed", Map.of(
                "accountId", "not-a-uuid", "level", "exceeded", "window", "monthly", "unit", "cents",
                "periodKey", "2026-08", "usage", 105, "limit", 100)))
                .isEmpty();
        assertThat(resolve("AiPersonalBudgetThresholdCrossed", Map.of(
                "level", "exceeded", "window", "monthly", "unit", "cents")))
                .isEmpty();
    }

    private List<String> resolve(String eventType, Map<String, Object> payload) {
        IdentityEventEnvelope envelope = new IdentityEventEnvelope(
                "evt-1", eventType, "Aggregate", "agg-1", payload(payload));
        return new NotificationRecipientResolver(db).resolve(envelope).block();
    }

    private JsonNode payload(Map<String, Object> payload) {
        return mapper.valueToTree(payload);
    }
}
