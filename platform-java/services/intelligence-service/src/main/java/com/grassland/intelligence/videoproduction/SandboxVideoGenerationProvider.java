package com.grassland.intelligence.videoproduction;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Deterministic local provider used when no paid channel is configured.
 *
 * <p>The result reference is an opaque placeholder consumed by
 * {@link VideoAssetArchiveService}: in sandbox mode the archive stores stub
 * bytes and rewrites the job to {@code /api/media/{id}} before any client
 * sees it. Provider URLs are never persisted or returned, so the placeholder
 * must not be shaped like a routable API path.
 */
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
                "sandbox://video/" + command.jobId(),
                command.durationSeconds(), null, null));
    }

    @Override
    public Mono<ProviderResult> poll(String providerTaskId, int requestedDurationSeconds) {
        return Mono.just(new ProviderResult(
                ProviderResult.State.SUCCEEDED, providerTaskId, 100,
                "sandbox://video/" + providerTaskId.substring("sandbox:".length()),
                requestedDurationSeconds, null, null));
    }
}
