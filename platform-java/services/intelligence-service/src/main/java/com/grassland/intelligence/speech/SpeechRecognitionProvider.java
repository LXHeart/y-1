package com.grassland.intelligence.speech;

import java.util.UUID;
import java.util.Set;
import com.grassland.intelligence.ai.ProviderInvocation;
import reactor.core.publisher.Mono;

public interface SpeechRecognitionProvider {

	String provider();

	default Set<String> aliases() {
		return Set.of();
	}

	Mono<Result> transcribe(Command command);

	record Command(UUID mediaId, String checksum, String language, long durationMs, byte[] audio, String mimeType,
			ProviderInvocation invocation) {
		public Command(UUID mediaId, String checksum, String language, long durationMs, byte[] audio) {
			this(mediaId, checksum, language, durationMs, audio, "application/octet-stream", null);
		}
	}

	record Result(String text, String detectedLanguage, int inputTokens, int outputTokens, boolean sandbox,
			int billedSeconds, java.util.List<Segment> segments) {
		public Result(String text, String detectedLanguage, int inputTokens, int outputTokens, boolean sandbox) {
			this(text, detectedLanguage, inputTokens, outputTokens, sandbox, 0, java.util.List.of());
		}

		public Result(String text, String detectedLanguage, int inputTokens, int outputTokens, boolean sandbox,
				int billedSeconds) {
			this(text, detectedLanguage, inputTokens, outputTokens, sandbox, billedSeconds, java.util.List.of());
		}

		public Result {
			segments = segments == null ? java.util.List.of() : java.util.List.copyOf(segments);
		}
	}

	/**
	 * 句级时间戳（任务书 #41 尾巴）：Whisper 兼容 verbose_json 的 segments[]，秒为单位的
	 * 起/止时间。sandbox/不支持分段的 provider 返回空列表——消费方（字幕工作台）回落启发式分轴。
	 */
	record Segment(double startSeconds, double endSeconds, String text) {
	}
}
