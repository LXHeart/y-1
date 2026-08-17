package com.grassland.edge.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "PUBLIC_BACKEND_ORIGIN=http://localhost:8080",
        "BILIBILI_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "DOUYIN_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "EDGE_ROUTE_AUTH_ME_IDENTITY=false",
        "EDGE_ROUTE_GUEST_TRIAL_INTELLIGENCE=false"
})
class EdgeFailClosedIT {

    @LocalServerPort
    private int port;

    @Autowired
    private UpstreamResolver resolver;

    @Test
    void unknownApiReturns404WithoutAnUpstream() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/not-migrated")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void disabledRouteReturns404InsteadOfFallingBack() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isNotFound();
    }

    /** 任务书 #36 B2：游客试用 flag 关闭 → /api/guest-trial/* fail-closed 404（ADR-D14 R2 总开关）。 */
    @Test
    void guestTrialFlagOffFailsClosed() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/guest-trial/article-titles")
                .exchange()
                .expectStatus().isNotFound();
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/guest-trial/quota")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void healthIsServedByEdge() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok")
                .jsonPath("$.service").isEqualTo("edge-bff");
    }
}
