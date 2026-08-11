package com.grassland.marketplace.taskcatalog;

/** Immutable business context used by one verification decision. */
public record VerificationTaskContext(
        String taskId, int taskVersion, String title, String description,
        String contentForm, String platform, String storeId,
        String applicationId, String recommenderAccountId,
        String submissionId, String contentUrl, String submittedAt) {

    public static VerificationTaskContext capture(
            Task task, TaskApplication application, EngagementSubmission submission) {
        return new VerificationTaskContext(
                task.id(), task.version(), task.title(), task.description(),
                task.contentForm(), task.platform(), task.storeId(),
                application.id(), application.recommenderAccountId(),
                submission.id(), submission.contentUrl(),
                submission.createdAt() == null ? null : submission.createdAt().toString());
    }
}
