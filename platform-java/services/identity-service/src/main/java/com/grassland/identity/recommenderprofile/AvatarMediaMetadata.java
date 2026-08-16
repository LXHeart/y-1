package com.grassland.identity.recommenderprofile;

import java.time.Instant;
import java.util.UUID;

/** intelligence 返回的推荐官头像权威元数据（账号级，无 org/domain 维度）。 */
public record AvatarMediaMetadata(
        UUID id,
        String ownerAccountId,
        String purpose,
        String status,
        String mimeType,
        long sizeBytes,
        Instant expiresAt) {
}
