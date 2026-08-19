package com.grassland.intelligence.creationlineage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Strongly consistent append-only writer used directly in generation response chains. */
@Service
public class CreationGenerationRecorder {

    private final CreationGenerationRepository repository;

    public CreationGenerationRecorder(CreationGenerationRepository repository) {
        this.repository = repository;
    }

    public Mono<CreationGeneration> record(Command command) {
        if (command.ownerAccountId() == null || command.ownerAccountId().isBlank()) {
            return Mono.error(new IllegalArgumentException("creation generation owner is required"));
        }
        if (command.provider() == null || command.provider().isBlank()) {
            return Mono.error(new IllegalArgumentException("creation generation provider is required"));
        }
        return repository.insert(new CreationGeneration(
                null, command.ownerAccountId(), command.organizationId(), command.kind(), command.mode(),
                command.contextSnapshotId(), command.aiRunId(), command.resolution(), command.provider(),
                command.model(), command.platformModelVersion(), command.upstreamRunId(), command.promptText(),
                command.inputSummary() == null ? Map.of()
                        : java.util.Collections.unmodifiableMap(
                                new java.util.LinkedHashMap<>(command.inputSummary())),
                command.inputMediaIds() == null ? List.of() : List.copyOf(command.inputMediaIds()),
                command.result() == null ? Map.of()
                        : java.util.Collections.unmodifiableMap(
                                new java.util.LinkedHashMap<>(command.result())),
                command.resultMediaIds() == null ? List.of() : List.copyOf(command.resultMediaIds()), null));
    }

    public record Command(
            CreationGeneration.Kind kind,
            CreationGeneration.Mode mode,
            UUID contextSnapshotId,
            UUID aiRunId,
            CreationGeneration.Resolution resolution,
            String provider,
            String model,
            Integer platformModelVersion,
            String upstreamRunId,
            String promptText,
            Map<String, Object> inputSummary,
            List<UUID> inputMediaIds,
            Map<String, Object> result,
            List<UUID> resultMediaIds,
            String ownerAccountId,
            String organizationId) {}
}
