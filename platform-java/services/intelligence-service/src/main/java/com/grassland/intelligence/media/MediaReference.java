package com.grassland.intelligence.media;

import java.time.Instant;
import java.util.UUID;

/**
 * media_reference 领域记录（草场 Slice 8 第二步）。镜像 {@code media_reference} 表全字段。
 *
 * <p>{@code objectKey} 是内部 S3/local key，**不是外部授权凭据**——读必须经 {@code MediaController} 鉴权后签发 presigned URL。
 *
 * @param id              媒体 id（外部引用句柄；URL 里用的是它，不是 objectKey）
 * @param ownerAccountId  归属账号
 * @param organizationId  归属组织，可空
 * @param purpose         用途（{@link MediaPurpose}）
 * @param domainType      关联领域类型，可空
 * @param domainId        关联领域 id，可空
 * @param objectKey       最终 S3/local 对象 key（内部，从不暴露 PUT 凭据）
 * @param uploadKey       临时直传 key，可空（服务端生成资产为 null）
 * @param mimeType        MIME 类型
 * @param sizeBytes       字节大小
 * @param checksum        sha256 hex，可空（pending 时尚未算）
 * @param source          来源（upload / generated / local）
 * @param status          生命周期状态（{@link MediaStatus}）
 * @param createdAt       创建时间
 * @param expiresAt       TTL 到期，可空（=永久）
 * @param deletedAt       软删时间，可空（删除审计）
 */
public record MediaReference(
        UUID id,
        String ownerAccountId,
        String organizationId,
        String purpose,
        String domainType,
        String domainId,
        String objectKey,
        String uploadKey,
        String mimeType,
        long sizeBytes,
        String checksum,
        String source,
        MediaStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant deletedAt) {

    /** 服务端生成资产等无临时 upload key 的便捷构造器。 */
    public MediaReference(
            UUID id,
            String ownerAccountId,
            String organizationId,
            String purpose,
            String domainType,
            String domainId,
            String objectKey,
            String mimeType,
            long sizeBytes,
            String checksum,
            String source,
            MediaStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant deletedAt) {
        this(id, ownerAccountId, organizationId, purpose, domainType, domainId,
                objectKey, null, mimeType, sizeBytes, checksum, source, status,
                createdAt, expiresAt, deletedAt);
    }
}
