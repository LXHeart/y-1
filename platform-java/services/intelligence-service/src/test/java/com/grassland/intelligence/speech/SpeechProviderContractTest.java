package com.grassland.intelligence.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SpeechProviderContractTest {

    @Test
    void sandboxIsDeterministicAndDoesNotEchoAudio() {
        byte[] audio = "raw-audio-secret".getBytes(StandardCharsets.UTF_8);
        SpeechRecognitionProvider.Command command = new SpeechRecognitionProvider.Command(
                UUID.randomUUID(), "sha256-value", "zh-CN", 12_000L, audio);
        SandboxSpeechRecognitionProvider sandbox = new SandboxSpeechRecognitionProvider();

        SpeechRecognitionProvider.Result first = sandbox.transcribe(command).block();
        SpeechRecognitionProvider.Result second = sandbox.transcribe(command).block();

        assertThat(first).isEqualTo(second);
        assertThat(first.text()).startsWith("[Sandbox]")
                .contains("zh-CN", "sha256-value")
                .doesNotContain("raw-audio-secret");
        assertThat(first.sandbox()).isTrue();
        assertThat(sandbox.provider()).isEqualTo("sandbox");
    }

    @Test
    void sandboxSafelyHandlesShortChecksum() {
        SpeechRecognitionProvider.Command command = new SpeechRecognitionProvider.Command(
                UUID.randomUUID(), "short", "auto", 1L, new byte[] {1, 2, 3});

        assertThat(new SandboxSpeechRecognitionProvider().transcribe(command).block().text())
                .contains("auto", "short");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCommands")
    void sandboxRejectsInvalidProviderCommands(
            String description, SpeechRecognitionProvider.Command command) {
        SandboxSpeechRecognitionProvider sandbox = new SandboxSpeechRecognitionProvider();

        assertThatThrownBy(() -> sandbox.transcribe(command).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Sandbox 语音识别参数不完整");
    }

    private static Stream<Arguments> invalidCommands() {
        UUID mediaId = UUID.randomUUID();
        byte[] audio = new byte[] {1};
        return Stream.of(
                Arguments.of("null audio", new SpeechRecognitionProvider.Command(
                        mediaId, "checksum", "auto", 1L, null)),
                Arguments.of("empty checksum", new SpeechRecognitionProvider.Command(
                        mediaId, " ", "auto", 1L, audio)),
                Arguments.of("empty language", new SpeechRecognitionProvider.Command(
                        mediaId, "checksum", " ", 1L, audio)),
                Arguments.of("negative duration", new SpeechRecognitionProvider.Command(
                        mediaId, "checksum", "auto", -1L, audio)));
    }
}
