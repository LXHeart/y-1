package com.grassland.intelligence.media;

import com.grassland.intelligence.mediaplatform.MediaArtifactStore;
import com.grassland.intelligence.mediaplatform.MediaProcessRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 门店媒体视频帧抽取（缺口清偿之五遗留：视频帧送审）：把 confirm 后服务端可得的视频字节 经 ffmpeg 抽成最多 N 张 jpeg 帧，供
 * {@link StoreMediaModerationService} 逐帧送多模态审核。
 *
 * <p>
 * 帧采样是「每 interval 秒一帧、最多 frame-count 帧」（fps=1/interval + -frames:v），不依赖
 * 视频时长探测——短视频自然少抽，超长截断。阻塞式（ProcessBuilder），调用方须在 boundedElastic 上调度（对齐
 * PlatformMediaService 口径）。ffmpeg 不可用/处理失败抛
 * {@link com.grassland.intelligence.security.IntelligenceException}，由审核服务
 * advisory 降级为未审。 帧零产出（无视频流/损坏）返回空列表，同样按未审降级——不把「读不懂」伪装成通过或拦截。
 */
@Component
public class VideoFrameExtractor {

	private final MediaProcessRunner processes;
	private final MediaArtifactStore artifacts;
	private final int frameCount;
	private final int frameIntervalSeconds;

	public VideoFrameExtractor(MediaProcessRunner processes, MediaArtifactStore artifacts, Environment environment) {
		this.processes = processes;
		this.artifacts = artifacts;
		this.frameCount = Math.max(1,
				Math.min(environment.getProperty("ai.store-media-moderation.video-frame-count", Integer.class, 3), 6));
		int interval = environment.getProperty("ai.store-media-moderation.video-frame-interval-seconds", Integer.class,
				5);
		this.frameIntervalSeconds = Math.max(1, Math.min(interval, 60));
	}

	/** 抽帧（阻塞）。返回 jpeg 字节列表（≤frameCount，可能为空）；临时文件无论成败都清理。 */
	public List<byte[]> extract(byte[] videoBytes) {
		Path input = artifacts.createPath(".mp4");
		try {
			Files.write(input, videoBytes);
			String prefix = input.getParent().resolve(input.getFileName().toString().replace(".mp4", "")).toString();
			processes.ffmpeg(List.of("-y", "-i", input.toString(), "-vf", "fps=1/" + frameIntervalSeconds, "-frames:v",
					String.valueOf(frameCount), "-q:v", "4", prefix + "-%03d.jpg"));
			List<byte[]> frames = new ArrayList<>();
			for (int index = 1; index <= frameCount; index++) {
				Path frame = Path.of(prefix + "-" + String.format("%03d", index) + ".jpg");
				if (!Files.isRegularFile(frame)) {
					break;
				}
				frames.add(Files.readAllBytes(frame));
			}
			return List.copyOf(frames);
		} catch (IOException error) {
			throw new UncheckedIOException("视频帧临时文件读写失败", error);
		} finally {
			cleanup(input);
		}
	}

	/** 帧输出与输入同前缀成族，逐个尽力删除；失败留给 MediaArtifactStore 的 TTL 清理兜底。 */
	private void cleanup(Path input) {
		try {
			Files.deleteIfExists(input);
			String prefix = input.getFileName().toString().replace(".mp4", "");
			try (var files = Files.list(input.getParent())) {
				files.filter(path -> path.getFileName().toString().startsWith(prefix + "-")).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException ignored) {
						// TTL 清理兜底
					}
				});
			}
		} catch (IOException ignored) {
			// TTL 清理兜底
		}
	}
}
