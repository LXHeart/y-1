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
        MailTemplate t = MailTemplates.mailTemplate("MembershipInvited",
                payload(Map.of("organizationId", "org-1")));
        assertThat(t).isNotNull();
        assertThat(t.category()).isEqualTo("invitation");
        assertThat(t.subject()).isNotBlank();
        assertThat(t.body()).isNotBlank();
    }

    @Test
    void permissionEventProducesNoMail_highValueSubsetExcludesPermission() {
        // 权限审批频次高、价值低 → 不发邮件（高价值子集决策）
        assertThat(MailTemplates.mailTemplate("PermissionRequested",
                payload(Map.of("organizationId", "org-1")))).isNull();
        assertThat(MailTemplates.mailTemplate("PermissionReviewed",
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
    void unknownEventProducesNoMail() {
        assertThat(MailTemplates.mailTemplate("WhateverEvent", payload(Map.of()))).isNull();
    }

    @Test
    void mailSubjectBodyMatchNotificationTemplate_zeroDrift() {
        // 委托保证：邮件 subject/body 与站内通知 title/body 同源，无漂移
        JsonNode p = payload(Map.of("organizationId", "org-1"));
        MailTemplate mail = MailTemplates.mailTemplate("MembershipInvited", p);
        NotificationTemplates.Template notif = NotificationTemplates.template("MembershipInvited", p);
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
