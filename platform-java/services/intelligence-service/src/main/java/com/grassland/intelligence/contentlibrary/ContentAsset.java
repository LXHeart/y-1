package com.grassland.intelligence.contentlibrary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 素材条目（草场 PRD §4.8 / Slice 14）。镜像 {@code content_asset} 表（V18）。
 *
 * <p>物理资产存 {@code media_reference}（经三步上传），本记录是「素材业务层」——分类/标签/有效期/来源/
 * 授权/历史快照都挂这里。{@code mediaReferenceId} 是同库无 FK 引用（同 V9 media_kyb_retention 口径），
 * 挂接时把 {@code mimeType}/{@code sizeBytes} 快照一份，media 删除后仍可展示残留元信息。
 *
 * @param id              素材 id（外部引用句柄）
 * @param mediaReferenceId 关联 media_reference（物理资产）
 * @param libraryType     库类型（{@link LibraryType}）
 * @param category        分类（{@link AssetCategory}）
 * @param ownerAccountId  上传者账号
 * @param organizationId  归属组织，可空（商家库非空，个人/公共库为 null）
 * @param title           素材标题
 * @param tags            标签列表
 * @param mimeType        挂接时快照
 * @param sizeBytes       挂接时快照
 * @param validUntil      有效期，可空（=永久；公共库必填）
 * @param status          状态（{@link AssetStatus}）
 * @param version         版本号（乐观锁 + 快照版本，每次编辑 +1）
 * @param source          来源，可空（公共库必填）
 * @param licenseScope    授权范围，可空（公共库必填）
 * @param reviewNote      审核备注（公共库 rejected 时填）
 * @param reviewedBy      审核人
 * @param reviewedAt      审核时间
 * @param createdAt       创建时间
 * @param updatedAt       更新时间
 * @param deletedAt       软删时间，可空
 */
public record ContentAsset(
        UUID id,
        UUID mediaReferenceId,
        LibraryType libraryType,
        AssetCategory category,
        String ownerAccountId,
        String organizationId,
        String title,
        List<String> tags,
        String mimeType,
        Long sizeBytes,
        Instant validUntil,
        AssetStatus status,
        int version,
        String source,
        String licenseScope,
        String reviewNote,
        String reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String storeId) {

    public ContentAsset(UUID id, UUID mediaReferenceId, LibraryType libraryType, AssetCategory category,
                        String ownerAccountId, String organizationId, String title, List<String> tags,
                        String mimeType, Long sizeBytes, Instant validUntil, AssetStatus status, int version,
                        String source, String licenseScope, String reviewNote, String reviewedBy,
                        Instant reviewedAt, Instant createdAt, Instant updatedAt, Instant deletedAt) {
        this(id, mediaReferenceId, libraryType, category, ownerAccountId, organizationId, title, tags,
                mimeType, sizeBytes, validUntil, status, version, source, licenseScope, reviewNote,
                reviewedBy, reviewedAt, createdAt, updatedAt, deletedAt, null);
    }
}
