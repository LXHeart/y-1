package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 履约交付物：推荐官为某条已接受的报名提交的凭证（PRD 九「推荐官提交凭证」）。
 *
 * <p>{@code status}：submitted（待商家核验）/ accepted（商家确认履约时置） / rejected（被退回，可重交）。
 * {@code recommenderAccountId} 冗余存一份，提交人自查不必回表读 application。
 * {@code platformHandle}（任务书 #23）：互动任务（contentForm=interaction）必填的推荐官平台账号标识，
 * 供 interaction_screenshot 核验比对截图账号；其余任务为 null。
 */
public record EngagementSubmission(
        String id,
        String applicationId,
        String recommenderAccountId,
        String contentUrl,
        String note,
        String status,
        String reviewNote,
        Instant reviewedAt,
        Instant createdAt,
        /** D-03 确认窗口 Temporal workflow 启动成功/AlreadyStarted 的时刻；null 由 dispatcher 补启。 */
        Instant confirmationWorkflowStartedAt,
        String platformHandle,
        /** 缺口清偿之九：评论任务的推荐官评论文本（≤500；提交链路已过 L1 词库审核）。 */
        String commentText
) {
    public boolean isPending() {
        return SubmissionStatus.SUBMITTED.dbValue().equalsIgnoreCase(status);
    }

    /** 兼容 V41 之前的全参构造调用方（既有测试）；无平台账号标识。 */
    public EngagementSubmission(
            String id, String applicationId, String recommenderAccountId, String contentUrl,
            String note, String status, String reviewNote, Instant reviewedAt, Instant createdAt,
            Instant confirmationWorkflowStartedAt) {
        this(id, applicationId, recommenderAccountId, contentUrl, note, status, reviewNote,
                reviewedAt, createdAt, confirmationWorkflowStartedAt, null, null);
    }

    /** 兼容 V46 之前的全参构造调用方（既有测试）；无评论文本。 */
    public EngagementSubmission(
            String id, String applicationId, String recommenderAccountId, String contentUrl,
            String note, String status, String reviewNote, Instant reviewedAt, Instant createdAt,
            Instant confirmationWorkflowStartedAt, String platformHandle) {
        this(id, applicationId, recommenderAccountId, contentUrl, note, status, reviewNote,
                reviewedAt, createdAt, confirmationWorkflowStartedAt, platformHandle, null);
    }
}
