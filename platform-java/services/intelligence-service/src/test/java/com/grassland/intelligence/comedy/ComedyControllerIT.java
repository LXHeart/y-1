package com.grassland.intelligence.comedy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 脱口秀端到端（草场 intelligence Slice 2）：401 无断言、400 参数非法、402 积分不足（不流式）、
 * 200 流式（扣积分 + 李继刚 prompt + SSE 字节契约）。Ai 能力与积分用 {@link MockitoBean} 隔离，
 * 断言真实 controller 编排（断言→扣费→prompt 组装→SSE 帧）。
 */
class ComedyControllerIT extends IntelligenceItSupport {

    @MockitoBean
    private AiCapabilityAdapter ai;

    @MockitoBean
    private CreditsClient credits;

    @BeforeEach
    void stubDefaults() {
        reset(ai, credits);
        when(credits.consume(any(), any())).thenReturn(Mono.empty());
    }

    private String signed() {
        return sign(UUID.randomUUID().toString(), "recommender");
    }

    @Test
    @DisplayName("无断言 → 401，不扣积分")
    void unauthenticatedRejected() {
        client().post().uri("/api/comedy-generation/generate-script")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "加班", "duration", 60))
                .exchange().expectStatus().isUnauthorized();
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("题材为空 → 400，不扣积分（校验在扣费前）")
    void invalidTopicRejected() {
        client().post().uri("/api/comedy-generation/generate-script")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "   ", "duration", 60))
                .exchange().expectStatus().isBadRequest();
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("时长越界 → 400")
    void invalidDurationRejected() {
        client().post().uri("/api/comedy-generation/generate-script")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "加班", "duration", 10))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("积分不足 → 402，不发 SSE、不调 AI")
    void insufficientCreditsRejected() {
        when(credits.consume(any(), any())).thenReturn(Mono.error(new InsufficientCreditsException()));
        client().post().uri("/api/comedy-generation/generate-script")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "加班", "duration", 60))
                .exchange().expectStatus().isEqualTo(402);
        verify(ai, never()).startTextRun(any());
    }

    @Test
    @DisplayName("成功 → 200 流式 SSE：扣 comedy_generation + 李继刚 prompt(duration/wordCount/主题) + 逐块 + [DONE]")
    void streamsComedyScript() {
        ArgumentCaptor<CreditFeature> featureCaptor = ArgumentCaptor.forClass(CreditFeature.class);
        when(credits.consume(any(), featureCaptor.capture())).thenReturn(Mono.empty());

        ArgumentCaptor<TextRunCommand> cmdCaptor = ArgumentCaptor.forClass(TextRunCommand.class);
        when(ai.startTextRun(cmdCaptor.capture()))
                .thenReturn(Flux.just(new ChatChunk("【铺垫】"), new ChatChunk("【爆点】")));

        byte[] body = client().post().uri("/api/comedy-generation/generate-script")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "职场加班", "duration", 60))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                .expectBody().returnResult().getResponseBody();

        assertThat(new String(body, UTF_8)).isEqualTo(
                "data: {\"content\":\"【铺垫】\"}\n\n"
                + "data: {\"content\":\"【爆点】\"}\n\n"
                + "data: [DONE]\n\n");

        // 扣的是脱口秀功能键
        assertThat(featureCaptor.getValue()).isEqualTo(CreditFeature.COMEDY_GENERATION);
        // prompt 组装：system 含 duration/wordCount（60×4.5=270），user 含主题
        TextRunCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.messages()).hasSize(2);
        assertThat(cmd.messages().get(0).role()).isEqualTo("system");
        assertThat(cmd.messages().get(0).content()).contains("总时长约 60 秒（约 270 字）");
        assertThat(cmd.messages().get(1).role()).isEqualTo("user");
        assertThat(cmd.messages().get(1).content()).contains("职场加班");
    }
}
