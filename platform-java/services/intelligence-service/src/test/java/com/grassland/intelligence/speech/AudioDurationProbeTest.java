package com.grassland.intelligence.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.security.IntelligenceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioDurationProbeTest {

    @TempDir
    Path temp;

    @Test
    void parsesFinitePositiveSecondsAsMilliseconds() {
        assertThat(AudioDurationProbe.parseDurationMillis("12.345\n")).isEqualTo(12_345L);
        assertThat(AudioDurationProbe.parseDurationMillis(" 0.0001 ")).isEqualTo(1L);
        assertThat(AudioDurationProbe.parseDurationMillis("1e100")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsMissingNonFiniteOrNonPositiveDurations() {
        for (String invalid : new String[] {null, "", "0", "-1", "NaN", "Infinity", "not-a-number"}) {
            assertThatThrownBy(() -> AudioDurationProbe.parseDurationMillis(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("无法读取语音音频时长");
        }
    }

    @Test
    void invokesFfprobeWithTheBoundedContractAndDeletesTheAudioFile() throws Exception {
        Path executable = temp.resolve("ffprobe-test");
        Files.writeString(executable, """
                #!/bin/sh
                printf '%s\\n' "$@" > "$0.args"
                printf '12.345\\n'
                """);
        assertThat(executable.toFile().setExecutable(true)).isTrue();

        AudioDurationProbe probe = new AudioDurationProbe(executable.toString(), temp);
        assertThat(probe.probe(new byte[] {1, 2, 3})).isEqualTo(12_345L);

        List<String> arguments = Files.readAllLines(temp.resolve("ffprobe-test.args"));
        assertThat(arguments.subList(0, 6)).containsExactly(
                "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1");
        Path audioFile = Path.of(arguments.get(6));
        assertThat(audioFile.getParent()).isEqualTo(temp);
        assertThat(audioFile).doesNotExist();
    }

    @Test
    void mapsNonzeroFfprobeExitTo422AndDeletesTheAudioFile() throws Exception {
        Path executable = temp.resolve("ffprobe-nonzero");
        Files.writeString(executable, """
                #!/bin/sh
                printf 'private ffprobe body' >&2
                exit 7
                """);
        assertThat(executable.toFile().setExecutable(true)).isTrue();

        AudioDurationProbe probe = new AudioDurationProbe(executable.toString(), temp);
        assertThatThrownBy(() -> probe.probe(new byte[] {1, 2, 3}))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(error -> assertThat(((IntelligenceException) error).status()).isEqualTo(422));
        try (var files = Files.list(temp)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("speech-audio-"))
                    .toList()).isEmpty();
        }
    }

    @Test
    void mapsBoundedProbeTimeoutTo504TerminatesProcessAndDeletesTheAudioFile() throws Exception {
        Path executable = temp.resolve("ffprobe-timeout");
        Path pidFile = temp.resolve("ffprobe-timeout.pid");
        Files.writeString(executable, """
                #!/bin/sh
                printf '%%s\\n' "$$" > '%s'
                exec sleep 30
                """.formatted(pidFile));
        assertThat(executable.toFile().setExecutable(true)).isTrue();

        AudioDurationProbe probe = new AudioDurationProbe(
                executable.toString(), temp, Duration.ofSeconds(2));
        assertThatThrownBy(() -> probe.probe(new byte[] {1, 2, 3}))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(error -> assertThat(((IntelligenceException) error).status()).isEqualTo(504));

        long pid = Long.parseLong(Files.readString(pidFile).trim());
        for (int i = 0; i < 20 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) {
            TimeUnit.MILLISECONDS.sleep(25);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        try (var files = Files.list(temp)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("speech-audio-"))
                    .toList()).isEmpty();
        }
    }
}
