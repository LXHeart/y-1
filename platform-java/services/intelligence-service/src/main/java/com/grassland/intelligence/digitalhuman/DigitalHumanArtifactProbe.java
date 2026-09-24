package com.grassland.intelligence.digitalhuman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 受控产物探针（任务书 #105F C105F-03 / K09）：blocking ffprobe——固定参数数组（不拼 shell、
 * 不接受用户路径外输入）、10 秒超时、输出仅取 stdout JSON。调用方负责 boundedElastic 离线程。
 *
 * <p>
 * 验证（卡步骤 2）：H264 + AAC、有声轨、时长与 manifest ±200ms、宽高与 manifest 一致——
 * 不靠扩展名入库；损坏/无声轨/错时长一律失败。
 */
public final class DigitalHumanArtifactProbe {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final long DURATION_TOLERANCE_MS = 200;

	private DigitalHumanArtifactProbe() {
	}

	/** ffprobe 事实（只含保存校验需要的字段）。 */
	public record ArtifactInfo(String videoCodec, String audioCodec, Long durationMs, Integer width, Integer height,
			long sizeBytes) {

		static final ArtifactInfo EMPTY = new ArtifactInfo(null, null, null, null, null, 0L);
	}

	/** 校验期望（来自 RecordingManifest.video）。 */
	public record Expectation(long durationMs, Integer width, Integer height) {
	}

	/** 探测失败（损坏/无声轨/无法解析）→ 调用方映射 dh_media_invalid。 */
	public static final class ProbeException extends RuntimeException {

		public ProbeException(String message) {
			super(message);
		}
	}

	/** 探测受控文件；ffprobe 不存在/超时/非零退出/解析失败均抛 {@link ProbeException}。 */
	public static ArtifactInfo probe(Path controlledFile) {
		List<String> command = List.of("ffprobe", "-v", "error", "-print_format", "json", "-show_streams",
				"-show_format", controlledFile.toString());
		Process process;
		try {
			process = new ProcessBuilder(command).start();
		} catch (IOException failure) {
			throw new ProbeException("ffprobe 不可用：" + failure.getMessage());
		}
		byte[] stdout;
		try {
			stdout = process.getInputStream().readAllBytes();
			if (!process.waitFor(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new ProbeException("ffprobe 超时。");
			}
		} catch (InterruptedException interrupted) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new ProbeException("ffprobe 被中断。");
		} catch (IOException failure) {
			process.destroyForcibly();
			throw new ProbeException("ffprobe 输出读取失败。");
		}
		if (process.exitValue() != 0) {
			throw new ProbeException("文件不是可解析的媒体容器。");
		}
		return parse(stdout, sizeOf(controlledFile));
	}

	private static long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (IOException failure) {
			throw new ProbeException("受控文件不可读。");
		}
	}

	private static ArtifactInfo parse(byte[] stdout, long sizeBytes) {
		JsonNode root;
		try {
			root = JSON.readTree(stdout);
		} catch (IOException invalid) {
			throw new ProbeException("ffprobe 输出不可解析。");
		}
		String videoCodec = null;
		String audioCodec = null;
		Long durationMs = null;
		Integer width = null;
		Integer height = null;
		List<String> problems = new ArrayList<>();
		for (JsonNode stream : root.path("streams")) {
			if ("video".equals(stream.path("codec_type").asText())) {
				if (videoCodec != null) {
					problems.add("多视频轨");
				}
				videoCodec = stream.path("codec_name").asText(null);
				width = stream.path("width").isNumber() ? stream.path("width").asInt() : null;
				height = stream.path("height").isNumber() ? stream.path("height").asInt() : null;
				if (stream.hasNonNull("duration")) {
					durationMs = Math.round(stream.path("duration").asDouble() * 1000);
				}
			} else if ("audio".equals(stream.path("codec_type").asText())) {
				if (audioCodec != null) {
					problems.add("多音频轨");
				}
				audioCodec = stream.path("codec_name").asText(null);
				if (durationMs == null && stream.hasNonNull("duration")) {
					durationMs = Math.round(stream.path("duration").asDouble() * 1000);
				}
			}
		}
		if (durationMs == null && root.path("format").hasNonNull("duration")) {
			durationMs = Math.round(root.path("format").path("duration").asDouble() * 1000);
		}
		if (!problems.isEmpty()) {
			throw new ProbeException(String.join("；", problems));
		}
		if (videoCodec == null || audioCodec == null) {
			throw new ProbeException("缺少视频或音频轨。");
		}
		return new ArtifactInfo(videoCodec, audioCodec, durationMs, width, height, sizeBytes);
	}

	/** 保存校验：容器/编码/时长/尺寸与 manifest 期望一致（±200ms）。失败抛 {@link ProbeException}。 */
	public static void validate(ArtifactInfo info, Expectation expectation) {
		if (!"h264".equals(info.videoCodec())) {
			throw new ProbeException("视频编码不是 H264：" + info.videoCodec());
		}
		if (!"aac".equals(info.audioCodec())) {
			throw new ProbeException("音频编码不是 AAC：" + info.audioCodec());
		}
		if (info.durationMs() == null
				|| Math.abs(info.durationMs() - expectation.durationMs()) > DURATION_TOLERANCE_MS) {
			throw new ProbeException("时长与录制清单不符（探测 " + (info.durationMs() == null ? "未知" : info.durationMs() + "ms")
					+ "，期望 " + expectation.durationMs() + "ms）。");
		}
		if (expectation.width() != null && !expectation.width().equals(info.width())) {
			throw new ProbeException("宽度与录制清单不符。");
		}
		if (expectation.height() != null && !expectation.height().equals(info.height())) {
			throw new ProbeException("高度与录制清单不符。");
		}
	}
}
