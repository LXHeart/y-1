package com.grassland.intelligence.speech;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpeechAudioPolicyTest {

    @Test
    void acceptsOnlyTheSignatureExpectedForEachDeclaredMimeType() {
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/wav", riffWaveBytes())).isTrue();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/x-wav", riffWaveBytes())).isTrue();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/ogg", oggBytes())).isTrue();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/webm", ebmlBytes())).isTrue();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/mp4", mp4Bytes())).isTrue();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/mpeg", id3Bytes())).isTrue();
        assertThat(SpeechAudioPolicy.hasExpectedSignature(
                "audio/mpeg", new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64})).isTrue();

        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/mpeg", pngBytes())).isFalse();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/mpeg", riffWaveBytes())).isFalse();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/wav", oggBytes())).isFalse();
    }

    @Test
    void rejectsMissingShortOrUnsupportedInputs() {
        assertThat(SpeechAudioPolicy.hasExpectedSignature(null, riffWaveBytes())).isFalse();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/wav", null)).isFalse();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("audio/wav", new byte[] {'R', 'I', 'F'})).isFalse();
        assertThat(SpeechAudioPolicy.hasExpectedSignature("application/octet-stream", riffWaveBytes())).isFalse();
    }

    private static byte[] riffWaveBytes() {
        return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
    }

    private static byte[] oggBytes() {
        return new byte[] {'O', 'g', 'g', 'S'};
    }

    private static byte[] ebmlBytes() {
        return new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3};
    }

    private static byte[] mp4Bytes() {
        return new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p'};
    }

    private static byte[] id3Bytes() {
        return new byte[] {'I', 'D', '3'};
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
    }
}
