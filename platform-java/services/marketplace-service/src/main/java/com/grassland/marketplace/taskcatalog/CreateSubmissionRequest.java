package com.grassland.marketplace.taskcatalog;

import java.util.List;
import java.util.UUID;

/**
 * 提交履约交付物的请求体。{@code contentUrl} 必填且须是 http(s) 链接——它是核实的主证据，
 * 收一个空串或「已发布」之类的自由文本等于没有凭证。{@code note} 可选。
 *
 * <p>{@code mediaIds}（草场 Slice 11 Stage 2）：可选的履约附件 media_reference id 列表（截图/数据/视频等），
 * 上限 {@link #MAX_MEDIA}、去重、超量→400（IllegalArgumentException→400）。提交时逐个经 intelligence 校验
 * （purpose=engagement_attachment && active && owner==提交人）后才挂接。
 */
public record CreateSubmissionRequest(String contentUrl, String note, List<UUID> mediaIds) {

    /** 履约附件数量上限。 */
    public static final int MAX_MEDIA = 6;

    public CreateSubmissionRequest {
        if (contentUrl == null || contentUrl.isBlank()) {
            throw new IllegalArgumentException("contentUrl is required");
        }
        contentUrl = contentUrl.trim();
        String lower = contentUrl.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("contentUrl must be an http(s) link");
        }
        if (note != null) {
            note = note.isBlank() ? null : note.trim();
        }
        mediaIds = mediaIds == null ? List.of() : mediaIds.stream().distinct().toList();
        if (mediaIds.size() > MAX_MEDIA) {
            throw new IllegalArgumentException("附件数量上限为 " + MAX_MEDIA);
        }
    }
}
