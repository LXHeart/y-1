package com.grassland.intelligence.speech;

import com.grassland.intelligence.security.IntelligenceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Extracts server-authoritative audio duration through a bounded local ffprobe process. */
@Component
public class AudioDurationProbe {

    private static final long PROCESS_TIMEOUT_SECONDS = 15L;

    private final String ffprobe;
    private final Path tempDirectory;
    private final Duration processTimeout;

    @Autowired
    public AudioDurationProbe(Environment environment) {
        this(
                environment.getProperty("speech.ffprobe-path", "ffprobe"),
                Path.of(environment.getProperty(
                        "speech.temp-dir",
                        environment.getProperty("media.platform.temp-dir", "/tmp/grassland-media"))));
    }

    AudioDurationProbe(String ffprobe, Path tempDirectory) {
        this(ffprobe, tempDirectory, Duration.ofSeconds(PROCESS_TIMEOUT_SECONDS));
    }

    AudioDurationProbe(String ffprobe, Path tempDirectory, Duration processTimeout) {
        this.ffprobe = ffprobe;
        this.tempDirectory = tempDirectory.toAbsolutePath().normalize();
        this.processTimeout = processTimeout;
    }

    public long probe(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("语音音频不能为空");
        }
        Path temporary = null;
        Process process = null;
        try {
            Files.createDirectories(tempDirectory);
            temporary = Files.createTempFile(tempDirectory, "speech-audio-", ".tmp");
            Files.write(temporary, bytes);
            process = new ProcessBuilder(List.of(
                            ffprobe,
                            "-v", "error",
                            "-show_entries", "format=duration",
                            "-of", "default=noprint_wrappers=1:nokey=1",
                            temporary.toString()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IntelligenceException(504, "语音音频时长探测超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IntelligenceException(422, "无法读取语音音频时长");
            }
            return parseDurationMillis(output);
        } catch (IOException error) {
            throw new IntelligenceException(500, "语音音频时长探测不可用");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IntelligenceException(503, "语音音频时长探测被中断");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup without exposing the server-side path.
                }
            }
        }
    }

    static long parseDurationMillis(String output) {
        try {
            if (output == null || output.isBlank()) {
                throw new NumberFormatException("missing duration");
            }
            double seconds = Double.parseDouble(output.trim());
            if (!Double.isFinite(seconds) || seconds <= 0) {
                throw new NumberFormatException("invalid duration");
            }
            double milliseconds = seconds * 1_000d;
            if (!Double.isFinite(milliseconds) || milliseconds >= Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            return Math.max(1L, Math.round(milliseconds));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("无法读取语音音频时长");
        }
    }
}
