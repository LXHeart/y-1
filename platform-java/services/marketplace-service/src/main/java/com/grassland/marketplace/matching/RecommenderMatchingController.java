package com.grassland.marketplace.matching;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskResourceAuthorization;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Merchant-facing explainable recommendations and invitation command. */
@RestController
public class RecommenderMatchingController {

    private final MarketplaceCallerResolver callers;
    private final TaskResourceAuthorization authorization;
    private final RecommenderMatchingService matching;

    public RecommenderMatchingController(
            MarketplaceCallerResolver callers, TaskResourceAuthorization authorization,
            RecommenderMatchingService matching) {
        this.callers = callers;
        this.authorization = authorization;
        this.matching = matching;
    }

    @GetMapping("/api/tasks/{taskId}/recommendations")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @PathVariable String taskId,
            @RequestParam(required = false, defaultValue = "50") int limit,
            ServerHttpRequest request) {
        if (limit < 1 || limit > 100) {
            return Mono.error(new IllegalArgumentException("limit 必须在 1-100 之间"));
        }
        return callers.requireUser(request)
                .flatMap(caller -> authorization.requireManager(taskId, caller))
                .map(TaskResourceAuthorization.ManagedTask::task)
                .flatMap(this::requirePublished)
                .flatMap(task -> matching.recommendations(task, limit))
                .map(page -> ResponseEntity.ok(Map.of("success", true, "data", pageBody(page))));
    }

    @PostMapping("/api/tasks/{taskId}/recommendations/{accountId}/invite")
    public Mono<ResponseEntity<Map<String, Object>>> invite(
            @PathVariable String taskId, @PathVariable String accountId,
            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> authorization.requireManager(taskId, caller)
                        .map(TaskResourceAuthorization.ManagedTask::task)
                        .flatMap(this::requirePublished)
                        .flatMap(task -> matching.invite(task, accountId, caller.accountId())))
                .map(outcome -> ResponseEntity.ok(Map.of("success", true, "data", inviteBody(outcome))));
    }

    private Mono<Task> requirePublished(Task task) {
        return "published".equals(task.status())
                ? Mono.just(task)
                : Mono.error(new MarketplaceException(409, "任务当前不可邀请推荐官"));
    }

    private static Map<String, Object> pageBody(RecommenderMatchingService.RecommendationPage page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scoringVersion", page.scoringVersion());
        data.put("computedAt", page.computedAt().toString());
        data.put("eligibleCount", page.eligibleCount());
        data.put("items", page.items().stream().map(RecommenderMatchingController::matchBody).toList());
        return data;
    }

    private static Map<String, Object> matchBody(RecommenderMatch match) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", match.accountId());
        data.put("totalScore", match.totalScore());
        data.put("level", match.level());
        data.put("reputationPolicyVersion", match.reputationPolicyVersion());
        data.put("computedAt", match.computedAt().toString());
        data.put("dimensions", match.dimensions());
        data.put("reasons", match.reasons());
        data.put("invitation", match.invitation() == null ? null : invitationBody(match.invitation()));
        return data;
    }

    private static Map<String, Object> inviteBody(RecommenderMatchingService.InviteOutcome outcome) {
        Map<String, Object> data = new LinkedHashMap<>(invitationBody(outcome.invitation()));
        data.put("created", outcome.created());
        return data;
    }

    private static Map<String, Object> invitationBody(TaskRecommenderInvitation invitation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", invitation.id());
        data.put("taskId", invitation.taskId());
        data.put("recommenderAccountId", invitation.recommenderAccountId());
        data.put("scoringVersion", invitation.scoringVersion());
        data.put("createdAt", invitation.createdAt().toString());
        data.put("appliedAt", invitation.appliedAt() == null ? null : invitation.appliedAt().toString());
        return data;
    }
}
