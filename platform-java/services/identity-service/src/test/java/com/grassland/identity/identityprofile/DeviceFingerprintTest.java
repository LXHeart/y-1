package com.grassland.identity.identityprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class DeviceFingerprintTest {

    @Test
    void usesIngressAppendedRightmostForwardedAddress() {
        var request = MockServerHttpRequest.get("/api/auth/login")
                .header("X-Forwarded-For", "198.51.100.20, 203.0.113.8")
                .remoteAddress(new InetSocketAddress("10.0.0.4", 12345))
                .build();

        assertThat(DeviceFingerprint.from(request).ipAddress()).isEqualTo("203.0.113.8");
    }

    @Test
    void fallsBackToSocketAddressWithoutForwardedHeader() {
        var request = MockServerHttpRequest.get("/api/auth/login")
                .remoteAddress(new InetSocketAddress("192.0.2.44", 12345))
                .build();

        assertThat(DeviceFingerprint.from(request).ipAddress()).isEqualTo("192.0.2.44");
    }
}
