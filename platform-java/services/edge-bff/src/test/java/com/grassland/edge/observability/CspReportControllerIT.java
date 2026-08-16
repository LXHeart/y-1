package com.grassland.edge.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * CSP 违规报告端点契约：真实 controller 映射优先于 /api/** 通配代理；POST 上报（无登录态、
 * 可能无 Origin/Referer）一律 204 静默；GET 拒绝（浏览器只 POST，其余是探测）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "PUBLIC_BACKEND_ORIGIN=http://localhost:8080",
        "BILIBILI_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "DOUYIN_PROXY_TOKEN_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
})
class CspReportControllerIT {

    @LocalServerPort
    private int port;

    @Test
    void postReportReturnsNoContentWithoutAuth() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/csp-report")
                .header("Content-Type", "application/csp-report")
                .bodyValue("{\"csp-report\":{\"document-uri\":\"https://app.example/\",\"violated-directive\":\"script-src\"}}")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void postJsonReportAlsoAccepted() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/csp-report")
                .header("Content-Type", "application/json")
                .bodyValue("{\"csp-report\":{\"blocked-uri\":\"inline\"}}")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void getReportIsRejected() {
        // GET 不匹配 POST-only 的报告映射，落到 /api/** 通配代理 → 未登记路由 fail-closed 404。
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/csp-report")
                .exchange()
                .expectStatus().isNotFound();
    }
}
