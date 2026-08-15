package com.grassland.marketplace.taskcatalog;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deterministic task-review sampling policy derived only from marketplace review history. */
@Component
public class TaskReviewPolicy {

    public static final String VERSION = "task-review-v1";

    private final boolean enabled;
    private final int sampledMinApproved;
    private final int exemptMinApproved;
    private final int sampleRateBps;

    public TaskReviewPolicy(
            @Value("${marketplace.task-review.policy-enabled:true}") boolean enabled,
            @Value("${marketplace.task-review.sampled-min-approved:3}") int sampledMinApproved,
            @Value("${marketplace.task-review.exempt-min-approved:10}") int exemptMinApproved,
            @Value("${marketplace.task-review.sample-rate-bps:2000}") int sampleRateBps) {
        this.enabled = enabled;
        this.sampledMinApproved = Math.max(1, sampledMinApproved);
        this.exemptMinApproved = Math.max(this.sampledMinApproved, exemptMinApproved);
        this.sampleRateBps = Math.max(0, Math.min(10_000, sampleRateBps));
    }

    public Decision decide(String taskId, TaskReviewRepository.MerchantReviewStats history) {
        if (!enabled || history.rejected() > 0 || history.approved() < sampledMinApproved) {
            return new Decision("full", true,
                    VERSION + ": full review; approved=" + history.approved() + ", rejected=" + history.rejected());
        }
        if (history.approved() >= exemptMinApproved) {
            return new Decision("exempt", false,
                    VERSION + ": trusted merchant exempt; approved=" + history.approved() + ", rejected=0");
        }
        boolean sampled = bucket(taskId) < sampleRateBps;
        return new Decision("sampled", sampled,
                VERSION + ": deterministic sample " + (sampled ? "selected" : "bypassed")
                        + "; approved=" + history.approved() + ", rateBps=" + sampleRateBps);
    }

    private static int bucket(String taskId) {
        UUID stable = UUID.nameUUIDFromBytes((VERSION + ":" + taskId).getBytes(StandardCharsets.UTF_8));
        return Math.floorMod(stable.hashCode(), 10_000);
    }

    public record Decision(String mode, boolean requiresReview, String reason) {}
}
