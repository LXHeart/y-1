package com.grassland.marketplace.taskcatalog;

import java.util.List;
import java.util.UUID;

/**
 * 提交履约交付物的请求体。{@code contentUrl} 必填且须是 http(s) 链接——它是核实的主证据，
 * 收一个空串或「已发布」之类的自由文本等于没有凭证。{@code note} 可选。
 *
 * <p>互动任务（contentForm=interaction，任务书 #23）语义重定义：{@code contentUrl} = 被互动的
 * <b>目标帖子/账号链接</b>；{@code platformHandle} = 推荐官在该平台的账号标识（互动任务必填——
 * controller 按任务 contentForm 分支校验，≤64 字符）；{@code mediaIds} = 动作截图（≥1 张由核验
 * evidence_completeness 检查，不在提交时硬拒）。
 *
 * <p>{@code mediaIds}（草场 Slice 11 Stage 2）：可选的履约附件 media_reference id 列表（截图/数据/视频等），
 * 上限 {@link #MAX_MEDIA}、去重、超量→400（IllegalArgumentException→400）。提交时逐个经 intelligence 校验
 * （purpose=engagement_attachment && active && owner==提交人）后才挂接。
 */
public record CreateSubmissionRequest(String contentUrl, String note, List<UUID> mediaIds, String platformHandle,
                                        String commentText) {

    /** 履约附件数量上限。 */
    public static final int MAX_MEDIA = 6;

    /** 平台账号标识上限（任务书 #23 R3）。 */
    public static final int MAX_PLATFORM_HANDLE = 64;

    /** 评论类互动评论文本上限（缺口清偿之九）。 */
    public static final int MAX_COMMENT_TEXT = 500;

    /** 补充说明上限（履约硬门槛：note 同步词库审核，与 intelligence 端点同限）。 */
    public static final int MAX_NOTE_TEXT = 500;

    /** 兼容 V41 之前的构造调用方（既有测试）。 */
    public CreateSubmissionRequest(String contentUrl, String note, List<UUID> mediaIds) {
        this(contentUrl, note, mediaIds, null, null);
    }

    /** 兼容 V41 单字段扩展前的构造调用方（既有测试）。 */
    public CreateSubmissionRequest(String contentUrl, String note, List<UUID> mediaIds, String platformHandle) {
        this(contentUrl, note, mediaIds, platformHandle, null);
    }

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
            if (note.length() > MAX_NOTE_TEXT) {
                throw new IllegalArgumentException("补充说明最长 " + MAX_NOTE_TEXT + " 字");
            }
        }
        if (platformHandle != null) {
            platformHandle = platformHandle.trim();
            if (platformHandle.isEmpty()) {
                platformHandle = null;
            } else if (platformHandle.length() > MAX_PLATFORM_HANDLE) {
                throw new IllegalArgumentException("平台账号标识最长 " + MAX_PLATFORM_HANDLE + " 字符");
            }
        }
        mediaIds = mediaIds == null ? List.of() : mediaIds.stream().distinct().toList();
        if (mediaIds.size() > MAX_MEDIA) {
            throw new IllegalArgumentException("附件数量上限为 " + MAX_MEDIA);
        }
    }
}
