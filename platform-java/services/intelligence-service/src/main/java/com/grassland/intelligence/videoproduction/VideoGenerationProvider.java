package com.grassland.intelligence.videoproduction;

import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Provider port. Vendor DTOs and status names remain behind implementations. */
public interface VideoGenerationProvider {

    String id();

    Mono<ProviderResult> submit(ProviderCommand command);

    Mono<ProviderResult> poll(String providerTaskId, int requestedDurationSeconds);

    record ProviderCommand(
            UUID jobId, String model, String prompt, List<String> images,
            int durationSeconds, String aspectRatio) {}

    record ProviderResult(
            State state, String providerTaskId, Integer progress, String resultUrl,
            Integer durationSeconds, String errorCode, String errorMessage) {
        public enum State { QUEUED, PROCESSING, UNKNOWN, SUCCEEDED, FAILED }
    }
}
