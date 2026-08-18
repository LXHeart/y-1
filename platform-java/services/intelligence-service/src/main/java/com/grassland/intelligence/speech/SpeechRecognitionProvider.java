package com.grassland.intelligence.speech;

import java.util.UUID;
import reactor.core.publisher.Mono;

public interface SpeechRecognitionProvider {

    String provider();

    Mono<Result> transcribe(Command command);

    record Command(
            UUID mediaId,
            String checksum,
            String language,
            long durationMs,
            byte[] audio) {}

    record Result(
            String text,
            String detectedLanguage,
            int inputTokens,
            int outputTokens,
            boolean sandbox) {}
}
