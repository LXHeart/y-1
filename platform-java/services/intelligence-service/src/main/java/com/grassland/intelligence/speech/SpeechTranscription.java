package com.grassland.intelligence.speech;

import java.time.Instant;
import java.util.UUID;

/** A persisted transcription request. Provider payloads are deliberately not retained. */
public record SpeechTranscription(
        UUID id,
        UUID mediaReferenceId,
        String ownerAccountId,
        String organizationId,
        String requestedLanguage,
        String detectedLanguage,
        long durationMs,
        String status,
        String transcriptText,
        String provider,
        String model,
        Integer platformModelVersion,
        UUID aiRunId,
        String failureCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {}
