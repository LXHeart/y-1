package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.matching.TaskRecommenderInvitationRepository;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 报名生命周期领域服务：推荐官报名/撤销、商家（批量）拒绝、任务报名列表（owner 按声誉权重排序 /
 * 非 owner 仅本人）与任务进度统计。任务加载与资源级自查由控制器守卫完成后传入。
 */
@Component
public class ApplicationLifecycleService {

    private final TaskApplicationRepository apps;
    private final TaskMetricsRepository metrics;
    private final TaskAcceptanceCounterRepository acceptanceCounters;
    private final TaskRecommenderInvitationRepository recommenderInvitations;
    private final ReputationService reputationService;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public ApplicationLifecycleService(TaskApplicationRepository apps,
                                       TaskMetricsRepository metrics,
                                       TaskAcceptanceCounterRepository acceptanceCounters,
                                       TaskRecommenderInvitationRepository recommenderInvitations,
                                       ReputationService reputationService,
                                       OutboxRepository outbox,
                                       TransactionalOperator transactions) {
        this.apps = apps;
        this.metrics = metrics;
        this.acceptanceCounters = acceptanceCounters;
        this.recommenderInvitations = recommenderInvitations;
        this.reputationService = reputationService;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /**
     * 推荐官报名（PRD「报名」+ GL-P1-TASK-001 报名截止）：任务须 published 且未截止、
     * 等级达标、名额未满、一人一报；创建（冻结资金快照）+ 邀请标记 + outbox {@code ApplicationSubmitted}
     * 同一事务。名额满 fail-fast 409。
     */
    public Mono<TaskApplication> apply(Task task, Caller rec, String note) {
        if (!"published".equals(task.status())) {
            // #26 D9：closed/cancelled 文案单独拆分（原统一「任务当前不可报名」），其余状态维持原文案
            String message = "closed".equals(task.status()) ? "任务已关闭，无法报名"
                    : "cancelled".equals(task.status()) ? "任务已取消，无法报名"
                    : "任务当前不可报名";
            return fail(409, message);
        }
        // GL-P1-TASK-001 Stage 1：报名截止（PRD「指定时间」）。已截止 → 不接受新报名；
        // 既有 pending/accepted/履约不受影响（D-03 未决，不动 accept/confirm/结算）。
        if (task.applicationDeadline() != null
                && task.applicationDeadline().isBefore(Instant.now())) {
            return fail(409, "报名已截止");
        }
        return reputationService.snapshot(rec.accountId())
                .flatMap(snapshot -> snapshot.evaluation().effectiveLevel().number()
                                < task.minRecommenderLevel()
                        ? Mono.<TaskApplication>error(new MarketplaceException(
                                403, "当前等级不满足任务报名要求"))
                        : slotsFull(task).flatMap(full -> full
                                ? Mono.<TaskApplication>error(new MarketplaceException(409, "名额已满"))
                                : apps.findByTaskAndRecommender(task.id(), rec.accountId())
                                        .<TaskApplication>flatMap(existing -> Mono.error(
                                                new MarketplaceException(409, "已报名该任务")))
                                        .switchIfEmpty(transactions.transactional(
                                                apps.create(task.id(), rec.accountId(), note,
                                                        TaskFunds.bountyOrZero(task), TaskFunds.freebieDepositOrZero(task))
                                                        .switchIfEmpty(Mono.error(new MarketplaceException(409, "已报名该任务")))
                                                        .flatMap(created -> recommenderInvitations
                                                                .markApplied(task.id(), rec.accountId())
                                                                .then(outbox.append(ApplicationEvents.envelope(
                                                                        "ApplicationSubmitted", created,
                                                                        task.ownerAccountId())))
                                                                .thenReturn(created))))));
    }

    /** 商家拒绝 pending 报名：状态迁移 + outbox {@code ApplicationRejected}。 */
    public Mono<TaskApplication> reject(Task task, TaskApplication app, Caller merchant) {
        return apps.reject(app.id(), task.id(), merchant.accountId())
                .switchIfEmpty(fail(409, "该报名已处理"))
                .flatMap(rejected -> outbox
                        .append(ApplicationEvents.envelope("ApplicationRejected", rejected, task.ownerAccountId()))
                        .thenReturn(rejected));
    }

    /** 任务书 #27：batch-reject 单项。仅 pending 可处理；逐项独立，允许部分成功。 */
    public Mono<BatchItemResult> batchRejectItem(Task task, Caller merchant, String appId) {
        return apps.findById(appId)
                .filter(app -> app.taskId().equals(task.id()))
                .filter(app -> ApplicationStatus.PENDING.dbValue().equals(app.status()))
                .flatMap(app -> apps.reject(appId, task.id(), merchant.accountId())
                        .flatMap(rejected -> outbox.append(
                                        ApplicationEvents.envelope("ApplicationRejected", rejected, task.ownerAccountId()))
                                .thenReturn(BatchItemResult.ofOutcome(appId, "rejected")))
                        .switchIfEmpty(Mono.just(BatchItemResult.failed(appId, "该报名已处理"))))
                .switchIfEmpty(Mono.defer(() -> apps.findById(appId)
                        .filter(app -> app.taskId().equals(task.id()))
                        .flatMap(app -> Mono.just(
                                BatchItemResult.failed(appId, "该报名已处理")))
                        .switchIfEmpty(Mono.just(
                                BatchItemResult.failed(appId, "报名不存在")))));
    }

    /** 推荐官撤销本人 pending 报名：withdraw WHERE 烧入 recommender（HLD 7.4）+ outbox 同一事务。 */
    public Mono<TaskApplication> withdraw(TaskApplication app, Task task, Caller rec) {
        return transactions.transactional(
                apps.withdraw(app.id(), task.id(), rec.accountId())
                        .switchIfEmpty(fail(409, "该报名已处理"))
                        .flatMap(withdrawn -> outbox
                                .append(ApplicationEvents.envelope("ApplicationWithdrawn", withdrawn, task.ownerAccountId()))
                                .thenReturn(withdrawn)));
    }

    /** 非 owner 视图：仅本人报名行（不相干的人拿空列表，不泄露信息）。 */
    public Mono<List<Map<String, Object>>> ownApplications(String taskId, Caller caller, String status,
                                                           Instant createdAfter, Instant createdBefore, int limit) {
        return apps.findByTaskId(taskId, status, createdAfter, createdBefore, limit)
                .filter(a -> caller.accountId().equals(a.recommenderAccountId()))
                .map(ApplicationBodies::toBody)
                .collectList();
    }

    /** owner 视图：全部报名按声誉权重降序（同权重按创建时间/ id 稳定排序），行附声誉快照三字段。 */
    public Mono<List<Map<String, Object>>> rankedApplications(String taskId, String status,
                                                              Instant createdAfter, Instant createdBefore, int limit) {
        return apps.findByTaskId(taskId, status, createdAfter, createdBefore, limit)
                .collectList()
                .flatMap(applications -> reputationService.snapshots(applications.stream()
                                .map(TaskApplication::recommenderAccountId).toList())
                        .map(snapshots -> applications.stream()
                                .map(app -> new RankedApplication(app,
                                        snapshots.get(app.recommenderAccountId())))
                                .sorted((left, right) -> {
                                    int byWeight = Integer.compare(
                                            right.snapshot().evaluation().taskPriorityWeight(),
                                            left.snapshot().evaluation().taskPriorityWeight());
                                    if (byWeight != 0) return byWeight;
                                    int byCreatedAt = left.application().createdAt()
                                            .compareTo(right.application().createdAt());
                                    if (byCreatedAt != 0) return byCreatedAt;
                                    return left.application().id()
                                            .compareTo(right.application().id());
                                })
                                .map(ranked -> ApplicationBodies.ranked(ranked.application(), ranked.snapshot()))
                                .toList()));
    }

    /** 任务进度统计（list 的 stats / summary 端点共用；与过滤行分离，分页不扭曲总量）。 */
    public Mono<Map<String, Object>> taskProgress(String taskId, Task task) {
        return metrics.findProgressByTaskIds(List.of(taskId)).next()
                .defaultIfEmpty(TaskProgress.empty(taskId))
                .map(facts -> {
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("total", facts.totalApplications());
                    stats.put("pending", facts.pendingApplications());
                    stats.put("reserving", facts.reservingApplications());
                    stats.put("accepted", facts.acceptedApplications());
                    stats.put("rejected", facts.rejectedApplications());
                    stats.put("withdrawn", facts.withdrawnApplications());
                    stats.put("refunded", facts.refundedApplications());
                    stats.put("occupiedSlots", facts.occupiedSlots());
                    stats.put("maxSlots", task.maxSlots());
                    stats.put("remainingSlots", task.maxSlots() == null
                            ? null : Math.max(0, task.maxSlots() - facts.occupiedSlots()));
                    stats.put("submittedDeliverables", facts.submittedDeliverables());
                    stats.put("confirmedDeliverables", facts.confirmedDeliverables());
                    stats.put("settledEngagements", facts.settledEngagements());
                    stats.put("reservedBountyCents", facts.reservedBountyCents());
                    stats.put("settledBountyCents", facts.settledBountyCents());
                    return stats;
                });
    }

    /** 名额是否已满：reserving 与 accepted 都通过事务 counter 占位。 */
    private Mono<Boolean> slotsFull(Task task) {
        Integer max = task.maxSlots();
        if (max == null) {
            return Mono.just(false);
        }
        return acceptanceCounters.occupied(task.id()).map(occupied -> occupied >= max);
    }

    private record RankedApplication(TaskApplication application, ReputationSnapshot snapshot) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
