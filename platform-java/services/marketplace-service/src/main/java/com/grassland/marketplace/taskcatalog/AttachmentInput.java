package com.grassland.marketplace.taskcatalog;

import java.util.UUID;

/**
 * 挂接附件的输入（草场 Slice 11 Stage 2）：提交履约时，每个 mediaId 经 {@code IntelligenceMediaClient.metadata}
 * 校验通过（purpose=engagement_attachment && active && owner==提交人）后，连同快照元数据一起交给
 * {@code SubmissionAttachmentRepository.attach}。
 *
 * <p>{@code mimeType} 可空（media 记录允许）；{@code sizeBytes} 沿用 intelligence 端 long，这里用 {@code Long}
 * 以便与 DB 的 bigint（可空列）一致。
 */
public record AttachmentInput(UUID mediaId, String mimeType, Long sizeBytes) {}
