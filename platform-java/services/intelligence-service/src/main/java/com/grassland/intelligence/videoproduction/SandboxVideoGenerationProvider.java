package com.grassland.intelligence.videoproduction;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Deterministic local provider used when no paid channel is configured. */
@Component
public class SandboxVideoGenerationProvider implements VideoGenerationProvider {

    @Override
    public String id() {
        return "sandbox";
    }

    @Override
    public Mono<ProviderResult> submit(ProviderCommand command) {
        return Mono.just(new ProviderResult(
                ProviderResult.State.SUCCEEDED, "sandbox:" + command.jobId(), 100,
                "/api/video-production/sandbox/videos/" + command.jobId(),
                command.durationSeconds(), null, null));
    }

    @Override
    public Mono<ProviderResult> poll(String providerTaskId, int requestedDurationSeconds) {
        return Mono.just(new ProviderResult(
                ProviderResult.State.SUCCEEDED, providerTaskId, 100, null,
                requestedDurationSeconds, null, null));
    }
}
