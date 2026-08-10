package com.grassland.intelligence.mediaplatform;

import com.grassland.intelligence.security.IntelligenceException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MediaProcessRunner {
    private final String ffmpeg;
    private final Duration timeout;

    public MediaProcessRunner(Environment environment) {
        this.ffmpeg = environment.getProperty("media.platform.ffmpeg-path", "ffmpeg");
        this.timeout = Duration.ofMillis(environment.getProperty("media.platform.process-timeout-ms", Long.class, 180_000L));
    }

    public void ffmpeg(List<String> arguments) {
        Process process;
        try {
            process = new ProcessBuilder().command(command(arguments))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException error) {
            throw new IntelligenceException(500, "ffmpeg 不可用，请检查媒体服务镜像");
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IntelligenceException(504, "媒体处理超时，请稍后重试");
            }
            if (process.exitValue() != 0) {
                throw new IntelligenceException(502, "媒体处理失败，请稍后重试");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IntelligenceException(503, "媒体处理被中断");
        }
    }

    private List<String> command(List<String> arguments) {
        var command = new java.util.ArrayList<String>();
        command.add(ffmpeg);
        command.addAll(arguments);
        return command;
    }
}
