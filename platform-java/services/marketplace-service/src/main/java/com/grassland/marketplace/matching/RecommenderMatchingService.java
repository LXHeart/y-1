package com.grassland.marketplace.matching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.Task;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Candidate ranking and invitation orchestration within the marketplace consistency boundary. */
@Service
public class RecommenderMatchingService {

    private static final Comparator<RecommenderMatch> RANKING = Comparator
            .comparingInt(RecommenderMatch::totalScore).reversed()
            .thenComparing(Comparator.comparingInt(
                    (RecommenderMatch match) -> match.dimensionScore("platformFit")).reversed())
            .thenComparing(Comparator.comparingInt(
                    (RecommenderMatch match) -> match.dimensionScore("completionRate")).reversed())
            .thenComparing(RecommenderMatch::accountId);

    private final RecommenderMatchingRepository candidates;
    private final TaskRecommenderInvitationRepository invitations;
    private final ReputationService reputations;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;
    private final DeterministicMatchScorer scorer = new DeterministicMatchScorer();
    private final Clock clock;

    @Autowired
    public RecommenderMatchingService(
            RecommenderMatchingRepository candidates,
            TaskRecommenderInvitationRepository invitations,
            ReputationService reputations, OutboxRepository outbox,
            TransactionalOperator transactions, ObjectMapper mapper) {
        this(candidates, invitations, reputations, outbox, transactions, mapper, Clock.systemUTC());
    }

    RecommenderMatchingService(
            RecommenderMatchingRepository candidates,
            TaskRecommenderInvitationRepository invitations,
            ReputationService reputations, OutboxRepository outbox,
            TransactionalOperator transactions, ObjectMapper mapper, Clock clock) {
        this.candidates = candidates;
        this.invitations = invitations;
        this.reputations = reputations;
        this.outbox = outbox;
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Mono<RecommendationPage> recommendations(Task task, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return ranked(task).map(matches -> new RecommendationPage(
                DeterministicMatchScorer.VERSION, matches.isEmpty() ? clock.instant() : matches.getFirst().computedAt(),
                matches.size(), matches.stream().limit(boundedLimit).toList()));
    }

    public Mono<InviteOutcome> invite(Task task, String accountId, String actorAccountId) {
        return invitations.find(task.id(), accountId)
                .map(existing -> new InviteOutcome(existing, false, null))
                .switchIfEmpty(ranked(task)
                        .flatMap(matches -> Mono.justOrEmpty(matches.stream()
                                .filter(match -> match.accountId().equals(accountId)).findFirst()))
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "推荐官不在当前候选池")))
                        .flatMap(match -> createInvitation(task, match, actorAccountId)));
    }

    private Mono<InviteOutcome> createInvitation(Task task, RecommenderMatch match, String actorAccountId) {
        String snapshot = json(scoreSnapshot(match));
        Mono<InviteOutcome> work = invitations.create(
                        task.id(), match.accountId(), actorAccountId,
                        DeterministicMatchScorer.VERSION, snapshot)
                .flatMap(created -> outbox.append(invitationEvent(task, match.accountId(), created))
                        .thenReturn(new InviteOutcome(created, true, match)))
                .switchIfEmpty(invitations.find(task.id(), match.accountId())
                        .map(existing -> new InviteOutcome(existing, false, null)));
        return transactions.transactional(work);
    }

    private Mono<List<RecommenderMatch>> ranked(Task task) {
        Instant computedAt = clock.instant();
        return candidates.findCandidates(task.id(), task.platform(), task.ownerAccountId()).collectList()
                .flatMap(candidateList -> reputations.snapshots(
                                candidateList.stream().map(MatchingCandidate::accountId).toList(), computedAt)
                        .map(snapshots -> candidateList.stream()
                                .filter(candidate -> snapshots.get(candidate.accountId()).evaluation()
                                        .effectiveLevel().number() >= task.minRecommenderLevel())
                                .map(candidate -> scorer.score(
                                        candidate, snapshots.get(candidate.accountId()), computedAt))
                                .sorted(RANKING).toList()));
    }

    private EventEnvelope invitationEvent(
            Task task, String recommenderAccountId, TaskRecommenderInvitation invitation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("invitationId", invitation.id());
        payload.put("recommenderAccountId", recommenderAccountId);
        payload.put("taskOwnerId", task.ownerAccountId());
        return new EventEnvelope(
                UUID.randomUUID().toString(), "TaskRecommenderInvited", "TaskRecommenderInvitation",
                invitation.id(), 1, invitation.createdAt(), null, payload);
    }

    private static Map<String, Object> scoreSnapshot(RecommenderMatch match) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scoringVersion", DeterministicMatchScorer.VERSION);
        snapshot.put("accountId", match.accountId());
        snapshot.put("totalScore", match.totalScore());
        snapshot.put("level", match.level());
        snapshot.put("reputationPolicyVersion", match.reputationPolicyVersion());
        snapshot.put("computedAt", match.computedAt().toString());
        snapshot.put("dimensions", match.dimensions());
        snapshot.put("reasons", match.reasons());
        return snapshot;
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("推荐评分快照序列化失败", error);
        }
    }

    public record RecommendationPage(
            String scoringVersion, Instant computedAt, int eligibleCount, List<RecommenderMatch> items) {}

    public record InviteOutcome(
            TaskRecommenderInvitation invitation, boolean created, RecommenderMatch match) {}
}
