package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class DigitalHumanRetirementFilterTest {
    @Test void refusesActiveOperationsBeforeReadingBodyOrCallingRuntime() {
        for (String path : new String[]{"sessions", "profiles", "preflights", "voice-previews", "avatars",
                "sessions/id/resume", "sessions/id/heartbeat", "sessions/id/connection-grants", "sessions/id/webrtc/offer",
                "sessions/id/media-ready", "sessions/id/turns", "sessions/id/greeting", "sessions/id/recordings", "sessions/id/playback-reset"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/digital-human/" + path));
            var called = new AtomicBoolean();
            new DigitalHumanRetirementFilter().filter(exchange, ignored -> { called.set(true); return Mono.empty(); }).block();
            assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(410);
            assertThat(exchange.getResponse().getBodyAsString().block()).contains("dh_retired");
            assertThat(called.get()).isFalse();
        }
    }
    @Test void encodedAndMatrixPathsCannotBypassRetirement() {
        assertThat(DigitalHumanRetirementFilter.retired("POST", "/api/digital-human/sessions/id/%72esume")).isTrue();
        assertThat(DigitalHumanRetirementFilter.retired("POST", "/api/digital-human/sessions/id/resume;x=1")).isTrue();
        assertThat(DigitalHumanRetirementFilter.retired("POST", "/api/digital-human/sessions/id%2Fother/resume")).isTrue();
    }
    @Test void preservesHistoryAndDrainPaths() {
        for (String action : new String[]{"end", "pause", "interrupt", "transcript-save"})
            assertThat(DigitalHumanRetirementFilter.retired("POST", "/api/digital-human/sessions/id/" + action)).isFalse();
        assertThat(DigitalHumanRetirementFilter.retired("DELETE", "/api/digital-human/sessions/id")).isFalse();
        assertThat(DigitalHumanRetirementFilter.retired("GET", "/api/digital-human/sessions")).isFalse();
        assertThat(DigitalHumanRetirementFilter.retired("POST", "/api/digital-human/recordings/id/stop")).isFalse();
        assertThat(DigitalHumanRetirementFilter.retired("POST", "/api/admin/digital-human/invocations/id/reconcile")).isFalse();
        assertThat(DigitalHumanRetirementFilter.retired("PUT", "/api/admin/digital-human/config")).isTrue();
        assertThat(DigitalHumanRetirementFilter.retired("PATCH", "/api/digital-human/profiles/id")).isTrue();
    }
}
