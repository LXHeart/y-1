package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.notification.MailTemplates.MailTemplate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link MailTemplates} 单测（GL-P1-NOTIFY-001）。验证委托 {@link NotificationTemplates} 拿文案 +
 * PERMISSION 类过滤的高价值子集策略。
 */
class MailTemplatesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode payload(Map<String, Object> fields) {
        return mapper.valueToTree(fields);
    }

    @Test
    void invitationEventProducesInvitationMail() {
        // 任务书 #49 邀请流下线后，INVITATION 类的邮件样板换成 #48 子账号欢迎通知
        MailTemplate t = MailTemplates.mailTemplate("OrgSubAccountCreated",
                payload(Map.of("organizationId", "org-1")));
        assertThat(t).isNotNull();
        assertThat(t.category()).isEqualTo("invitation");
        assertThat(t.subject()).isNotBlank();
        assertThat(t.body()).isNotBlank();
    }


    @Test
    void personalBudgetThresholdAlertCoversWarningAndExceededVariants() {
        // 个人 AI 预算告警（GL-P3-AI-001）：WALLET 类；exceeded/warning × monthly/daily × cents/tokens 文案
        Map<String, Object> base = Map.of("accountId", "51515151-5151-5151-5151-515151515151",
                "periodKey", "2026-08-21", "usage", 80, "limit", 100);
        MailTemplate warning = MailTemplates.mailTemplate("AiPersonalBudgetThresholdCrossed",
                payload(new java.util.HashMap<>(base) {{
                    put("level", "warning"); put("window", "daily"); put("unit", "tokens");
                }}));
        assertThat(warning).isNotNull();
        assertThat(warning.category()).isEqualTo("wallet");
        assertThat(warning.subject()).contains("接近上限");
        assertThat(warning.body()).contains("今日调用量");

        MailTemplate exceededMonthlyCents = MailTemplates.mailTemplate("AiPersonalBudgetThresholdCrossed",
                payload(new java.util.HashMap<>(base) {{
                    put("level", "exceeded"); put("window", "monthly"); put("unit", "cents");
                    put("usage", 105);
                }}));
        assertThat(exceededMonthlyCents.subject()).contains("已超限");
        assertThat(exceededMonthlyCents.body()).contains("本月消费金额").contains("硬停");
    }

    @Test
    void budgetThresholdAlertProducesWalletMail() {
        // 组织 AI 预算告警属资金类高价值：WALLET 类入邮件（去重由事件侧三闸保证）
        MailTemplate warning = MailTemplates.mailTemplate("AiOrgBudgetThresholdCrossed",
                payload(Map.of("organizationId", "org-1", "ruleKey", "daily_tokens", "level", "warning",
                        "window", "daily", "unit", "tokens", "periodKey", "2026-08-21",
                        "usage", 80, "limit", 100)));
        assertThat(warning).isNotNull();
        assertThat(warning.category()).isEqualTo("wallet");
        assertThat(warning.subject()).contains("接近上限");
        MailTemplate exceeded = MailTemplates.mailTemplate("AiOrgBudgetThresholdCrossed",
                payload(Map.of("organizationId", "org-1", "ruleKey", "daily_cents", "level", "exceeded",
                        "window", "daily", "unit", "cents", "periodKey", "2026-08-21",
                        "usage", 105, "limit", 100)));
        assertThat(exceeded).isNotNull();
        assertThat(exceeded.subject()).contains("已超限");
    }

    @Test
    void permissionEventProducesNoMail_highValueSubsetExcludesPermission() {
        // 权限审批频次高、价值低 → 不发邮件（高价值子集决策）
        assertThat(MailTemplates.mailTemplate("PermissionRequested",
                payload(Map.of("organizationId", "org-1")))).isNull();
        assertThat(MailTemplates.mailTemplate("PermissionReviewed",
                payload(Map.of("organizationId", "org-1")))).isNull();
        assertThat(MailTemplates.mailTemplate("PermissionReviewSlaBreached",
                payload(Map.of("organizationId", "org-1")))).isNull();
    }

    @Test
    void engagementEventProducesEngagementMail() {
        MailTemplate t = MailTemplates.mailTemplate("ApplicationSubmitted",
                payload(Map.of("taskId", "t-1")));
        assertThat(t).isNotNull();
        assertThat(t.category()).isEqualTo("engagement");
    }

    @Test
    void confirmationWindowMailMatchesEngagementNotification() {
        JsonNode p = payload(Map.of("taskId", "t-1", "applicationId", "a-1", "submissionId", "s-1"));
        for (String eventType : java.util.List.of("ConfirmationWindowEntered", "ConfirmationWindowExpiring", "AutoSettledOnTimeout")) {
            MailTemplate mail = MailTemplates.mailTemplate(eventType, p);
            NotificationTemplates.Template notification = NotificationTemplates.template(eventType, p);
            assertThat(mail).as(eventType).isNotNull();
            assertThat(mail.category()).as(eventType).isEqualTo("engagement");
            assertThat(mail.subject()).as(eventType).isEqualTo(notification.title());
            assertThat(mail.body()).as(eventType).isEqualTo(notification.body());
        }
    }

    @Test
    void merchantContestedProducesDisputeMail() {
        JsonNode p = payload(Map.of("disputeId", "d-1", "applicationId", "a-1"));
        MailTemplate mail = MailTemplates.mailTemplate("MerchantContested", p);
        NotificationTemplates.Template notification = NotificationTemplates.template("MerchantContested", p);
        assertThat(mail).isNotNull();
        assertThat(mail.category()).isEqualTo("dispute");
        assertThat(mail.subject()).isEqualTo(notification.title());
        assertThat(mail.body()).isEqualTo(notification.body());
    }

    @Test
    void disputeEventProducesDisputeMail() {
        MailTemplate t = MailTemplates.mailTemplate("EngagementDisputed",
                payload(Map.of("disputeId", "d-1", "openedByRole", "merchant")));
        assertThat(t).isNotNull();
        assertThat(t.category()).isEqualTo("dispute");
    }

    @Test
    void walletEventProducesWalletMail() {
        MailTemplate t = MailTemplates.mailTemplate("FundsCaptured",
                payload(Map.of("engagementRef", "e-1")));
        assertThat(t).isNotNull();
        assertThat(t.category()).isEqualTo("wallet");
    }

    @Test
    void applicationResultEventsProduceEngagementMailMatchingNotification() {
        // 报名结果（#28）：ENGAGEMENT 类落入高价值邮件子集，文案与站内通知零漂移。
        JsonNode p = payload(Map.of("taskId", "t-1", "applicationId", "a-1"));
        for (String eventType : java.util.List.of("ApplicationAccepted", "ApplicationRejected")) {
            MailTemplate mail = MailTemplates.mailTemplate(eventType, p);
            NotificationTemplates.Template notification = NotificationTemplates.template(eventType, p);
            assertThat(mail).as(eventType).isNotNull();
            assertThat(mail.category()).as(eventType).isEqualTo("engagement");
            assertThat(mail.subject()).as(eventType).isEqualTo(notification.title());
            assertThat(mail.body()).as(eventType).isEqualTo(notification.body());
        }
    }

    @Test
    void unknownEventProducesNoMail() {
        assertThat(MailTemplates.mailTemplate("WhateverEvent", payload(Map.of()))).isNull();
    }

    @Test
    void mailSubjectBodyMatchNotificationTemplate_zeroDrift() {
        // 委托保证：邮件 subject/body 与站内通知 title/body 同源，无漂移
        JsonNode p = payload(Map.of("organizationId", "org-1"));
        MailTemplate mail = MailTemplates.mailTemplate("OrgSubAccountCreated", p);
        NotificationTemplates.Template notif = NotificationTemplates.template("OrgSubAccountCreated", p);
        assertThat(mail.subject()).isEqualTo(notif.title());
        assertThat(mail.body()).isEqualTo(notif.body());
    }

    @Test
    void residualEventsCompleteMailCoverage() {
        // GL-P1-NOTIFY-001 残留补全：4 个事件经委托拿到文案，且均非 PERMISSION → 落入高价值子集，入队邮件。
        Map<String, String> expectedCategory = Map.of(
                "DisputeDecided", "dispute",
                "AdjudicationReopened", "dispute",
                "FundsReserved", "wallet",
                "FundsReversed", "wallet");
        JsonNode p = payload(Map.of("disputeId", "d-1", "engagementRef", "eng-1", "amountCents", 600));
        expectedCategory.forEach((eventType, category) -> {
            MailTemplate mail = MailTemplates.mailTemplate(eventType, p);
            assertThat(mail).as(eventType).isNotNull();
            assertThat(mail.category()).as(eventType).isEqualTo(category);
            assertThat(mail.subject()).as(eventType).isNotBlank();
            assertThat(mail.body()).as(eventType).isNotBlank();
        });
    }
}
