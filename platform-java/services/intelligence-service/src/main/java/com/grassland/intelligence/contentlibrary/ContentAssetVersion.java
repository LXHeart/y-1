package com.grassland.intelligence.contentlibrary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 素材历史快照（草场 PRD §4.8 / Slice 14）。镜像 {@code content_asset_version} 表（V18）。
 *
 * <p>PRD §4.8「素材更新后不覆盖已进入任务的历史快照」：每次编辑落新 version 整行镜像，历史不可改
 * （镜像 task_version V11 / platform_model_config_history V7 的不可变快照范式）。
 *
 * @param assetId         所属素材
 * @param version         版本号（与 {@link ContentAsset#version()} 对应）
 * @param libraryType     库类型（快照时刻）
 * @param category        分类（快照时刻）
 * @param ownerAccountId  上传者
 * @param organizationId  归属组织，可空
 * @param title           标题（快照时刻）
 * @param tags            标签（快照时刻）
 * @param mimeType        MIME（快照时刻）
 * @param sizeBytes       字节大小（快照时刻）
 * @param validUntil      有效期（快照时刻）
 * @param source          来源（快照时刻）
 * @param licenseScope    授权范围（快照时刻）
 * @param snapshottedAt   快照落库时间
 * @param snapshottedBy   执行编辑的账号
 */
public record ContentAssetVersion(
        UUID assetId,
        int version,
        LibraryType libraryType,
        AssetCategory category,
        String ownerAccountId,
        String organizationId,
        String title,
        List<String> tags,
        String mimeType,
        Long sizeBytes,
        Instant validUntil,
        String source,
        String licenseScope,
        Instant snapshottedAt,
        String snapshottedBy) {
}
