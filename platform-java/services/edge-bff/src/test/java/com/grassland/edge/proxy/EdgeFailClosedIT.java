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
        "EDGE_ROUTE_GUEST_TRIAL_INTELLIGENCE=false",
        "EDGE_ROUTE_CONTENT_SAFETY_INTELLIGENCE=false",
        "EDGE_ROUTE_SPEECH_INTELLIGENCE=false",
        "EDGE_ROUTE_IMAGE_STUDIO_INTELLIGENCE=false",
        "EDGE_ROUTE_VIDEO_STUDIO_INTELLIGENCE=false"
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

    /** 任务书 #34：内容安全 flag 关闭 → /api/content-safety/* fail-closed 404。 */
    @Test
    void contentSafetyFlagOffFailsClosed() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/content-safety/check")
                .exchange()
                .expectStatus().isNotFound();
    }

    /** 任务书 #33：语音转写 flag 关闭 → /api/speech/transcriptions 两种允许方法都 fail-closed 404。 */
    @Test
    void speechFlagOffFailsClosed() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/speech/transcriptions")
                .exchange()
                .expectStatus().isNotFound();
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/speech/transcriptions/transcription-1")
                .exchange()
                .expectStatus().isNotFound();
    }

    /** 任务书 #43：图片编辑台 flag 关闭 → /api/image-studio/* fail-closed 404。 */
    @Test
    void imageStudioFlagOffFailsClosed() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/image-studio/matting")
                .exchange()
                .expectStatus().isNotFound();
    }

    /** 任务书 #43：视频工坊 flag 关闭 → /api/video-studio/* fail-closed 404。 */
    @Test
    void videoStudioFlagOffFailsClosed() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/api/video-studio/bgm-advice")
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
