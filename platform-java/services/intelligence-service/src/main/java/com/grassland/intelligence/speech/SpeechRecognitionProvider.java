package com.grassland.intelligence.speech;

import java.util.UUID;
import java.util.Set;
import com.grassland.intelligence.ai.ProviderInvocation;
import reactor.core.publisher.Mono;

public interface SpeechRecognitionProvider {

    String provider();

    default Set<String> aliases() {
        return Set.of();
    }

    Mono<Result> transcribe(Command command);

    record Command(
            UUID mediaId,
            String checksum,
            String language,
            long durationMs,
            byte[] audio,
            String mimeType,
            ProviderInvocation invocation) {
        public Command(
                UUID mediaId,
                String checksum,
                String language,
                long durationMs,
                byte[] audio) {
            this(mediaId, checksum, language, durationMs, audio, "application/octet-stream", null);
        }
    }

    record Result(
            String text,
            String detectedLanguage,
            int inputTokens,
            int outputTokens,
            boolean sandbox,
            int billedSeconds) {
        public Result(
                String text,
                String detectedLanguage,
                int inputTokens,
                int outputTokens,
                boolean sandbox) {
            this(text, detectedLanguage, inputTokens, outputTokens, sandbox, 0);
        }
    }
}
