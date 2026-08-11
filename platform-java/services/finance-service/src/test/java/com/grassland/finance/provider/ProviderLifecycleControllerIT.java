package com.grassland.finance.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ProviderLifecycleControllerIT extends FinanceItSupport {

    @Autowired
    ProviderOperationRepository operations;

    @Test
    void webhookIsIdempotentAndUnknownOperationIsIgnored() {
        String operationId = "payment:" + UUID.randomUUID();
        String providerRef = "sandbox:payment:" + UUID.randomUUID();
        operations.register("sandbox", operationId, "payment", "order-1", 4200, "CNY", providerRef)
                .block();
        String eventId = "event:" + UUID.randomUUID();
        Map<String, Object> event = Map.of(
                "eventId", eventId,
                "provider", "sandbox",
                "eventType", "payment.succeeded",
                "providerRef", providerRef,
                "operationId", operationId,
                "payloadJson", "{\"source\":\"test\"}");

        for (int i = 0; i < 2; i++) {
            post("/api/admin/finance/sandbox/webhooks", event)
                    .expectStatus().isOk().expectBody()
                    .jsonPath("$.data.status").isEqualTo("processed");
        }
        assertThat(count("finance_provider_webhook_event", "event_id", eventId)).isEqualTo(1);

        post("/api/admin/finance/sandbox/webhooks", Map.of(
                "eventId", "event:" + UUID.randomUUID(),
                "provider", "sandbox",
                "eventType", "payment.processing",
                "providerRef", providerRef,
                "operationId", operationId,
                "payloadJson", "{}"))
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("ignored");

        String unknownEvent = "event:" + UUID.randomUUID();
        post("/api/admin/finance/sandbox/webhooks", Map.of(
                "eventId", unknownEvent,
                "provider", "sandbox",
                "eventType", "payment.succeeded",
                "operationId", "missing:" + UUID.randomUUID(),
                "payloadJson", "{}"))
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("ignored");
    }

    @Test
    void reconciliationClassifiesMatchedMismatchAndUnmatchedIdempotently() {
        String matchedId = "refund:" + UUID.randomUUID();
        String matchedRef = "sandbox:refund:" + UUID.randomUUID();
        operations.register("sandbox", matchedId, "refund", "order-2", 1800, "CNY", matchedRef)
                .block();
        String statement = "statement:" + UUID.randomUUID();
        Map<String, Object> matched = statement(
                statement, matchedRef, matchedId, "refund", 1800);

        for (int i = 0; i < 2; i++) {
            post("/api/admin/finance/sandbox/reconciliation", matched)
                    .expectStatus().isOk().expectBody()
                    .jsonPath("$.data.status").isEqualTo("matched");
        }
        assertThat(operations.findByOperationId(matchedId).block().status()).isEqualTo("reconciled");
        assertThat(count("finance_provider_reconciliation", "statement_ref", statement)).isEqualTo(1);

        String mismatchId = "split:" + UUID.randomUUID();
        String mismatchRef = "sandbox:split:" + UUID.randomUUID();
        operations.register("sandbox", mismatchId, "split", "order-3", 5000, "CNY", mismatchRef)
                .block();
        post("/api/admin/finance/sandbox/reconciliation",
                statement("statement:" + UUID.randomUUID(), mismatchRef, mismatchId, "split", 4999))
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("mismatch");

        post("/api/admin/finance/sandbox/reconciliation", statement(
                "statement:" + UUID.randomUUID(), "sandbox:missing:" + UUID.randomUUID(),
                "missing:" + UUID.randomUUID(), "payout", 900))
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("unmatched");
    }

    @Test
    void providerControlsRequireFinanceRoleAndRejectNonSandboxProvider() {
        client().get().uri("/api/admin/finance/provider-operations")
                .header("X-Grassland-Identity", signRole(UUID.randomUUID().toString(), "risk"))
                .exchange().expectStatus().isForbidden();

        client().post().uri("/api/admin/finance/sandbox/webhooks")
                .header("X-Grassland-Identity", financeAssertion())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "eventId", "event:" + UUID.randomUUID(), "provider", "real-psp",
                        "eventType", "operation.succeeded", "payloadJson", "{}"))
                .exchange().expectStatus().isBadRequest();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec post(
            String uri, Map<String, Object> body) {
        return client().post().uri(uri)
                .header("X-Grassland-Identity", financeAssertion())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private String financeAssertion() {
        return signRole(UUID.randomUUID().toString(), "finance");
    }

    private static Map<String, Object> statement(
            String statementRef, String providerRef, String operationId,
            String operationType, long amountCents) {
        return Map.of(
                "provider", "sandbox", "statementRef", statementRef,
                "providerRef", providerRef, "operationId", operationId,
                "operationType", operationType, "amountCents", amountCents,
                "currency", "CNY", "payloadJson", "{}");
    }

    private long count(String table, String column, String value) {
        String sql = "SELECT COUNT(*)::int AS c FROM " + table + " WHERE " + column + " = :value";
        return db.sql(sql).bind("value", value)
                .map(row -> row.get("c", Integer.class)).one().block().longValue();
    }
}
