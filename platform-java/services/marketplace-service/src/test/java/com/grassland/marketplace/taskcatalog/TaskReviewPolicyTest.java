package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskReviewPolicyTest {
    private final TaskReviewPolicy policy = new TaskReviewPolicy(true, 3, 10, 2_000);

    @Test
    void newOrPreviouslyRejectedMerchantReceivesFullReview() {
        assertThat(policy.decide("task-1", new TaskReviewRepository.MerchantReviewStats(2, 0)))
                .extracting(TaskReviewPolicy.Decision::mode, TaskReviewPolicy.Decision::requiresReview)
                .containsExactly("full", true);
        assertThat(policy.decide("task-2", new TaskReviewRepository.MerchantReviewStats(20, 1)).mode())
                .isEqualTo("full");
    }

    @Test
    void trustedMerchantIsExempt() {
        assertThat(policy.decide("task-3", new TaskReviewRepository.MerchantReviewStats(10, 0)))
                .extracting(TaskReviewPolicy.Decision::mode, TaskReviewPolicy.Decision::requiresReview)
                .containsExactly("exempt", false);
    }

    @Test
    void samplingIsStableForTheSameTask() {
        TaskReviewPolicy.Decision first = policy.decide(
                "stable-task", new TaskReviewRepository.MerchantReviewStats(5, 0));
        TaskReviewPolicy.Decision second = policy.decide(
                "stable-task", new TaskReviewRepository.MerchantReviewStats(5, 0));
        assertThat(second).isEqualTo(first);
        assertThat(first.mode()).isEqualTo("sampled");
    }
}
