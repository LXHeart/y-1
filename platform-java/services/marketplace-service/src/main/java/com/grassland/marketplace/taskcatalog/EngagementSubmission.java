package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 履约交付物：推荐官为某条已接受的报名提交的凭证（PRD 九「推荐官提交凭证」）。
 *
 * <p>{@code status}：submitted（待商家核验）/ accepted（商家确认履约时置） / rejected（被退回，可重交）。
 * {@code recommenderAccountId} 冗余存一份，提交人自查不必回表读 application。
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
        Instant createdAt
) {
    public boolean isPending() {
        return SubmissionStatus.SUBMITTED.dbValue().equalsIgnoreCase(status);
    }
}
