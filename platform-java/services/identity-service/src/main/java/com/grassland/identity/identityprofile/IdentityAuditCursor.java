package com.grassland.identity.identityprofile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

record IdentityAuditCursor(Instant occurredAt, String id) {

    String encode() {
        String raw = occurredAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static IdentityAuditCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            Instant occurredAt = Instant.parse(raw.substring(0, separator));
            String id = java.util.UUID.fromString(raw.substring(separator + 1)).toString();
            return new IdentityAuditCursor(occurredAt, id);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid cursor", error);
        }
    }
}
