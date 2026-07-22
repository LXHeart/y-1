package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class LegacyProxyHeaderPolicyTest {
    @Test
    void removesHopByHopAndConnectionNamedRequestHeaders() {
        HttpHeaders source = new HttpHeaders();
        source.add(HttpHeaders.CONNECTION, "keep-alive, X-Remove-Me");
        source.add(HttpHeaders.HOST, "attacker.example");
        source.add("Keep-Alive", "timeout=5");
        source.add("X-Remove-Me", "secret");
        source.add(HttpHeaders.COOKIE, "y1.sid=session");
        source.add(HttpHeaders.RANGE, "bytes=0-9");

        HttpHeaders result = LegacyProxyHeaderPolicy.requestHeaders(source);

        assertThat(result.getFirst(HttpHeaders.CONNECTION)).isNull();
        assertThat(result.getFirst(HttpHeaders.HOST)).isNull();
        assertThat(result.getFirst("Keep-Alive")).isNull();
        assertThat(result.getFirst("X-Remove-Me")).isNull();
        assertThat(result.getFirst(HttpHeaders.COOKIE)).isEqualTo("y1.sid=session");
        assertThat(result.getFirst(HttpHeaders.RANGE)).isEqualTo("bytes=0-9");
    }

    @Test
    void keepsIndependentSetCookieHeaders() {
        HttpHeaders source = new HttpHeaders();
        source.add(HttpHeaders.SET_COOKIE, "a=1; Path=/");
        source.add(HttpHeaders.SET_COOKIE, "b=2; Path=/");
        source.add(HttpHeaders.CONNECTION, "close");

        HttpHeaders result = LegacyProxyHeaderPolicy.responseHeaders(source);

        assertThat(result.get(HttpHeaders.SET_COOKIE)).containsExactly(
            "a=1; Path=/",
            "b=2; Path=/"
        );
        assertThat(result.getFirst(HttpHeaders.CONNECTION)).isNull();
    }
}
