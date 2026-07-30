package com.grassland.intelligence.ai;

import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI 能力端口（HLD §12.2 {@code AiCapabilityAdapter}）。Slice 1 仅落文本流式；
 * 后续 slice 扩 {@code startMediaRun} / {@code validateCredential}（BYOK 校验）/ cancel。
 *
 * <p>供应商 DTO、错误码、限流、重试停留在实现层（如 {@code QwenClient}），不进入领域模型。
 */
public interface AiCapabilityAdapter {

    /** 流式文本生成；上游每段 delta.content 映射为一个 {@link ChatChunk}（空段已过滤），自然结束即完成。 */
    Flux<ChatChunk> startTextRun(TextRunCommand command);

    /** 非流式文本完成；返回 OpenAI-compatible {@code choices[0].message.content}。 */
    Mono<String> completeText(TextCompletionCommand command);

    /** 非流式多模态完成（草场 Slice 10 视频改编）：文本 + 图片片断，自定义超时。默认不支持。 */
    default Mono<String> completeMultimodal(List<ContentPart> parts, Duration timeout) {
        return Mono.error(new UnsupportedOperationException("multimodal completion not supported"));
    }
}
