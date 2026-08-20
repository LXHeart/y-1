package com.grassland.intelligence.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.grassland.intelligence.mediaplatform.MediaArtifactStore;
import com.grassland.intelligence.mediaplatform.MediaProcessRunner;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link VideoFrameExtractor} 真 ffmpeg 单测（缺口清偿之五遗留：视频帧送审）。 环境无 ffmpeg
 * 时跳过（advisory 语义与生产一致：抽帧不可用=未审）；CI 与服务镜像均内置 ffmpeg。
 */
@DisplayName("VideoFrameExtractor 帧抽取")
class VideoFrameExtractorTest {

	@TempDir
	static Path tempDir;

	private static boolean ffmpegAvailable() {
		try {
			Process process = new ProcessBuilder("ffmpeg", "-version").start();
			process.waitFor();
			return process.exitValue() == 0;
		} catch (Exception error) {
			return false;
		}
	}

	@BeforeAll
	static void requireFfmpeg() {
		assumeTrue(ffmpegAvailable(), "环境无 ffmpeg，跳过帧抽取测试");
	}

	@Test
	void extractsJpegFramesFromVideo() throws Exception {
		VideoFrameExtractor extractor = extractor(3, 1);
		byte[] video = generateVideo(3);

		List<byte[]> frames = extractor.extract(video);

		assertThat(frames).isNotEmpty();
		assertThat(frames).allSatisfy(frame -> {
			assertThat(frame.length).isGreaterThan(2);
			// jpeg SOI 魔数
			assertThat(frame[0] & 0xFF).isEqualTo(0xFF);
			assertThat(frame[1] & 0xFF).isEqualTo(0xD8);
		});
	}

	@Test
	void capsFramesAtConfiguredCount() throws Exception {
		VideoFrameExtractor extractor = extractor(2, 1);
		byte[] video = generateVideo(3);

		List<byte[]> frames = extractor.extract(video);

		assertThat(frames).hasSize(2);
	}

	@Test
	void invalidVideoBytesFailForAdvisoryDegradation() {
		VideoFrameExtractor extractor = extractor(3, 1);

		// 非视频字节：ffmpeg 非零退出 → 上抛 IntelligenceException，由审核服务降级为未审
		assertThatThrownBy(() -> extractor.extract(new byte[]{'n', 'o', 't', '-', 'v', 'i', 'd', 'e', 'o'}))
				.isInstanceOf(IntelligenceException.class);
	}

	/** lavfi testsrc 生成指定秒数的 mp4（自带视频流，无需测试夹具文件）。 */
	private static byte[] generateVideo(int seconds) throws Exception {
		Path output = Files.createTempFile("frame-extractor-source", ".mp4");
		try {
			new MediaProcessRunner(new MockEnvironment())
					.ffmpeg(List.of("-y", "-f", "lavfi", "-i", "testsrc2=duration=" + seconds + ":size=320x240:rate=10",
							"-pix_fmt", "yuv420p", output.toString()));
			return Files.readAllBytes(output);
		} finally {
			Files.deleteIfExists(output);
		}
	}

	private static VideoFrameExtractor extractor(int frameCount, int intervalSeconds) {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("ai.store-media-moderation.video-frame-count", String.valueOf(frameCount)).withProperty(
						"ai.store-media-moderation.video-frame-interval-seconds", String.valueOf(intervalSeconds));
		return new VideoFrameExtractor(new MediaProcessRunner(environment), new MediaArtifactStore(environment),
				environment);
	}
}
