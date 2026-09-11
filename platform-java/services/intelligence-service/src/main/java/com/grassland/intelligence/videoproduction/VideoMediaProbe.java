package com.grassland.intelligence.videoproduction;

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

/**
 * 视频素材服务端探测（任务书 #100 C100-11 / §6.5）：ffprobe 实测时长与音轨存在性。
 *
 * <p>边界校验用 ffprobe 结果而非浏览器声称的时长；探测经有界本地进程（AudioDurationProbe
 * 同款口径），调用方负责在工作线程边界执行（boundedElastic），不阻塞 WebFlux 事件循环。
 */
@Component
public class VideoMediaProbe {

    /** 实测结果：durationMs ≥1；hasAudio = 存在至少一条可解码音轨。 */
    public record ProbeResult(long durationMs, boolean hasAudio) {
    }

    private static final long PROCESS_TIMEOUT_SECONDS = 20L;

    private final String ffprobe;
    private final Path tempDirectory;
    private final Duration processTimeout;

    @Autowired
    public VideoMediaProbe(Environment environment) {
        this(environment.getProperty("speech.ffprobe-path", "ffprobe"),
                Path.of(environment.getProperty(
                        environment.containsProperty("speech.temp-dir") ? "speech.temp-dir"
                                : "media.platform.temp-dir",
                        "/tmp/grassland-media")));
    }

    VideoMediaProbe(String ffprobe, Path tempDirectory) {
        this(ffprobe, tempDirectory, Duration.ofSeconds(PROCESS_TIMEOUT_SECONDS));
    }

    VideoMediaProbe(String ffprobe, Path tempDirectory, Duration processTimeout) {
        this.ffprobe = ffprobe;
        this.tempDirectory = tempDirectory.toAbsolutePath().normalize();
        this.processTimeout = processTimeout;
    }

    public ProbeResult probe(byte[] bytes, String extension) {
        if (bytes == null || bytes.length == 0) {
            throw new IntelligenceException(400, "CANVAS_MEDIA_UNAVAILABLE", "素材内容为空");
        }
        Path temporary = null;
        try {
            Files.createDirectories(tempDirectory);
            temporary = Files.createTempFile(tempDirectory, "own-media-", "." + extension);
            Files.write(temporary, bytes);

            long durationMs = probeDuration(temporary);
            boolean hasAudio = probeAudio(temporary);
            return new ProbeResult(durationMs, hasAudio);
        } catch (IntelligenceException e) {
            throw e;
        } catch (IOException error) {
            throw new IntelligenceException(500, "素材探测不可用");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IntelligenceException(503, "素材探测被中断");
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

    private long probeDuration(Path file) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(List.of(
                        ffprobe, "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1", file.toString()))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IntelligenceException(504, "素材时长探测超时");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IntelligenceException(422, "无法读取素材时长");
        }
        return parseDurationMillis(output);
    }

    private boolean probeAudio(Path file) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(List.of(
                        ffprobe, "-v", "error", "-select_streams", "a", "-show_entries",
                        "stream=index", "-of", "csv=p=0", file.toString()))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IntelligenceException(504, "素材音轨探测超时");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return process.exitValue() == 0 && !output.isBlank();
    }

    /** 与 AudioDurationProbe 同口径的毫秒解析（那边是包私有，这里本地复刻）。 */
    private static long parseDurationMillis(String output) {
        try {
            double seconds = Double.parseDouble(output.trim());
            if (!Double.isFinite(seconds) || seconds <= 0) {
                throw new NumberFormatException("invalid duration");
            }
            return Math.max(1L, Math.round(seconds * 1_000d));
        } catch (NumberFormatException error) {
            throw new IntelligenceException(422, "无法读取素材时长");
        }
    }
}
