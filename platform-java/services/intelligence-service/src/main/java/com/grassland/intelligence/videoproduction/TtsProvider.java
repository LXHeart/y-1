package com.grassland.intelligence.videoproduction;

import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * TTS provider 端口（任务书 #64 卡5，P1 MiniMax TTS）。与 {@link VideoGenerationProvider}
 * 同构的 submit/poll 异步模型——vendor DTO 与状态名留在实现内。
 */
public interface TtsProvider {

    String id();

    Mono<TtsResult> submit(TtsCommand command);

    Mono<TtsResult> poll(String providerTaskId);

    record TtsCommand(UUID audioId, String model, String text, String voice) {}

    /**
     * @param durationMs sandbox 在 SUCCEEDED 时直接给出解析得出的精确时长（纯 Java 合成）；
     *                   真实 provider 为 null，由归档层 ffprobe 实测
     */
    record TtsResult(
            State state, String providerTaskId, String audioUrl, Integer durationMs,
            String errorCode, String errorMessage) {
        public enum State { QUEUED, PROCESSING, SUCCEEDED, FAILED }
    }
}
