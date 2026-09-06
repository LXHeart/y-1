package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.mediaplatform.MediaProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 沙箱媒体生成（任务书 #64 卡8 / §4.7）：testsrc+color lavfi 合成 540×960、2 秒、带音轨的
 * 真实 mp4——确定性强、可被 ffmpeg 合成链消费、可 ffprobe 断言。ffmpeg 不可用（CI 无二进制）
 * 回落 8 字节 ftyp 存根：归档链路仍闭环，只有真合成 IT（assumeTrue ffmpeg）需要真实产物。
 */
final class SandboxMedia {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(SandboxMedia.class);

    private static final byte[] STUB_MP4 = {0, 0, 0, 8, 'f', 't', 'y', 'p'};

    private SandboxMedia() {}

    static byte[] testsrcMp4(MediaProcessRunner runner) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("sandbox-video");
            Path output = dir.resolve("clip.mp4");
            runner.ffmpeg(List.of(
                    "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=540x960:rate=30:duration=2",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-ar", "48000", "-ac", "2",
                    "-shortest", output.toString()), Duration.ofSeconds(60));
            return Files.readAllBytes(output);
        } catch (RuntimeException | IOException error) {
            // 静默回落 8 字节存根会让真合成在远处（compose 消费存根）才炸——回落必须留痕。
            LOGGER.warn("sandbox testsrc 生成失败，回落 ftyp 存根", error);
            return STUB_MP4;
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // 临时目录兜底
                                }
                            });
                } catch (IOException ignored) {
                    // 同上
                }
            }
        }
    }

    static byte[] stubMp4() {
        return STUB_MP4;
    }
}
