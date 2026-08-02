package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 推荐官报名记录（application 聚合，HLD 5.3）。草场 Epic 4 Slice 4B。
 *
 * <p>{@code recommenderAccountId} = 报名者（断言 caller，recommender）；{@code taskId} 同库真 FK 引用 {@link Task}；
 * {@code status} 存小写 String（house style，见 {@link ApplicationStatus}）；{@code reviewedByAccountId} 为
 * accept/reject 的操作商家（caller，withdraw 时 null）；{@code decidedAt} 为 accept/reject 时间。
 *
 * <p>{@code bountyCents} = <b>accept 时冻结的赏金快照</b>（GL-P1-TASK-001：snapshot-pinning）。accept/结算读这列
 * 而非可变 {@code task.bounty_cents}——否则 accept 后改 task 赏金（全字段 revise）会让结算读到新值、走错 fund 分支。
 * 非 fund 任务（accept 时 bounty=0）这列为 0。pending 报名也带这列（create 时取 task 当前赏金），但只有在 accept
 * 落库时才「冻结」语义成立（pending 期间 task 赏金变会经 create 重新取值，不影响已 accept 的行）。
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
        Instant confirmedAt,
        long bountyCents
) {}
