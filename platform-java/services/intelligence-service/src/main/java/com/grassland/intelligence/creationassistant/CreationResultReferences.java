package com.grassland.intelligence.creationassistant;

import com.grassland.intelligence.contentlibrary.ContentAssetRepository;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Typed handles are authorized when selected; unchanged expired handles do not block text edits. */
@Component
public class CreationResultReferences {
    private final MediaReferenceRepository media;
    private final ContentAssetRepository assets;

    public CreationResultReferences(MediaReferenceRepository media, ContentAssetRepository assets) {
        this.media = media;
        this.assets = assets;
    }

    public Mono<Void> validateNew(Map<String, Object> workspace, Map<String, Object> previous, Caller caller) {
        Set<String> retained = refs(previous).stream().map(CreationResultReferences::key).collect(Collectors.toSet());
        return Flux.fromIterable(refs(workspace)).filter(ref -> !retained.contains(key(ref)))
                .concatMap(ref -> validate(ref, caller)).then();
    }

    private Mono<?> validate(Map<?, ?> ref, Caller caller) {
        UUID id;
        try { id = UUID.fromString((String) ref.get("id")); }
        catch (Exception error) { return Mono.error(new IntelligenceException(400, "结果引用 ID 无效")); }
        Mono<?> accessible = "media".equals(ref.get("refType"))
                ? media.findById(id).filter(item -> caller.accountId().equals(item.ownerAccountId())
                    && item.status() == MediaStatus.ACTIVE && item.deletedAt() == null
                    && (item.expiresAt() == null || item.expiresAt().isAfter(Instant.now())))
                : assets.findForCreation(List.of(id), caller.accountId(), caller.organizationId()).next()
                    .flatMap(asset -> media.findById(asset.mediaReferenceId())
                        .filter(item -> item.status() == MediaStatus.ACTIVE && item.deletedAt() == null
                            && (item.expiresAt() == null || item.expiresAt().isAfter(Instant.now()))));
        return accessible.switchIfEmpty(Mono.error(new IntelligenceException(404, "RESULT_REFERENCE_UNAVAILABLE", "选定的结果不存在或不可用")));
    }

    private static List<Map<?, ?>> refs(Map<String, Object> workspace) {
        if (!(workspace.get("resultRefs") instanceof List<?> refs)) return List.of();
        return refs.stream().filter(Map.class::isInstance).map(item -> (Map<?, ?>) item).collect(Collectors.toList());
    }

    private static String key(Map<?, ?> ref) { return ref.get("refType") + ":" + ref.get("id"); }
}
