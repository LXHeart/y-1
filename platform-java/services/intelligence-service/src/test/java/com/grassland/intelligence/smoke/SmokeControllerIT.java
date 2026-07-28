package com.grassland.intelligence.smoke;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 冒烟端点端到端（草场 intelligence Slice 1）：edge 断言 → callerResolver → 平台默认 Qwen 流式 → SSE 字节级透传。
 * WireMock 托管 Qwen，断言 401（无断言）+ 200 流式（{@code text/event-stream} + {@code X-Accel-Buffering: no}
 * + {@code data: <json>\n\n} + {@code [DONE]）+ outbox 表已迁移（Flyway 通）。
 */
class SmokeControllerIT extends IntelligenceItSupport {

    @BeforeEach
    void stubQwen() {
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200).withBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"草场是\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"内容平台\"}}]}\n\n"
                + "data: [DONE]\n\n")));
    }

    @Test
    @DisplayName("无断言 → 401")
    void unauthenticatedRejected() {
        client().post().uri("/api/intelligence/smoke/chat")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("有断言 → 200 流式 SSE，逐块透传 + [DONE] 收尾，content-type / X-Accel-Buffering 正确")
    void streamsBlockByBlockSse() {
        byte[] body = client().post().uri("/api/intelligence/smoke/chat")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "介绍草场"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                .expectBody().returnResult().getResponseBody();

        assertThat(new String(body, UTF_8)).isEqualTo(
                "data: {\"content\":\"草场是\"}\n\n"
                + "data: {\"content\":\"内容平台\"}\n\n"
                + "data: [DONE]\n\n");
    }

    @Test
    @DisplayName("outbox 表已由 Flyway 建好（DB 地基通）")
    void outboxTableMigrated() {
        Integer count = db.sql("""
                        SELECT COUNT(*)::int AS c
                        FROM information_schema.tables
                        WHERE table_schema = current_schema()
                          AND table_name = 'intelligence_outbox'
                        """)
                .map(r -> r.get("c", Integer.class)).one().block();
        assertThat(count).isEqualTo(1);
    }
}
