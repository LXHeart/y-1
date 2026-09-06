package com.grassland.intelligence.mediaplatform;

import com.grassland.intelligence.security.IntelligenceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MediaProcessRunner {

    /** ffmpeg 输出尾部随异常带出的上限——进度行已在拼接前剔除。 */
    private static final int STDERR_TAIL_LIMIT = 400;

    private final String ffmpeg;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public MediaProcessRunner(Environment environment) {
        this(
                environment.getProperty("media.platform.ffmpeg-path", "ffmpeg"),
                Duration.ofMillis(
                        environment.getProperty("media.platform.process-timeout-ms", Long.class, 180_000L)));
    }

    MediaProcessRunner(String ffmpeg, Duration timeout) {
        this.ffmpeg = ffmpeg;
        this.timeout = timeout;
    }

    public void ffmpeg(List<String> arguments) {
        ffmpeg(arguments, timeout, null);
    }

    /** 任务书 #64 卡8：合成链长任务可传更长超时（成片上限 600s）。 */
    public void ffmpeg(List<String> arguments, Duration timeoutOverride) {
        ffmpeg(arguments, timeoutOverride, null);
    }

    /**
     * 任务书 #64 卡8：合成在专属临时目录内以相对路径执行——subtitles 滤镜路径由此避开
     * 绝对路径的冒号转义（macOS /var/folders 与滤镜参数分隔符冲突）。
     *
     * <p>输出落临时文件再取尾部：进程侧零死锁风险；退出码非零/超时时尾部随异常消息
     * 带出（会经 failTask 落 {@code video_production_task.error_message}，排障唯一线索）。
     */
    public void ffmpeg(List<String> arguments, Duration timeoutOverride, java.nio.file.Path workingDirectory) {
        Duration effective = timeoutOverride == null ? timeout : timeoutOverride;
        Path outputLog = null;
        Process process;
        try {
            outputLog = Files.createTempFile("ffmpeg-output-", ".log");
        } catch (IOException error) {
            throw new IntelligenceException(500, "ffmpeg 不可用，请检查媒体服务镜像");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder().command(command(arguments))
                    .redirectErrorStream(true).redirectOutput(outputLog.toFile());
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            process = builder.start();
        } catch (IOException error) {
            deleteQuietly(outputLog);
            throw new IntelligenceException(500, "ffmpeg 不可用，请检查媒体服务镜像");
        }
        try {
            if (!process.waitFor(effective.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IntelligenceException(504, "媒体处理超时，请稍后重试" + outputTail(outputLog));
            }
            if (process.exitValue() != 0) {
                throw new IntelligenceException(502, "媒体处理失败，请稍后重试" + outputTail(outputLog));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IntelligenceException(503, "媒体处理被中断");
        } finally {
            deleteQuietly(outputLog);
        }
    }

    private static String outputTail(Path log) {
        try {
            String content = Files.readString(log, StandardCharsets.UTF_8)
                    .replaceAll("(?m)^frame=.*", "")
                    .replaceAll("[\\r\\n]+", " | ")
                    .replaceAll("(\\| )+", "| ")
                    .trim();
            if (content.isEmpty()) {
                return "（ffmpeg 无输出）";
            }
            return " ffmpeg输出尾部[" + content.substring(Math.max(0, content.length() - STDERR_TAIL_LIMIT))
                    + "]";
        } catch (IOException error) {
            return "（ffmpeg 输出读取失败）";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 临时文件，尽力清理。
        }
    }

    private List<String> command(List<String> arguments) {
        var command = new java.util.ArrayList<String>();
        command.add(ffmpeg);
        command.addAll(arguments);
        return command;
    }
}
