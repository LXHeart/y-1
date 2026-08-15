package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Applies review policy and records every manual, policy, or SLA decision in audit + outbox. */
@Component
public class TaskReviewService {

    private final TaskRepository tasks;
    private final TaskReviewRepository reviews;
    private final TaskReviewPolicy policy;
    private final OutboxRepository outbox;

    public TaskReviewService(TaskRepository tasks, TaskReviewRepository reviews,
                             TaskReviewPolicy policy, OutboxRepository outbox) {
        this.tasks = tasks;
        this.reviews = reviews;
        this.policy = policy;
        this.outbox = outbox;
    }

    public Mono<Task> submit(Task task) {
        return reviews.merchantStats(task.ownerAccountId())
                .map(stats -> policy.decide(task.id(), stats))
                .flatMap(decision -> reviews.append(task.id(), "submitted", null, decision.reason())
                        .then(decision.requiresReview()
                                ? outbox.append(submittedEvent(task, decision)).thenReturn(task)
                                : approveSystem(task, "policy_" + decision.mode(), decision.reason())));
    }

    public Mono<Task> approveSystem(Task task, String source, String note) {
        if (task.requirements().commissionLadder() != null) {
            try {
                task.requirements().commissionLadder().validateReserve(task.bountyCents());
            } catch (IllegalArgumentException error) {
                return Mono.error(new MarketplaceException(400, error.getMessage()));
            }
        }
        return tasks.reviewApprove(task.id(), task.version(), null)
                .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务审核状态已变更")))
                .flatMap(approved -> reviews.append(approved.id(), "approved", null,
                                source + ": " + note)
                        .then(outbox.append(publishedEvent(approved, source)))
                        .thenReturn(approved));
    }

    private static EventEnvelope submittedEvent(Task task, TaskReviewPolicy.Decision decision) {
        Map<String, Object> payload = basePayload(task);
        payload.put("title", task.title());
        payload.put("reviewMode", decision.mode());
        payload.put("reviewPolicyVersion", TaskReviewPolicy.VERSION);
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskSubmittedForReview", "Task",
                task.id(), task.version(), Instant.now(), null, payload);
    }

    private static EventEnvelope publishedEvent(Task task, String reviewSource) {
        Map<String, Object> payload = basePayload(task);
        payload.put("title", task.title());
        payload.put("reviewSource", reviewSource);
        if (task.applicationDeadline() != null) {
            payload.put("applicationDeadline", task.applicationDeadline().toString());
        }
        return new EventEnvelope(UUID.randomUUID().toString(), "TaskPublished", "Task",
                task.id(), task.version(), Instant.now(), null, payload);
    }

    private static Map<String, Object> basePayload(Task task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("organizationId", task.organizationId());
        payload.put("ownerAccountId", task.ownerAccountId());
        payload.put("version", task.version());
        if (task.storeId() != null) payload.put("storeId", task.storeId());
        return payload;
    }
}
