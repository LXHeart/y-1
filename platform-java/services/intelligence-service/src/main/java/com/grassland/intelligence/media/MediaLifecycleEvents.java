package com.grassland.intelligence.media;

import com.grassland.intelligence.event.EventEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class MediaLifecycleEvents {
    private MediaLifecycleEvents() {}

    static EventEnvelope reserved(MediaReference ref) {
        return event(ref, "MediaUploadReserved", 1, Map.of("status", "pending"));
    }

    static EventEnvelope activated(MediaReference ref) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("status", "active");
        extra.put("checksum", value(ref.checksum()));
        return event(ref, "MediaActivated", 2, extra);
    }

    static EventEnvelope deleted(MediaReference ref, String reason) {
        return event(ref, "MediaDeleted", 3, Map.of(
                "status", "deleted", "reason", reason == null ? "deleted" : reason));
    }

    private static EventEnvelope event(
            MediaReference ref, String eventType, long version, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mediaId", ref.id().toString());
        payload.put("ownerAccountId", ref.ownerAccountId());
        payload.put("organizationId", value(ref.organizationId()));
        payload.put("purpose", ref.purpose());
        payload.put("domainType", value(ref.domainType()));
        payload.put("domainId", value(ref.domainId()));
        payload.put("mimeType", ref.mimeType());
        payload.put("sizeBytes", ref.sizeBytes());
        payload.putAll(extra);
        return new EventEnvelope(
                ref.id() + ":" + eventType, eventType, "media_reference", ref.id().toString(),
                version, Instant.now(), ref.id().toString(), payload);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}

