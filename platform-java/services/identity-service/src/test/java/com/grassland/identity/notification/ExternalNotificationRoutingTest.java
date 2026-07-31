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
    void bothPartiesNotifiedOnVerificationAndSettlement() {
        for (String eventType : List.of("VerificationChecked", "EngagementSettled", "SettlementHeld")) {
            assertThat(resolve(eventType, Map.of(
                    "taskId", "task-1", "recommenderAccountId", RECOMMENDER, "taskOwnerId", OWNER)))
                    .as(eventType)
                    .containsExactly(OWNER, RECOMMENDER);
        }
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
        for (String eventType :
                List.of("DisputeAssigned", "DisputeAppealed", "AdjudicationEscalated", "DisputeFinalized")) {
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
        for (String eventType : List.of("FundsCaptured", "FundsReleased", "AccountCredited")) {
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

    // ---- helpers ----

    private List<String> resolve(String eventType, Map<String, Object> payload) {
        IdentityEventEnvelope envelope = new IdentityEventEnvelope(
                "evt-1", eventType, "Aggregate", "agg-1", payload(payload));
        return new NotificationRecipientResolver(db).resolve(envelope).block();
    }

    private JsonNode payload(Map<String, Object> payload) {
        return mapper.valueToTree(payload);
    }
}
