package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.workflow.saga.AcceptanceWorkflowStarter;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 接受内核（Slice 4F + ADR-D12 + 任务书 #27 D6）：单条 accept、batch-accept、自动通过 dispatcher
 * 三入口共享的领域服务。资源级自查（caller==task.owner）由控制器守卫完成后传入。
 *
 * <ul>
 *   <li>接受命令、原子名额占用、状态迁移和 outbox 在同一事务内完成；资金型任务由 durable dispatcher
 *       启动预留 Saga，支持显式 {@code Idempotency-Key} 重放和冲突检测（并发约束冲突 → 回读既有命令重放）。</li>
 *   <li>预留结局轮询（reservation）：accepted/reserving/compensated+reason，附 {@code taskClosed}（#26 D12）。</li>
 * </ul>
 */
@Component
public class ApplicationAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAcceptanceService.class);

    private final TaskRepository tasks;
    private final TaskApplicationRepository apps;
    private final TaskAcceptanceCounterRepository acceptanceCounters;
    private final AcceptanceCommandRepository acceptanceCommands;
    private final AcceptanceWorkflowStarter acceptanceWorkflows;
    private final OutboxRepository outbox;
    private final ReputationService reputationService;
    private final TaskFullAutoCloser taskFullAutoCloser;
    private final TransactionalOperator transactions;

    public ApplicationAcceptanceService(TaskRepository tasks,
                                        TaskApplicationRepository apps,
                                        TaskAcceptanceCounterRepository acceptanceCounters,
                                        AcceptanceCommandRepository acceptanceCommands,
                                        AcceptanceWorkflowStarter acceptanceWorkflows,
                                        OutboxRepository outbox,
                                        ReputationService reputationService,
                                        TaskFullAutoCloser taskFullAutoCloser,
                                        TransactionalOperator transactions) {
        this.tasks = tasks;
        this.apps = apps;
        this.acceptanceCounters = acceptanceCounters;
        this.acceptanceCommands = acceptanceCommands;
        this.acceptanceWorkflows = acceptanceWorkflows;
        this.outbox = outbox;
        this.reputationService = reputationService;
        this.taskFullAutoCloser = taskFullAutoCloser;
        this.transactions = transactions;
    }

    /** 单条 accept 入口：幂等键命中 → 重放既有结局；否则进入接受内核。 */
    public Mono<AcceptanceOutcome> accept(Task task, String applicationId, Caller merchant, String idempotencyKey) {
        return acceptanceCommands.findByActorAndKey(merchant.accountId(), idempotencyKey)
                .flatMap(existing -> replayAcceptance(existing, task.id(), applicationId)
                        .map(response -> new AcceptanceOutcome(response, false)))
                .switchIfEmpty(claimAcceptance(task, applicationId, merchant, idempotencyKey));
    }

    /** 任务书 #27：batch-accept 单项（itemKey = baseKey + ":" + appId）。单项失败不中断后续项（D2）。 */
    public Mono<BatchItemResult> batchItem(Task task, Caller merchant, String itemKey, String appId) {
        return acceptanceCommands.findByActorAndKey(merchant.accountId(), itemKey)
                .flatMap(existing -> replayBatchAccept(existing, task.id(), appId))
                .switchIfEmpty(claimAcceptance(task, appId, merchant, itemKey)
                        .map(claim -> {
                            String outcome = TaskFunds.isMonetary(task) ? "reserving" : "accepted";
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = claim.response().getBody() != null
                                    ? (Map<String, Object>) claim.response().getBody().get("data") : null;
                            String cmdId = data != null ? (String) data.get("commandId") : null;
                            String wfId = data != null ? (String) data.get("workflowId") : null;
                            // #26 D12：batch item 透传本事务关闭事实（资金型 reserving 恒 false，
                            // 最终以 reservation 轮询为准）
                            return new BatchItemResult(appId, outcome, cmdId, wfId, null,
                                    claim.taskClosed());
                        })
                        .onErrorResume(MarketplaceException.class,
                                e -> Mono.just(BatchItemResult.failed(appId, e.getMessage())))
                        .onErrorResume(e -> Mono.just(
                                BatchItemResult.failed(appId, "处理失败"))));
    }

    /**
     * 单条/批量/自动接受共享的接受内核入口。#26 D12：返回 {@link AcceptanceOutcome} 携带关闭事实——
     * 单条 accept 的 HTTP 响应体不带 {@code taskClosed}（资金型 202 时关闭尚未发生，加了也是 false，徒增误导），
     * batch-accept 的逐项结果透传该字段。
     */
    public Mono<AcceptanceOutcome> claimAcceptance(
            Task task, String applicationId, Caller merchant, String idempotencyKey) {
        return apps.findById(applicationId)
                .switchIfEmpty(fail(404, "报名不存在"))
                .filter(app -> app.taskId().equals(task.id()))
                .switchIfEmpty(fail(404, "报名不存在"))
                .filter(app -> ApplicationStatus.PENDING.dbValue().equals(app.status()))
                .switchIfEmpty(fail(409, "该报名已处理"))
                .flatMap(app -> reputationService.snapshot(app.recommenderAccountId())
                        .map(ApplicationAcceptanceService::entitlementSnapshot)
                        .flatMap(entitlement -> claimAcceptance(task, app, merchant, idempotencyKey, entitlement)))
                .onErrorResume(this::isAcceptanceConstraintConflict,
                        failure -> acceptanceCommands.findByActorAndKey(merchant.accountId(), idempotencyKey)
                                .flatMap(existing -> replayAcceptance(existing, task.id(), applicationId)
                                        // 与 replayBatchAccept 一致：重放时接受事务早已落定，关闭事实以任务现状为准
                                        .flatMap(response -> taskClosed(task.id())
                                                .map(closed -> new AcceptanceOutcome(response, closed))))
                                .switchIfEmpty(fail(409, "该报名正在被其他请求处理")));
    }

    private Mono<AcceptanceOutcome> claimAcceptance(
            Task task, TaskApplication app, Caller merchant, String idempotencyKey,
            ReputationEntitlementSnapshot entitlement) {
        boolean monetary = TaskFunds.isMonetary(task);
        String commandId = UUID.randomUUID().toString();
        String workflowId = monetary ? "accept-" + app.id() + "-" + commandId : null;
        AcceptanceCommand proposed = new AcceptanceCommand(
                commandId, merchant.accountId(), idempotencyKey, task.id(), app.id(), workflowId,
                task.ownerAccountId(), task.organizationId(), TaskFunds.commandAmountCents(task),
                monetary ? "pending_dispatch" : "accepted", null, null, null, null, null);

        Mono<AcceptanceClaim> write = acceptanceCommands.create(proposed)
                .flatMap(command -> acceptanceCounters.claim(task.id())
                        .switchIfEmpty(fail(409, "名额已满"))
                        .then(monetary
                                // claim 时刷新 provisional 金额快照（claim-time 权威；apply 时写入的值可能已被修订覆盖）
                                ? apps.beginAcceptance(app.id(), task.id(), merchant.accountId(), entitlement,
                                        TaskFunds.bountyOrZero(task), TaskFunds.freebieDepositOrZero(task))
                                : apps.accept(app.id(), task.id(), merchant.accountId(),
                                        TaskFunds.bountyOrZero(task), entitlement))
                        .switchIfEmpty(fail(409, "该报名已处理"))
                        .flatMap(accepted -> outbox.append(ApplicationEvents.envelope(
                                        monetary ? "ApplicationAcceptanceStarted" : "ApplicationAccepted",
                                        accepted, task.ownerAccountId()))
                                .thenReturn(accepted))
                        // #26 满员自动关闭（D2/D4）：仅非资金型在接受落定后同事务判定关闭；
                        // 资金型 claim→reserving 阶段不判定（预留失败会释放名额，误关不可收口），
                        // 由 saga activateEngagement（reserving→accepted 落定）再判。
                        // D12：关闭事实记入 AcceptanceClaim 供 batch item 透传（单条响应体不带该字段）。
                        .flatMap(accepted -> monetary
                                ? Mono.just(new AcceptanceClaim(command, accepted, false))
                                : taskFullAutoCloser.closeIfFull(task.id())
                                        .map(closed -> true)
                                        .defaultIfEmpty(false)
                                        .map(closed -> new AcceptanceClaim(command, accepted, closed))));

        return transactions.transactional(write)
                .flatMap(claim -> monetary
                        ? dispatchAcceptance(claim.command())
                                .map(response -> new AcceptanceOutcome(response, false))
                        : Mono.just(new AcceptanceOutcome(ResponseEntity.ok(Map.of(
                                "success", true, "data", ApplicationBodies.toBody(claim.application()))), claim.taskClosed())));
    }

    private Mono<ResponseEntity<Map<String, Object>>> dispatchAcceptance(AcceptanceCommand command) {
        ResponseEntity<Map<String, Object>> accepted = ApplicationBodies.acceptanceResponse(command, "reserving", HttpStatus.ACCEPTED);
        return acceptanceWorkflows.start(command)
                .flatMap(ignored -> acceptanceCommands.markStarted(command.id()).thenReturn(accepted))
                .onErrorResume(failure -> {
                    log.warn("acceptance workflow initial dispatch deferred command={} application={}",
                            command.id(), command.applicationId(), failure);
                    return Mono.just(accepted);
                });
    }

    private Mono<ResponseEntity<Map<String, Object>>> replayAcceptance(
            AcceptanceCommand command, String taskId, String applicationId) {
        if (!command.taskId().equals(taskId) || !command.applicationId().equals(applicationId)) {
            return fail(409, "Idempotency-Key 已用于其他接受请求");
        }
        return switch (command.status()) {
            case "pending_dispatch", "started" -> Mono.just(
                    ApplicationBodies.acceptanceResponse(command, "reserving", HttpStatus.ACCEPTED));
            case "accepted" -> apps.findById(applicationId)
                    .map(app -> ResponseEntity.ok(Map.of("success", true, "data", ApplicationBodies.toBody(app))))
                    .switchIfEmpty(fail(409, "接受请求结果不可用"));
            case "compensated", "aborted" -> Mono.just(
                    ApplicationBodies.acceptanceResponse(command, command.status(), HttpStatus.OK));
            default -> fail(409, "接受请求状态无效");
        };
    }

    private Mono<BatchItemResult> replayBatchAccept(AcceptanceCommand command, String taskId, String appId) {
        if (!command.taskId().equals(taskId) || !command.applicationId().equals(appId)) {
            return Mono.just(BatchItemResult.failed(appId, "Idempotency-Key 已用于其他接受请求"));
        }
        return switch (command.status()) {
            case "pending_dispatch", "started" ->
                    Mono.just(new BatchItemResult(appId, "reserving", command.id(), command.workflowId(), null, false));
            // #26 D12：重放时接受事务早已落定，关闭事实以任务现状为准
            case "accepted" -> taskClosed(taskId).map(closed ->
                    new BatchItemResult(appId, "accepted", null, null, null, closed));
            case "compensated", "aborted" ->
                    Mono.just(new BatchItemResult(appId, command.status(), command.id(), command.workflowId(),
                            command.failureReason(), false));
            default -> Mono.just(BatchItemResult.failed(appId, "接受请求状态无效"));
        };
    }

    private boolean isAcceptanceConstraintConflict(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || failure instanceof R2dbcDataIntegrityViolationException;
    }

    /**
     * 任务书 #27：dispatcher 调用的共享 accept 内核（D6）。无 Caller（系统操作），reviewed_by 置 null。
     * 返回 outcome 字符串：accepted / reserving / slots_full / compensated。
     */
    public Mono<String> acceptForDispatcher(Task task, TaskApplication app, ReputationSnapshot snapshot) {
        ReputationEntitlementSnapshot entitlement = entitlementSnapshot(snapshot);
        String idempotencyKey = "auto-accept:" + app.id();
        Caller systemCaller = new Caller(null, null, null, null, null, "system", null, null);
        // 幂等检查：dispatcher 重启重跑时复用既有结局
        return acceptanceCommands.findByActorAndKey(null, idempotencyKey)
                .flatMap(existing -> switch (existing.status()) {
                    case "pending_dispatch", "started" -> Mono.just(TaskFunds.isMonetary(task) ? "reserving" : "accepted");
                    case "accepted" -> Mono.just("accepted");
                    case "compensated" -> Mono.just("compensated");
                    case "aborted" -> Mono.just("aborted");
                    default -> Mono.just("unknown");
                })
                .switchIfEmpty(claimAcceptance(task, app, systemCaller, idempotencyKey, entitlement)
                        .map(outcome -> TaskFunds.isMonetary(task) ? "reserving" : "accepted"))
                .onErrorResume(MarketplaceException.class, e -> {
                    if (e.status() == 409 && "名额已满".equals(e.getMessage())) {
                        return Mono.just("slots_full");
                    }
                    return Mono.error(e);
                });
    }

    /**
     * 映射 application DB 状态为预留结局：accepted / reserving；pending（含补偿回退）查最近失败 reason。
     * #26 D12：响应附 {@code taskClosed}（任务当前是否已 closed）——单条/资金型接受最终都收敛到本端点轮询，
     * 前端在 accepted 且 taskClosed=true 时追加「任务名额已满，已自动关闭」文案，一处覆盖全路径。
     */
    public Mono<ResponseEntity<Map<String, Object>>> reservationOutcome(TaskApplication app) {
        String status = app.status();
        if (ApplicationStatus.ACCEPTED.dbValue().equals(status)) {
            return taskClosed(app.taskId()).map(closed -> ApplicationBodies.ok(ApplicationBodies.reservationBody("accepted", null, closed)));
        }
        if (ApplicationStatus.RESERVING.dbValue().equals(status)) {
            return taskClosed(app.taskId()).map(closed -> ApplicationBodies.ok(ApplicationBodies.reservationBody("reserving", null, closed)));
        }
        // pending（含补偿回退）或其它：查最近 ApplicationReservationFailed 事件的 reason
        return taskClosed(app.taskId()).flatMap(closed -> outbox.latestReservationFailureReason(app.id())
                .map(reason -> ApplicationBodies.ok(ApplicationBodies.reservationBody("compensated", reason, closed)))
                .defaultIfEmpty(ApplicationBodies.ok(ApplicationBodies.reservationBody(status, null, closed))));  // 无失败记录 → 原状态（pending 等）
    }

    /** 任务是否已关闭（#26 D12；任务行不可得防御性回 false）。 */
    private Mono<Boolean> taskClosed(String taskId) {
        return tasks.findById(taskId)
                .map(task -> TaskStatus.CLOSED.dbValue().equals(task.status()))
                .defaultIfEmpty(false);
    }

    private static ReputationEntitlementSnapshot entitlementSnapshot(ReputationSnapshot snapshot) {
        var evaluation = snapshot.evaluation();
        return new ReputationEntitlementSnapshot(
                evaluation.effectiveLevel().number(), snapshot.policy().version(),
                evaluation.settlementDelayDays(), evaluation.commissionBonusBps(), evaluation.premiumSupport());
    }

    /**
     * 接受事务落定结果。#26 D12：{@code taskClosed} = 本事务是否触发满员自动关闭——
     * 仅非资金型接受（apps.accept 后 closeIfFull）在此可知；资金型 reserving 阶段恒 false，
     * 最终以 reservation 端点为准（saga activateEngagement 内关闭）。
     */
    private record AcceptanceClaim(AcceptanceCommand command, TaskApplication application, boolean taskClosed) {}

    /**
     * claimAcceptance 对上层（单条 accept / batch-accept / dispatcher）的返回：HTTP 响应 + 关闭事实。
     * 单条 accept 响应体不携带 {@code taskClosed}（D12），batch item 序列化时透传。
     */
    public record AcceptanceOutcome(ResponseEntity<Map<String, Object>> response, boolean taskClosed) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
