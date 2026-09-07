package com.grassland.marketplace.workflow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.assertion.TestAssertionHelper;
import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class FinanceEscrowClientTest {

    private static final String AUDIENCE = "grassland-finance";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String REF = "application-42";
    private static final String PAYEE = "22222222-2222-2222-2222-222222222222";

    private WireMockServer wireMock;
    private FinanceEscrowClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        IdentityAssertionSigner signer = TestAssertionHelper.serviceSigner("marketplace", AUDIENCE);
        ServiceAssertionIssuer issuer = new ServiceAssertionIssuer(signer, AUDIENCE);
        client = new FinanceEscrowClient(issuer, wireMock.baseUrl(), "X-Grassland-Identity");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void matchingReservationEnvelopeReturnsReservedAndSendsServiceAssertion() {
        stubReservation(201, reservationEnvelope(ORG, REF, 1_000, PAYEE, 1_000, 100, "reserved"));

        StepVerifier.create(client.reserve(ORG, REF, 1_000, PAYEE, 1_000))
                .assertNext(result -> {
                    assertThat(result.reserved()).isTrue();
                    assertThat(result.amountCents()).isEqualTo(1_000);
                })
                .verifyComplete();

        wireMock.verify(postRequestedFor(urlEqualTo("/api/finance/accounts/" + ORG + "/reservations"))
                .withHeader("X-Grassland-Identity", matching(".+"))
                .withRequestBody(containing("\"engagementRef\":\"" + REF + "\""))
                .withRequestBody(containing("\"payeeAccountId\":\"" + PAYEE + "\"")));
    }

    @Test
    void reservationWithoutPayeeUsesStableNullableRequestPayload() {
        stubReservation(201, reservationEnvelope(ORG, REF, 1_000, null, 0, 0, "reserved"));

        StepVerifier.create(reactor.core.publisher.Mono.defer(
                        () -> client.reserve(ORG, REF, 1_000, null)))
                .assertNext(result -> {
                    assertThat(result.reserved()).isTrue();
                    assertThat(result.amountCents()).isEqualTo(1_000);
                })
                .verifyComplete();

        wireMock.verify(postRequestedFor(urlEqualTo("/api/finance/accounts/" + ORG + "/reservations"))
                .withRequestBody(containing("\"payeeAccountId\":null")));
    }

    @Test
    void successWithMismatchedFrozenScopeThrows() {
        String[] mismatchedResponses = {
                reservationEnvelope("33333333-3333-3333-3333-333333333333", REF,
                        1_000, PAYEE, 1_000, 100, "reserved"),
                reservationEnvelope(ORG, "application-else", 1_000, PAYEE, 1_000, 100, "reserved"),
                reservationEnvelope(ORG, REF, 999, PAYEE, 1_000, 100, "reserved"),
                reservationEnvelope(ORG, REF, 1_000,
                        "44444444-4444-4444-4444-444444444444", 1_000, 100, "reserved"),
                reservationEnvelope(ORG, REF, 1_000, PAYEE, 300, 30, "reserved"),
                reservationEnvelope(ORG, REF, 1_000, PAYEE, 1_000, 99, "reserved"),
                reservationEnvelope(ORG, REF, 1_000, PAYEE, 1_000, 100, "released")
        };

        for (String response : mismatchedResponses) {
            wireMock.resetAll();
            stubReservation(200, response);

            StepVerifier.create(client.reserve(ORG, REF, 1_000, PAYEE, 1_000))
                    .verifyError(FinanceEscrowException.class);
        }
    }

    @Test
    void malformedOrUnsuccessfulSuccessResponseThrows() {
        String[] invalidResponses = {
                "",
                "not-json",
                "{\"success\":false,\"error\":\"unexpected\"}",
                "{\"success\":true,\"data\":null}"
        };

        for (String response : invalidResponses) {
            wireMock.resetAll();
            stubReservation(200, response);

            StepVerifier.create(client.reserve(ORG, REF, 1_000, PAYEE, 1_000))
                    .verifyError(FinanceEscrowException.class);
        }
    }

    @Test
    void scopeConflictIsAnExceptionRatherThanInsufficientFunds() {
        stubReservation(422, "{\"success\":false,\"error\":\"engagementRef 预留范围冲突\"}");

        StepVerifier.create(client.reserve(ORG, REF, 1_000, PAYEE, 1_000))
                .verifyError(FinanceEscrowException.class);
    }

    @Test
    void onlyExplicitInsufficientFundsConflictReturnsBusinessResult() {
        stubReservation(409, "{\"success\":false,\"error\":\"余额不足\"}");

        StepVerifier.create(client.reserve(ORG, REF, 1_000, PAYEE, 1_000))
                .assertNext(result -> {
                    assertThat(result.reserved()).isFalse();
                    assertThat(result.amountCents()).isZero();
                })
                .verifyComplete();

        wireMock.resetAll();
        stubReservation(409, "{\"success\":false,\"error\":\"交易金额超出本组织单笔上限\"}");
        StepVerifier.create(client.reserve(ORG, REF, 1_000, PAYEE, 1_000))
                .verifyError(FinanceEscrowException.class);
    }


    // ---------- 任务书 #90 C90-01：captureVerified / release 验证语义 ----------

    @Test
    void captureVerifiedSuccessVerifiesScope() {
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/capture"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(reservationEnvelope(ORG, REF, 1_000, PAYEE, 0, 0, "captured"))));

        StepVerifier.create(client.captureVerified(ORG, REF, 1_000, PAYEE, null))
                .assertNext(outcome -> {
                    assertThat(outcome.captured()).isTrue();
                    assertThat(outcome.alreadyCaptured()).isFalse();
                    assertThat(outcome.reconciliationReason()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void captureVerifiedScopeMismatchOnSuccessRequiresReconciliation() {
        // HTTP 200 但收款人与预期不符（D90-02）：钱动了但不对——进对账，不得发 settled
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/capture"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(reservationEnvelope(ORG, REF, 1_000,
                                "44444444-4444-4444-4444-444444444444", 0, 0, "captured"))));

        StepVerifier.create(client.captureVerified(ORG, REF, 1_000, PAYEE, null))
                .assertNext(outcome -> {
                    assertThat(outcome.captured()).isFalse();
                    assertThat(outcome.reconciliationReason()).isEqualTo("reservation_scope_mismatch");
                })
                .verifyComplete();
    }

    @Test
    void captureVerifiedMissingReservationRequiresReconciliation() {
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/capture"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":\"预留不存在\"}")));

        StepVerifier.create(client.captureVerified(ORG, REF, 1_000, PAYEE, null))
                .assertNext(outcome -> {
                    assertThat(outcome.captured()).isFalse();
                    assertThat(outcome.reconciliationReason()).isEqualTo("reservation_missing");
                })
                .verifyComplete();
    }

    @Test
    void captureVerifiedConflictRereadsCapturedStateAsIdempotent() {
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/capture"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":\"该预留已处理\"}")));
        stubGetReservation(reservationEnvelope(ORG, REF, 1_000, PAYEE, 0, 0, "captured"));

        StepVerifier.create(client.captureVerified(ORG, REF, 1_000, PAYEE, null))
                .assertNext(outcome -> {
                    assertThat(outcome.captured()).isTrue();
                    assertThat(outcome.alreadyCaptured()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void captureVerifiedConflictWithReleasedStateRequiresReconciliation() {
        // 已 release（取消补偿先行）→ 绝不能当结算成功（TC90-002 核心）
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/capture"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":\"该预留已处理\"}")));
        stubGetReservation(reservationEnvelope(ORG, REF, 1_000, PAYEE, 0, 0, "released"));

        StepVerifier.create(client.captureVerified(ORG, REF, 1_000, PAYEE, null))
                .assertNext(outcome -> {
                    assertThat(outcome.captured()).isFalse();
                    assertThat(outcome.reconciliationReason()).isEqualTo("reservation_released");
                })
                .verifyComplete();
    }

    @Test
    void captureVerifiedConflictButReservationVanishedRequiresReconciliation() {
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/capture"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":\"该预留已处理\"}")));
        stubGetReservation(404, "{\"success\":false,\"error\":\"预留不存在\"}");

        StepVerifier.create(client.captureVerified(ORG, REF, 1_000, PAYEE, null))
                .assertNext(outcome ->
                        assertThat(outcome.reconciliationReason()).isEqualTo("reservation_missing"))
                .verifyComplete();
    }

    @Test
    void releaseConflictWithReleasedStateIsIdempotentNoOp() {
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/release"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":\"该预留已处理\"}")));
        stubGetReservation(reservationEnvelope(ORG, REF, 1_000, PAYEE, 0, 0, "released"));

        StepVerifier.create(client.release(ORG, REF)).verifyComplete();
    }

    @Test
    void releaseConflictWithCapturedStateThrowsForReconciliation() {
        // 已 captured 的预留绝不能当已释放（补偿/取消会误信退款成功）
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/release"))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":\"该预留已处理\"}")));
        stubGetReservation(reservationEnvelope(ORG, REF, 1_000, PAYEE, 0, 0, "captured"));

        StepVerifier.create(client.release(ORG, REF)).verifyError(FinanceEscrowException.class);
    }

    @Test
    void releaseMissingReservationIsNoOp() {
        wireMock.stubFor(post(urlEqualTo("/api/finance/reservations/" + REF + "/release"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":\"预留不存在\"}")));

        StepVerifier.create(client.release(ORG, REF)).verifyComplete();
    }

    private void stubGetReservation(String body) {
        stubGetReservation(200, body);
    }

    private void stubGetReservation(int status, String body) {
        wireMock.stubFor(get(urlEqualTo("/api/finance/reservations/" + REF))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void stubReservation(int status, String body) {
        wireMock.stubFor(post(urlEqualTo("/api/finance/accounts/" + ORG + "/reservations"))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static String reservationEnvelope(String org, String ref, long amount, String payee,
                                              int bonusBps, long bonusCents, String status) {
        return "{\"success\":true,\"data\":{"
                + "\"organizationId\":\"" + org + "\","
                + "\"engagementRef\":\"" + ref + "\","
                + "\"amountCents\":" + amount + ","
                + "\"payeeAccountId\":" + (payee == null ? "null" : "\"" + payee + "\"") + ","
                + "\"commissionBonusBps\":" + bonusBps + ","
                + "\"commissionBonusCents\":" + bonusCents + ","
                + "\"status\":\"" + status + "\"}}";
    }
}
