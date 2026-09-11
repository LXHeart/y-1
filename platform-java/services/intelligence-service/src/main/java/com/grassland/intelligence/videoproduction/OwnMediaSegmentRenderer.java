package com.grassland.intelligence.videoproduction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

import com.grassland.intelligence.mediaplatform.MediaProcessRunner;

/**
 * 自有素材段渲染（任务书 #100 C100-12 / §6.5）。
 *
 * <p>裁剪区间在保存时已校验 {@code trimEnd-trimStart == plannedSeconds×1000}，段长即目标
 * 长度（不 pad 不加速）。统一规格与既有段一致（h264 / 30fps / yuv420p；aac 48k stereo）
 * 保证 concat 可 copy；尺寸沿用目标 resolution 保持比例补边。音轨三策略：
 * source 保留区间原音（重采样规格化）、narration 混入既有 TTS 配音（无则静音）、mute 静音。
 * 全部 ffmpeg 经 {@link MediaProcessRunner} 在 worker 线程执行（调用方保证 boundedElastic）。
 */
@Component
public class OwnMediaSegmentRenderer {

    private static final String VIDEO_ENCODER = "libx264";

    private final MediaProcessRunner runner;
    private final VideoMediaProbe probe;

    public OwnMediaSegmentRenderer(MediaProcessRunner runner, VideoMediaProbe probe) {
        this.runner = runner;
        this.probe = probe;
    }

    /**
     * 渲染自有素材段。
     *
     * @param mediaBytes   素材对象内容（worker 读取；调用方已复核大小与权限）
     * @param source       制作来源（own-media，trim/audioMode 已通过保存校验）
     * @param plannedSeconds 镜头时长（== 截取长度）
     * @param resolution   目标分辨率（如 1080x1920）
     * @param ttsAudioBytes narration 模式的 TTS 配音（可空——audio 行由 spawn 侧保证存在性，
     *                      渲染期缺失按静音处理不失败）
     */
    public void render(Path workDir, Path segment, byte[] mediaBytes, VideoShotSource source,
            int plannedSeconds, String resolution, byte[] ttsAudioBytes) throws IOException {
        Path mediaFile = workDir.resolve("own-" + source.shotId() + ".mp4");
        Files.write(mediaFile, mediaBytes);
        double trimStartSeconds = source.trimStartMs() / 1000.0;
        double targetSeconds = (source.trimEndMs() - source.trimStartMs()) / 1000.0;
        int width = VideoResolution.widthOf(resolution);
        int height = VideoResolution.heightOf(resolution);

        List<String> args = new ArrayList<>(List.of("-y",
                "-ss", String.valueOf(trimStartSeconds),
                "-t", String.valueOf(targetSeconds),
                "-i", mediaFile.getFileName().toString()));

        String audioLabel;
        switch (source.audioMode()) {
            case VideoShotSource.AUDIO_SOURCE -> {
                // 保留原音：输入 0:a 重采样规格化即可，无第二输入
                audioLabel = "0:a?";
            }
            case VideoShotSource.AUDIO_NARRATION -> {
                if (ttsAudioBytes != null) {
                    Path audioFile = workDir.resolve("own-audio-" + source.shotId() + ".bin");
                    Files.write(audioFile, ttsAudioBytes);
                    args.addAll(List.of("-i", audioFile.getFileName().toString()));
                    audioLabel = "1:a";
                } else {
                    args.addAll(List.of("-f", "lavfi", "-t", String.valueOf(targetSeconds + 1),
                            "-i", "anullsrc=r=48000:cl=stereo"));
                    audioLabel = "1:a";
                }
            }
            default -> {
                // mute：显式静音轨（保持段恒有音频流，与 concat 规格一致）
                args.addAll(List.of("-f", "lavfi", "-t", String.valueOf(targetSeconds + 1),
                        "-i", "anullsrc=r=48000:cl=stereo"));
                audioLabel = "1:a";
            }
        }

        StringBuilder filters = new StringBuilder();
        filters.append("[0:v]scale=").append(width).append(':').append(height)
                .append(":force_original_aspect_ratio=decrease,")
                .append("pad=").append(width).append(':').append(height)
                .append(":(ow-iw)/2:(oh-ih)/2,fps=30,setsar=1[v];");
        if (VideoShotSource.AUDIO_SOURCE.equals(source.audioMode())) {
            // 原音存在性在保存时以 ffprobe 实测闸过；这里可选轨缺省静音兜底
            filters.append("[").append(audioLabel).append("]aresample=48000,aformat=channel_layouts=stereo,")
                    .append("apad,atrim=duration=").append(String.valueOf(targetSeconds))
                    .append(",asetpts=PTS-STARTPTS[a]");
        } else {
            filters.append("[").append(audioLabel).append("]apad,atrim=duration=")
                    .append(String.valueOf(targetSeconds))
                    .append(",asetpts=PTS-STARTPTS[a]");
        }

        args.addAll(List.of("-filter_complex", filters.toString(),
                "-map", "[v]", "-map", "[a]",
                "-c:v", VIDEO_ENCODER, "-preset", "veryfast", "-pix_fmt", "yuv420p", "-r", "30",
                "-c:a", "aac", "-ar", "48000", "-ac", "2",
                "-t", String.valueOf(targetSeconds),
                segment.getFileName().toString()));
        runner.ffmpeg(args, Duration.ofMinutes(10), workDir);
    }

    /** 段实测（缓存指纹与结算口径复用 probe）。 */
    public long probeDurationMs(byte[] segmentBytes) {
        return probe.probe(segmentBytes, "mp4").durationMs();
    }
}
