package com.grassland.intelligence.speech;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class SandboxSpeechRecognitionProvider implements SpeechRecognitionProvider {

    @Override
    public String provider() {
        return "sandbox";
    }

    @Override
    public Mono<Result> transcribe(Command command) {
        if (command == null
                || command.audio() == null
                || command.checksum() == null || command.checksum().isBlank()
                || command.language() == null || command.language().isBlank()
                || command.durationMs() < 0) {
            return Mono.error(new IllegalArgumentException("Sandbox 语音识别参数不完整"));
        }
        String checksumPrefix = command.checksum().substring(0, Math.min(12, command.checksum().length()));
        return Mono.just(new Result(
                "[Sandbox] language=" + command.language() + " checksum=" + checksumPrefix,
                command.language(), 0, 0, true));
    }
}
