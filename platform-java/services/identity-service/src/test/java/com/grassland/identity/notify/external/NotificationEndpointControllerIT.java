package com.grassland.identity.notify.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.notification.NotificationEventProcessor;
import com.grassland.identity.notification.NotificationProcessingResult;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class NotificationEndpointControllerIT extends IdentityItSupport {

    private static final Pattern CODE = Pattern.compile("验证码是：([0-9]{6})");

    @Autowired private NotificationEventProcessor processor;

    @Test
    void pushEndpointReceivesEventThroughDurableOutbox() {
        var account = seedAccount("push-endpoint-" + UUID.randomUUID() + "@example.com");
        String token = "ExponentPushToken[abcdefghijklmnopqrstuvwxyz123456]";
        client().post().uri("/api/me/notification-endpoints/push")
                .header("Cookie", "y1.sid=" + account.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "expo", "token", token))
                .exchange().expectStatus().isOk();

        String eventId = "push-event-" + UUID.randomUUID();
        String json = """
                {"eventId":"%s","eventType":"MembershipGranted","aggregateType":"membership",
                 "aggregateId":"%s","payload":{"accountId":"%s","organizationId":"%s","role":"member"}}
                """.formatted(eventId, UUID.randomUUID(), account.accountId(), UUID.randomUUID());
        assertThat(processor.process(new ConsumerRecord<>("identity", 0, 0, eventId, json)).block())
                .isEqualTo(NotificationProcessingResult.PROCESSED);

        Map<String, Object> row = db.sql("SELECT channel, recipient, status FROM external_delivery_outbox "
                        + "WHERE source_event_id = :eventId")
                .bind("eventId", eventId).fetch().one().block();
        assertThat(row).containsEntry("channel", "push")
                .containsEntry("recipient", token).containsEntry("status", "pending");
    }

    @Test
    void smsEndpointRequiresSuccessfulVerificationChallenge() {
        var account = seedAccount("sms-endpoint-" + UUID.randomUUID() + "@example.com");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = client().post().uri("/api/me/notification-endpoints/sms/challenges")
                .header("Cookie", "y1.sid=" + account.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phone", "+8613800001234"))
                .exchange().expectStatus().isEqualTo(202)
                .expectBody(Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        String challengeId = (String) ((Map<String, Object>) response.get("data")).get("challengeId");

        String body = db.sql("SELECT body FROM external_delivery_outbox WHERE source_event_id = :eventId")
                .bind("eventId", "sms-challenge:" + challengeId)
                .map(row -> row.get("body", String.class)).one().block();
        var matcher = CODE.matcher(body);
        assertThat(matcher.find()).isTrue();

        client().post().uri("/api/me/notification-endpoints/sms/challenges/" + challengeId + "/confirm")
                .header("Cookie", "y1.sid=" + account.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", matcher.group(1)))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.verified").isEqualTo(true);

        Long endpoints = db.sql("SELECT count(*) FROM notification_endpoint "
                        + "WHERE account_id = CAST(:accountId AS uuid) AND channel = 'sms' "
                        + "AND address = '+8613800001234' AND verified_at IS NOT NULL")
                .bind("accountId", account.accountId()).map(row -> row.get(0, Long.class)).one().block();
        assertThat(endpoints).isEqualTo(1L);
    }

    @Test
    void preferenceDisablesPushForCategory() {
        var account = seedAccount("push-pref-" + UUID.randomUUID() + "@example.com");
        String token = "ExponentPushToken[preferenceabcdefghijklmnopqrstuvwxyz]";
        client().post().uri("/api/me/notification-endpoints/push")
                .header("Cookie", "y1.sid=" + account.cookie()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "expo", "token", token)).exchange().expectStatus().isOk();
        client().put().uri("/api/me/notification-endpoints/preferences/invitation")
                .header("Cookie", "y1.sid=" + account.cookie()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("pushEnabled", false, "smsEnabled", true)).exchange().expectStatus().isOk();

        Long active = new ExternalDeliveryRepository(db)
                .findActiveEndpoints(account.accountId(), "invitation").count().block();
        assertThat(active).isZero();
    }
}

