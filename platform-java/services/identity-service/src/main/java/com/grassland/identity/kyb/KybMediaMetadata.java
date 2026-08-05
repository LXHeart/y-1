package com.grassland.identity.kyb;

import java.time.Instant;
import java.util.UUID;

/** intelligence 返回的 KYB 媒体权威元数据。 */
public record KybMediaMetadata(
        UUID id,
        String ownerAccountId,
        String organizationId,
        String purpose,
        String domainType,
        String domainId,
        String status,
        String mimeType,
        long sizeBytes,
        Instant expiresAt) {
}
