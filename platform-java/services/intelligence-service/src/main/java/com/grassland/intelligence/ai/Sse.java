package com.grassland.intelligence.ai;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import reactor.core.publisher.Flux;

/**
 * SSE 帧编码工具——字节级复刻 legacy 契约，保证前端既有 {@code consumeSSEStream}（fetch+getReader，
 * 按 {@code data: } 前缀解析、遇 {@code [DONE]} 终止）零改动即可消费。
 *
 * <p>每帧 {@code data: <payload>\n\n}（冒号后有空格），末尾 {@code data: [DONE]\n\n}。
 * 用 {@link DataBuffer} 手写帧（不用 Spring {@code ServerSentEvent} 自动格式化——后者空格/转义差异会破坏契约）。
 * Flux 取消（客户端断开）由 Reactor 自动传播到上游 {@code QwenClient}（取消上游 HTTP 流）。
 */
public final class Sse {

    private static final String DONE = "[DONE]";

    private Sse() {}

    /** 把一组已序列化的 payload（通常是 JSON 字符串）逐帧编码，并以 {@code [DONE]} 收尾。 */
    public static Flux<DataBuffer> stream(Flux<String> payloads, DataBufferFactory factory) {
        return payloads
                .map(p -> frame("data: " + p + "\n\n", factory))
                .concatWith(Flux.just(frame("data: " + DONE + "\n\n", factory)));
    }

    private static DataBuffer frame(String text, DataBufferFactory factory) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        DataBuffer buf = factory.allocateBuffer(bytes.length);
        buf.write(bytes);
        return buf;
    }
}
