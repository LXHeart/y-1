package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 推荐官报名记录（application 聚合，HLD 5.3）。草场 Epic 4 Slice 4B。
 *
 * <p>{@code recommenderAccountId} = 报名者（断言 caller，recommender）；{@code taskId} 同库真 FK 引用 {@link Task}；
 * {@code status} 存小写 String（house style，见 {@link ApplicationStatus}）；{@code reviewedByAccountId} 为
 * accept/reject 的操作商家（caller，withdraw 时 null）；{@code decidedAt} 为 accept/reject 时间。
 */
public record TaskApplication(
        String id,
        String taskId,
        String recommenderAccountId,
        String status,
        String note,
        String reviewedByAccountId,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt
) {}
