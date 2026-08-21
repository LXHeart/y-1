package com.grassland.intelligence.moments;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.moments.MomentsGenerationController.MomentsRequest;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 朋友圈生成控制器单测：401 前置、SSE 包装、上游失败 502、任务模式绑定。控制器不持有
 * {@code CreditsClient}（积分扣退全在执行环/冻结执行内），SSE 帧内容由
 * {@link MomentsGenerationServiceTest} 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class MomentsGenerationControllerTest {

    private static final Caller CALLER = new Caller(
            "acc-1", "merchant", "session-1", null, null, "user", null, null);

    @Mock
    private IntelligenceCallerResolver callers;
    @Mock
    private MomentsGenerationService service;
    @Mock
    private MomentsTaskCreationContext contexts;
    @Mock
    private com.grassland.intelligence.contentsafety.ContentSafetyService safety;

    private MomentsGenerationController controller;

    @BeforeEach
    void setUp() {
        controller = new MomentsGenerationController(callers, service, contexts, safety);
        // 任务书 #34：safety 追加帧默认透传（mock 服务只回灌入帧）
        lenient().when(safety.appendSafetyFrame(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void anonymousRejectedBeforeCharge() {
        when(callers.resolve(any()))
                .thenReturn(Mono.error(new IntelligenceException(401, "未登录")));
        when(service.validateAndEncode(any())).thenReturn(List.of());

        StepVerifier.create(controller.generate(request("lifestyle"), exchange()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IntelligenceException.class);
                    assertThat(((IntelligenceException) error).status()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    void independentModeWrapsExecutedStreamAsSse() {
        // GL-P3-AI-001 尾巴清偿：独立模式扣分/退款在执行环内（service.generateStream 边界），
        // 控制器只包 SSE。
        when(callers.resolve(any())).thenReturn(Mono.just(CALLER));
        when(service.validateAndEncode(any())).thenReturn(List.of());
        when(service.generateStream(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(Flux.just("{\"type\":\"result\",\"copy\":\"开业大吉\"}")));

        StepVerifier.create(controller.generate(request("lifestyle"), exchange()))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
                    assertThat(decode(entity.getBody()))
                            .contains("data: {\"type\":\"result\",\"copy\":\"开业大吉\"}")
                            .contains("data: [DONE]");
                })
                .verifyComplete();
    }

    @Test
    void upstreamFailureFailsBeforeSseAs502() {
        // 独立模式执行在 SSE 之前完成：上游失败 → 502 JSON（不发 SSE 字节）；
        // 退款在执行环内（AiExecutionService.handleFailure），控制器无手动退款。
        when(callers.resolve(any())).thenReturn(Mono.just(CALLER));
        when(service.validateAndEncode(any())).thenReturn(List.of());
        when(service.generateStream(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.error(new RuntimeException("upstream down")));

        StepVerifier.create(controller.generate(request("event"), exchange()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IntelligenceException.class);
                    assertThat(((IntelligenceException) error).status()).isEqualTo(502);
                })
                .verify();
    }

    @Test
    void invalidImageRejectedBeforeChargeAs400() {
        when(service.validateAndEncode(any()))
                .thenThrow(new IntelligenceException(400, "最多上传 9 张图片"));

        StepVerifier.create(controller.generate(request("lifestyle"), exchange()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IntelligenceException.class);
                    assertThat(((IntelligenceException) error).status()).isEqualTo(400);
                })
                .verify();
        verify(callers, never()).resolve(any());
    }

    @Test
    void taskModeRequiresContextSnapshotId() {
        assertThatThrownBy(() -> new MomentsRequest("主题", "lifestyle", null, null, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("任务创作必须绑定创作上下文快照");
    }

    @Test
    void independentModeRejectsContextSnapshotId() {
        assertThatThrownBy(() -> new MomentsRequest(
                "主题", "lifestyle", null, null, false, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("独立创作不能绑定任务上下文快照");
    }

    @Test
    void unknownStyleRejectedInRequestRecord() {
        assertThatThrownBy(() -> new MomentsRequest("主题", "viral", null, null, false, null))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("朋友圈风格不合法");
    }

    @Test
    void taskModeBindsMomentsContextAndUsesFrozenExecution() {
        UUID snapshotId = UUID.randomUUID();
        when(callers.requireUser(any())).thenReturn(Mono.just(CALLER));
        MomentsTaskCreationContext.Binding binding =
                new MomentsTaskCreationContext.Binding(snapshotId, null);
        when(contexts.bind(snapshotId, "acc-1")).thenReturn(Mono.just(binding));
        when(service.validateAndEncode(any())).thenReturn(List.of());
        when(service.generateTask(any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just("{\"type\":\"result\",\"copy\":\"任务文案\"}"));

        StepVerifier.create(controller.generate(
                        new MomentsRequest("主题", "lifestyle", null, null, true, snapshotId), exchange()))
                .assertNext(entity -> assertThat(decode(entity.getBody()))
                        .contains("任务文案"))
                .verifyComplete();

        verify(contexts).bind(snapshotId, "acc-1");
        verify(service).generateTask(org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(MomentsStyle.LIFESTYLE),
                org.mockito.ArgumentMatchers.eq("主题"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(binding),
                any());
    }

    @Test
    void taskModeBindingFailurePropagates() {
        UUID snapshotId = UUID.randomUUID();
        when(callers.requireUser(any())).thenReturn(Mono.just(CALLER));
        when(contexts.bind(snapshotId, "acc-1"))
                .thenReturn(Mono.error(new IntelligenceException(409, "创作上下文不是朋友圈图文任务")));

        StepVerifier.create(controller.generate(
                        new MomentsRequest("主题", "lifestyle", null, null, true, snapshotId), exchange()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IntelligenceException.class);
                    assertThat(((IntelligenceException) error).status()).isEqualTo(409);
                })
                .verify();
    }

    // ---------------- helpers ----------------

    private static MomentsRequest request(String style) {
        return new MomentsRequest("主题", style, null, null, false, null);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/moments-generation/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}"));
    }

    private static String decode(Flux<DataBuffer> body) {
        return body.map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return new String(bytes, UTF_8);
                })
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block();
    }
}
