package com.grassland.trust.adjudication;

import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.event.EventEnvelope;
import com.grassland.trust.event.OutboxRepository;
import com.grassland.trust.judge.Judge;
import com.grassland.trust.judge.JudgeRepository;
import com.grassland.trust.judge.JudgeVote;
import com.grassland.trust.judge.VoteChoice;
import com.grassland.trust.judge.VoteTally;
import com.grassland.trust.security.DisputeAudience;
import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import com.grassland.trust.workflow.AdjudicationInput;
import com.grassland.trust.workflow.DisputeAdjudicationWorkflow;
import com.grassland.trust.workflow.DisputeAdjudicationWorkflowImpl;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 审判 HTTP 入口（草场 Epic 6 Slice 6C Phase B / HLD §5.5、§9.3、§10.5）。
 *
 * <ul>
 *   <li>POST /api/trust/disputes/{id}/adjudicate — 启动审判（当事方 + org 自查；<b>同步</b>抽 panel-size 官 + open→voting +
 *       outbox {@code DisputeAssigned}；202 新启 / 200 幂等返回当前态）。<br>
 *       <b>Phase B 注</b>：本轮同步抽签分配面板；Phase C 将替换为 {@code DisputeAdjudicationWorkflow}
 *       （{@code WorkflowClient.start}，timer → tally → 重开/上诉/终审 loop）。投票窗口到期 tally/decide/reopen 随 Phase C。</li>
 *   <li>POST /api/trust/disputes/{id}/votes — 审判官投票（{@code requireJudge} + 面板成员自查 + 当前轮；
 *       幂等 UNIQUE，每官每轮一票不可改；201 新投 / 200 既有；返回累计 tally）。</li>
 *   <li>GET /api/trust/disputes/{id}/adjudication — 审判状态轮询（当事方 + org 自查 / marketplace 服务 /
 *       <b>本轮面板审判官</b>——快照已脱敏，审判官是其目标读者，否则「能投票却看不见案情」）：
 *       {status, round, panel{size,voted}, tallies{...,majority}, decision, appealState, finalDecision}。
 *       默认脱敏——不暴露审判官 account_id / 个票 rationale（D-10）。</li>
 * </ul>
 *
 * <p>身份靠 {@link TrustCallerResolver}；错误统一由 {@code TrustErrorHandler} 处理。
 */
@RestController
public class AdjudicationController {

    private final TrustCallerResolver callers;
    private final DisputeCaseRepository disputes;
    private final JudgeRepository judges;
    private final OutboxRepository outbox;
    private final AdjudicationProperties props;
    private final WorkflowClient workflowClient;
    /** 读争议的受众口径（当事方 / marketplace 服务 / 本轮面板审判官 / 客服），见 {@link DisputeAudience}。 */
    private final DisputeAudience audience;
    private final TransactionalOperator transactions;

    public AdjudicationController(TrustCallerResolver callers, DisputeCaseRepository disputes,
                                  JudgeRepository judges, OutboxRepository outbox, AdjudicationProperties props,
                                  WorkflowClient workflowClient, DisputeAudience audience,
                                  TransactionalOperator transactions) {
        this.audience = audience;
        this.callers = callers;
        this.disputes = disputes;
        this.judges = judges;
        this.outbox = outbox;
        this.props = props;
        this.workflowClient = workflowClient;
        this.transactions = transactions;
    }

    @PostMapping("/api/trust/disputes/{id}/adjudicate")
    public Mono<ResponseEntity<Map<String, Object>>> adjudicate(@PathVariable String id, ServerHttpRequest request) {
        return callers.requireMerchantOrRecommender(request)
                .filter(caller -> caller.organizationId() != null)
                .switchIfEmpty(fail(403, "无组织归属，无法启动审判"))
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .flatMap(d -> {
                            if (!d.organizationId().equals(caller.organizationId())) {
                                return fail(403, "无权操作该争议");
                            }
                            if ("final".equals(d.status())) {
                                return fail(409, "争议已终局");
                            }
                            if ("merchant_rejection".equals(d.kind())) {
                                return fail(409, "商家履约异议直送客服终审，不进入审判面板");
                            }
                            // 新启争议：先抽面板（fail-fast：无可用审判官 → 503，争议保持 open 可重试），
                            //   再 open→voting + 写面板 + 发事件；避免「先翻 voting 再抽签失败」的半提交。
                            // 已审判（voting/decided/appealed）：幂等——补齐缺失面板（自愈）后返回当前态。
                            // 而后启动 DisputeAdjudicationWorkflow（24h Timer→tally→重开/上诉/终审 lifecycle）。
                            boolean fresh = "open".equals(d.status());
                            String workflowId = "adjudicate-" + d.id();
                            Mono<DisputeCase> outcome = fresh
                                    ? startFreshAdjudication(d)
                                    : ensurePanelAndEvent(d, d.round()).thenReturn(d);
                            return outcome
                                    .flatMap(voting -> startWorkflow(voting, workflowId).thenReturn(voting))
                                    .flatMap(this::snapshot)
                                    .map(snap -> ResponseEntity.status(fresh ? HttpStatus.ACCEPTED : HttpStatus.OK)
                                            .body(adjudicateBody(snap, workflowId)));
                        }));
    }

    /** 启 DisputeAdjudicationWorkflow（双击去重：WorkflowExecutionAlreadyStarted → 复用 id）。阻塞调用包 boundedElastic。 */
    private Mono<String> startWorkflow(DisputeCase d, String workflowId) {
        AdjudicationInput input = new AdjudicationInput(
                d.id(),
                props.voteWindowSecondsEffective(),
                props.appealWindowSecondsEffective(),
                props.maxRounds(),
                Math.max(0, props.csAwaitHours()) * 3600L,
                Math.max(1, props.csPollSeconds()));
        return Mono.fromCallable(() -> {
            DisputeAdjudicationWorkflow stub = workflowClient.newWorkflowStub(
                    DisputeAdjudicationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue(DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                            .build());
            WorkflowClient.start(stub::run, input);
            return workflowId;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(WorkflowExecutionAlreadyStarted.class, already -> Mono.just(workflowId));
    }

    private Map<String, Object> adjudicateBody(Map<String, Object> snap, String workflowId) {
        Map<String, Object> data = new LinkedHashMap<>(snap);
        data.put("workflowId", workflowId);
        return Map.of("success", true, "data", data);
    }

    /** 新启：抽面板 → open→voting → 写面板 + 发 DisputeAssigned。抽签失败先于状态翻转 → 争议保持 open。 */
    private Mono<DisputeCase> startFreshAdjudication(DisputeCase d) {
        return drawPanelAccounts(d.organizationId(), props.panelSize())
                .flatMap(accountIds -> transactions.transactional(
                        disputes.startAdjudication(d.id(), 1)
                                .flatMap(voting -> judges.assignPanel(d.id(), 1, accountIds)
                                        .then(outbox.append(assignedEnvelope(voting, 1, accountIds.size())))
                                        .thenReturn(voting))));
    }

    /** 抽 panel-size 无冲突审判官账号；空池 → 503。 */
    private Mono<List<String>> drawPanelAccounts(String orgId, int size) {
        return judges.drawEligiblePool(props.judgeEligibilityTier(), orgId, size)
                .map(Judge::accountId).collectList()
                .flatMap(list -> list.isEmpty()
                        ? Mono.error(new TrustException(503, "无可用的合格审判官"))
                        : Mono.just(list));
    }

    /** 幂等保证该轮面板已分配：面板已存在 → no-op；否则抽签 + 写 + 发 DisputeAssigned（自愈重试）。 */
    private Mono<Void> ensurePanelAndEvent(DisputeCase d, int round) {
        return judges.countPanel(d.id(), round).flatMap(count -> count > 0
                ? Mono.<Void>empty()
                : drawPanelAccounts(d.organizationId(), props.panelSize())
                        .flatMap(accountIds -> transactions.transactional(
                                judges.assignPanel(d.id(), round, accountIds)
                                        .then(outbox.append(assignedEnvelope(d, round, accountIds.size()))))));
    }

    @PostMapping("/api/trust/disputes/{id}/votes")
    public Mono<ResponseEntity<Map<String, Object>>> castVote(@PathVariable String id,
                                                              @RequestBody CastVoteRequest body, ServerHttpRequest request) {
        VoteChoice choice = VoteChoice.fromDb(body.vote());  // 非法 → IllegalArgumentException → 400
        return callers.requireJudge(request)
                // 门禁第二道：须已入 judge 池且未退池（审判官 = 推荐官 + 入池，见 requireJudge 注释）。
                // 放在此处而非 resolver：避免 TrustCallerResolver 依赖 JudgeRepository。
                .filterWhen(caller -> judges.findByAccountId(caller.accountId())
                        .map(Judge::active)
                        .defaultIfEmpty(false))
                .switchIfEmpty(fail(403, "需要先加入审判官池"))
                .flatMap(judge -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .flatMap(d -> {
                            if (!"voting".equals(d.status())) {
                                return fail(409, "该争议当前不在投票阶段");
                            }
                            int round = d.round();
                            return judges.isPanelMember(id, round, judge.accountId())
                                    .filter(Boolean::booleanValue)
                                    .switchIfEmpty(fail(403, "不在本轮审判面板"))
                                    .then(judges.recordVote(id, round, judge.accountId(), choice.dbValue(), body.rationale())
                                            .<VoteResult>map(v -> new VoteResult(v, true))
                                            .switchIfEmpty(judges.findVote(id, round, judge.accountId())
                                                    .<VoteResult>map(v -> new VoteResult(v, false))))
                                    .flatMap(result -> judges.tallyVotes(id, round)
                                            .map(tally -> ResponseEntity.status(result.inserted() ? HttpStatus.CREATED : HttpStatus.OK)
                                                    .body(Map.of("success", true, "data", voteBody(result.vote(), round, tally)))));
                        }));
    }

    @GetMapping("/api/trust/disputes/{id}/adjudication")
    public Mono<ResponseEntity<Map<String, Object>>> getAdjudication(@PathVariable String id, ServerHttpRequest request) {
        return callers.resolvePartyOrService(request, TrustCallerResolver.MARKETPLACE_SERVICE)
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        // 受众口径统一在 DisputeAudience（当事方 / marketplace 服务 / 本轮面板审判官 / 客服）。
                        // 不要在此就地展开——「读争议是谁的权限」只应有一个答案，见该类 javadoc 记的四次同类缺陷。
                        .filterWhen(d -> audience.canRead(caller, d))
                        .switchIfEmpty(fail(403, "无权查询该争议"))
                        .flatMap(this::snapshot)
                        .map(snap -> ResponseEntity.ok(Map.of("success", true, "data", snap))));
    }

    @PostMapping("/api/trust/disputes/{id}/appeal")
    public Mono<ResponseEntity<Map<String, Object>>> appeal(@PathVariable String id,
                                                            @RequestBody(required = false) AppealRequest body,
                                                            ServerHttpRequest request) {
        return callers.requireMerchantOrRecommender(request)
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .flatMap(d -> {
                            if (!d.organizationId().equals(caller.organizationId())) {
                                return fail(403, "无权操作该争议");
                            }
                            // 仅 decided 态可上诉（= 在上诉窗口内；workflow Timer 控制 decided→final，过期则不可上诉）。
                            if (!"decided".equals(d.status())) {
                                return fail(409, "该争议当前不可上诉");
                            }
                            return transactions.transactional(
                                    disputes.fileAppeal(id, caller.accountId())  // 幂等：dispute_id PK
                                            .filter(Boolean::booleanValue)
                                            .switchIfEmpty(fail(409, "该争议已上诉"))
                                            .then(disputes.markAppealed(id))             // decided→appealed
                                            .switchIfEmpty(fail(409, "上诉失败：状态已变"))
                                            .flatMap(appealed -> outbox.append(disputeEnvelope("DisputeAppealed", appealed))
                                                    .thenReturn(appealed)))
                                    .flatMap(this::snapshot)
                                    .map(snap -> ResponseEntity.ok(Map.of("success", true, "data", snap)));
                        }));
    }

    @PostMapping("/api/trust/disputes/{id}/final-decision")
    public Mono<ResponseEntity<Map<String, Object>>> finalDecision(@PathVariable String id,
                                                                   @RequestBody FinalDecisionRequest body,
                                                                   ServerHttpRequest request) {
        return callers.requireCustomerService(request)
                // MFA 近期性：断言 reauthenticatedAt 须在窗口内（HLD §11.2 客服覆盖判决须重新认证）。
                .filter(cs -> cs.reauthenticatedAt() != null
                        && Duration.between(cs.reauthenticatedAt(), Instant.now()).toMinutes() <= CS_MFA_WINDOW_MINUTES)
                .switchIfEmpty(fail(403, "需要客服近期重新认证（MFA）"))
                .flatMap(cs -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        // 客服终审范围：已上诉（panel 判决+上诉）或升级（超轮无判决，appeal_state=escalated）；
                        // 或 merchant_rejection（D-03 §2 商家拒绝核实通过履约，open 态直送客服，不走面板）。
                        .filter(d -> "appealed".equals(d.status())
                                || ("voting".equals(d.status()) && "escalated".equals(d.appealState()))
                                || ("open".equals(d.status()) && "merchant_rejection".equals(d.kind())))
                        .switchIfEmpty(fail(409, "该争议不在客服终审范围"))
                        .flatMap(d -> transactions.transactional(
                                disputes.forceFinalize(id, body.decision(), cs.accountId())  // CS 覆盖终局
                                        .switchIfEmpty(fail(409, "争议已终局"))
                                        .flatMap(fin -> outbox.append(disputeEnvelope("DisputeFinalized", fin)).thenReturn(fin)))
                                .flatMap(this::snapshot)
                                .map(snap -> ResponseEntity.ok(Map.of("success", true, "data", snap)))));
    }

    /** 通用争议事件信封（确定性 type-3 eventId：eventType:disputeId:round）。
     *  {@code openedByAccountId}/{@code openedByRole} 供 identity 通知中心解析收件人（Slice 12 Stage 3）；
     *  争议对方账号不在 DisputeCase 内（仅 engagementRef 引用），故本期只携带开启人 + 组织。 */
    private EventEnvelope disputeEnvelope(String eventType, DisputeCase d) {
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + d.id() + ":" + d.round()).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disputeId", d.id());
        payload.put("engagementRef", d.engagementRef());
        payload.put("organizationId", d.organizationId());
        payload.put("openedByAccountId", d.openedByAccountId());
        payload.put("openedByRole", d.openedByRole());
        payload.put("round", d.round());
        payload.put("status", d.status());
        if (d.finalDecision() != null) {
            payload.put("finalDecision", d.finalDecision());
        }
        return new EventEnvelope(eventId, eventType, "DisputeCase",
                d.id(), d.version(), Instant.now(), null, payload);
    }

    // ---------- helpers ----------

    /** 客服终审 MFA 近期性窗口（HLD §11.2：覆盖判决须近期重新认证）。 */
    private static final int CS_MFA_WINDOW_MINUTES = 5;

    private EventEnvelope assignedEnvelope(DisputeCase d, int round, int panelSize) {
        String eventId = UUID.nameUUIDFromBytes(
                ("DisputeAssigned:" + d.id() + ":" + round).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disputeId", d.id());
        payload.put("engagementRef", d.engagementRef());
        payload.put("organizationId", d.organizationId());
        payload.put("openedByAccountId", d.openedByAccountId());
        payload.put("openedByRole", d.openedByRole());
        payload.put("round", round);
        payload.put("panelSize", panelSize);
        return new EventEnvelope(eventId, "DisputeAssigned", "DisputeCase",
                d.id(), d.version(), Instant.now(), null, payload);
    }

    /** 审判状态快照（脱敏：不含审判官 account_id / 个票 rationale）。 */
    private Mono<Map<String, Object>> snapshot(DisputeCase d) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("id", d.id());
        base.put("status", d.status());
        base.put("round", d.round());
        base.put("decision", d.decision());
        base.put("appealState", d.appealState());
        base.put("finalDecision", d.finalDecision());
        base.put("decidedAt", d.decidedAt() == null ? null : d.decidedAt().toString());
        base.put("window", windowInfo(d));
        if (d.round() <= 0) {
            base.put("panel", Map.of("size", 0, "voted", 0));
            base.put("tallies", emptyTally());
            return Mono.just(base);
        }
        return judges.tallyVotes(d.id(), d.round()).map(tally -> {
            base.put("panel", Map.of("size", tally.panelSize(), "voted", tally.cast()));
            base.put("tallies", tallyMap(tally));
            return base;
        });
    }

    /**
     * 当前阶段的时间窗信息（可观测性）——UI 据此显示「还剩多久」，
     * 否则用户看到 {@code voting} 不知要等 1 分钟还是 24 小时。
     *
     * <p>窗口起点用 {@code updatedAt}（每次状态迁移刷新，即当前阶段的进入时刻）。
     * {@code remainingSeconds} 为估算值：真正的到期由 Temporal Timer 驱动，
     * 二者可能有秒级偏差（workflow 重放/重试），故仅作展示不作判定依据。
     */
    private Map<String, Object> windowInfo(DisputeCase d) {
        Map<String, Object> w = new LinkedHashMap<>();
        String phase;
        long durationSeconds;
        switch (d.status() == null ? "" : d.status()) {
            case "voting" -> {
                phase = "vote";
                durationSeconds = props.voteWindowSecondsEffective();
            }
            case "decided" -> {
                phase = "appeal";
                durationSeconds = props.appealWindowSecondsEffective();
            }
            default -> {
                // open（未开庭）/ appealed（等客服，无固定窗口）/ final（已结束）
                phase = "none";
                durationSeconds = 0;
            }
        }
        w.put("phase", phase);
        w.put("durationSeconds", durationSeconds);
        if (durationSeconds > 0 && d.updatedAt() != null) {
            Instant deadline = d.updatedAt().plusSeconds(durationSeconds);
            w.put("startedAt", d.updatedAt().toString());
            w.put("deadline", deadline.toString());
            w.put("remainingSeconds", Math.max(0, Duration.between(Instant.now(), deadline).toSeconds()));
        } else {
            w.put("startedAt", null);
            w.put("deadline", null);
            w.put("remainingSeconds", null);
        }
        return w;
    }

    private Map<String, Object> voteBody(JudgeVote v, int round, VoteTally tally) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disputeId", v.disputeId());
        m.put("round", round);
        m.put("vote", v.vote());
        m.put("rationale", v.rationale());
        m.put("votedAt", v.votedAt() == null ? null : v.votedAt().toString());
        m.put("tallies", tallyMap(tally));
        return m;
    }

    private Map<String, Object> tallyMap(VoteTally t) {
        // LinkedHashMap：majority 可空（平票/不足时 null），Map.of 不允许 null 值。
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("forMerchant", t.forMerchant());
        m.put("forRecommender", t.forRecommender());
        m.put("abstain", t.abstain());
        m.put("panelSize", t.panelSize());
        m.put("majority", t.hasMajorityForMerchant() ? "for_merchant"
                : t.hasMajorityForRecommender() ? "for_recommender" : null);
        return m;
    }

    private Map<String, Object> emptyTally() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("forMerchant", 0);
        m.put("forRecommender", 0);
        m.put("abstain", 0);
        m.put("panelSize", 0);
        m.put("majority", null);
        return m;
    }

    private record VoteResult(JudgeVote vote, boolean inserted) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }
}
