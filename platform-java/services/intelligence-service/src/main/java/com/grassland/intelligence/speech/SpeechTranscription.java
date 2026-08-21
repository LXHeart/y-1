package com.grassland.intelligence.speech;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted transcription request. 全文与句级分段（segments）随结果落库；其余 provider
 * 载荷不保留。分段来自 verbose_json 的 segments[]（字幕对齐的结构化产出）。
 */
public record SpeechTranscription(UUID id, UUID mediaReferenceId, String ownerAccountId, String organizationId,
		String requestedLanguage, String detectedLanguage, long durationMs, String status, String transcriptText,
		String provider, String model, Integer platformModelVersion, UUID aiRunId, String failureCode,
		java.util.List<SpeechRecognitionProvider.Segment> segments, Instant createdAt, Instant updatedAt,
		Instant completedAt) {
	public SpeechTranscription {
		segments = segments == null ? java.util.List.of() : java.util.List.copyOf(segments);
	}
}
