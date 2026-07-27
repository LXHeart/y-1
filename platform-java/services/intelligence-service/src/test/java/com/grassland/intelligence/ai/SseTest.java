package com.grassland.intelligence.ai;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;

/**
 * {@link Sse} 字节级契约：每帧 {@code data: <payload>\n\n}（冒号后空格），末尾 {@code data: [DONE]\n\n}。
 * 前端 {@code consumeSSEStream}（fetch+getReader，按 {@code data: } 前缀解析、遇 {@code [DONE]} 终止）依赖此精确格式。
 */
class SseTest {

    @Test
    @DisplayName("多 payload 逐帧编码并以 [DONE] 收尾")
    void framesPayloadsAndTerminates() {
        Flux<DataBuffer> out = Sse.stream(
                Flux.just("{\"content\":\"a\"}", "{\"content\":\"b\"}"),
                DefaultDataBufferFactory.sharedInstance);

        String body = DataBufferUtils.join(out).map(b -> b.toString(UTF_8)).block();

        assertThat(body).isEqualTo(
                "data: {\"content\":\"a\"}\n\n"
                + "data: {\"content\":\"b\"}\n\n"
                + "data: [DONE]\n\n");
    }

    @Test
    @DisplayName("空 payload 流仍以 [DONE] 收尾（合法 SSE）")
    void emptyStreamStillTerminates() {
        Flux<DataBuffer> out = Sse.stream(Flux.empty(), DefaultDataBufferFactory.sharedInstance);
        String body = DataBufferUtils.join(out).map(b -> b.toString(UTF_8)).block();
        assertThat(body).isEqualTo("data: [DONE]\n\n");
    }

    @Test
    @DisplayName("含中文/特殊字符的 payload 原样 UTF-8 透传")
    void utf8PayloadPreserved() {
        Flux<DataBuffer> out = Sse.stream(
                Flux.just("{\"content\":\"你好世界\"}"),
                DefaultDataBufferFactory.sharedInstance);
        List<DataBuffer> buffers = out.collectList().block();
        assertThat(buffers).hasSize(2);
        String frame0 = buffers.get(0).toString(UTF_8);
        assertThat(frame0).isEqualTo("data: {\"content\":\"你好世界\"}\n\n");
        buffers.forEach(DataBufferUtils::release);
    }
}
