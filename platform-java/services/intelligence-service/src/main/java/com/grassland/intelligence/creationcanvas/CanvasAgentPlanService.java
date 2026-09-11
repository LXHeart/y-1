package com.grassland.intelligence.creationcanvas;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 画布 AI 计划服务（任务书 #100 C100-16 / API-13/14 / §6.6）。
 *
 * <p>幂等占位 → 服务端上下文 → FrozenText（executeIndependent，CREATION_ASSISTANT，
 * 90s 上限）→ 严格解析 → 持久化 ready/clarify/failed（runId 追踪）。空选择 clarify 是
 * 服务端固定引导（runId=null 不计费）；同键 preparing 返回 202；同键终态返回既有计划
 * （不第二次收费）；僵尸 preparing（>120s）读取时 CAS 标失败保留 runId。
 */
@Service
public class CanvasAgentPlanService {

    private static final Logger log = LoggerFactory.getLogger(CanvasAgentPlanService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);
    static final Duration ZOMBIE_PREPARING = Duration.ofSeconds(120);
    static final Duration PLAN_TTL = Duration.ofMinutes(30);

    private final CanvasAgentPlanRepository plans;
    private final CanvasAgentContextBuilder contexts;
    private final CreationCanvasRepository canvas;
    private final VideoCanvasWorkspaceRepository bindings;
    private final org.springframework.r2dbc.core.DatabaseClient db;
    private final FrozenTextExecutionService frozenText;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CanvasAgentPlanService(CanvasAgentPlanRepository plans, CanvasAgentContextBuilder contexts,
            CreationCanvasRepository canvas, VideoCanvasWorkspaceRepository bindings,
            org.springframework.r2dbc.core.DatabaseClient db, FrozenTextExecutionService frozenText) {
        this(plans, contexts, canvas, bindings, db, frozenText, Clock.systemUTC());
    }

    CanvasAgentPlanService(CanvasAgentPlanRepository plans, CanvasAgentContextBuilder contexts,
            CreationCanvasRepository canvas, VideoCanvasWorkspaceRepository bindings,
            org.springframework.r2dbc.core.DatabaseClient db, FrozenTextExecutionService frozenText,
            Clock clock) {
        this.plans = plans;
        this.contexts = contexts;
        this.canvas = canvas;
        this.bindings = bindings;
        this.db = db;
        this.frozenText = frozenText;
        this.clock = clock;
    }

    public record CreatePlanRequest(UUID operationId, UUID draftId, UUID storyboardId,
            List<String> selectedNodeIds, Long expectedEditVersion, Long expectedCanvasRevision,
            String instruction) {
    }

    public record PlanOutcome(CanvasAgentPlanRepository.AgentPlanRow plan, boolean preparing) {
    }

    public Mono<PlanOutcome> create(ServerWebExchange exchange, String accountId, CreatePlanRequest request) {
        if (request == null || request.operationId() == null || request.draftId() == null
                || request.storyboardId() == null || request.instruction() == null
                || request.instruction().isBlank()) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                    "operationId/draftId/storyboardId/instruction 必填"));
        }
        if (request.instruction().codePoints().count() > 4000) {
            return Mono.error(new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED",
                    "指令超过 4000 字符"));
        }
        if (request.expectedEditVersion() == null || request.expectedEditVersion() < 1
                || request.expectedCanvasRevision() == null || request.expectedCanvasRevision() < 1) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                    "expectedEditVersion/expectedCanvasRevision 必须为正安全整数"));
        }
        String requestHash = hashOf(accountId, request);
        // 同键复读：终态直接返回（不第二次调模型）；preparing 返回 202；异参 409
        return plans.findByAccountAndOperation(accountId, request.operationId())
                .flatMap(existing -> {
                    if (!existing.requestHash().equals(requestHash)) {
                        return Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT",
                                "操作键已用于不同请求"));
                    }
                    if ("preparing".equals(existing.status())) {
                        return Mono.just(new PlanOutcome(existing, true));
                    }
                    return Mono.just(new PlanOutcome(existing, false));
                })
                .switchIfEmpty(Mono.defer(() -> createFresh(exchange, accountId, request, requestHash)));
    }

    private Mono<PlanOutcome> createFresh(ServerWebExchange exchange, String accountId,
            CreatePlanRequest request, String requestHash) {
        // AI 入口前置（§6.6）：独立画布文档必须已创建；服务端绑定校验 draft/sb
        return bindings.findByStoryboard(request.storyboardId())
                .filter(binding -> binding.draftId().equals(request.draftId())
                        && binding.accountId().equals(accountId))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND",
                        "草稿与分镜无绑定关系")))
                .flatMap(binding -> canvas.findByDraftId(request.draftId())
                        .switchIfEmpty(Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT",
                                "请先保存当前画布（独立画布文档未创建）")))
                        .flatMap(canvasDoc -> db.sql("SELECT version FROM creation_draft "
                                        + "WHERE id=CAST(:id AS uuid) AND owner_account_id=:account "
                                        + "AND deleted_at IS NULL")
                                .bind("id", request.draftId().toString()).bind("account", accountId)
                                .map(row -> row.get(0, Integer.class)).one()
                                .switchIfEmpty(Mono.error(new IntelligenceException(404,
                                        "CANVAS_RESOURCE_NOT_FOUND", "草稿不存在")))
                                .flatMap(draftVersion -> shotsOf(request.storyboardId())
                                        .flatMap(shotRows -> {
                                            CanvasAgentContextBuilder.Context context = contexts.build(
                                                    accountId, request.draftId().toString(),
                                                    request.storyboardId().toString(),
                                                    request.selectedNodeIds(), canvasDoc.documentJson(),
                                                    shotRows);
                                            // 占位行先落库（幂等；崩溃不重复调模型）
                                            CanvasAgentPlanRepository.AgentPlanRow placeholder =
                                                    new CanvasAgentPlanRepository.AgentPlanRow(
                                                            UUID.randomUUID(), accountId,
                                                            request.operationId(), requestHash,
                                                            request.draftId(), request.storyboardId(),
                                                            draftVersion, request.expectedEditVersion(),
                                                            request.expectedCanvasRevision(), "preparing",
                                                            toJsonArray(request.selectedNodeIds()),
                                                            request.instruction(), null, null, null,
                                                            null, null, null, null, null,
                                                            clock.instant().plus(PLAN_TTL)
                                                                    .atZone(java.time.ZoneOffset.UTC)
                                                                    .toOffsetDateTime());
                                            return plans.insertPlaceholder(placeholder)
                                                    .flatMap(inserted -> inserted == 0
                                                            ? plans.findByAccountAndOperation(accountId,
                                                                    request.operationId())
                                                                    .map(row -> new PlanOutcome(row, true))
                                                            : runModel(exchange, placeholder, context));
                                        }))));
    }

    /** 空选择 clarify（固定引导、runId=null、不计费）；否则 FrozenText 严格解析。 */
    private Mono<PlanOutcome> runModel(ServerWebExchange exchange,
            CanvasAgentPlanRepository.AgentPlanRow placeholder, CanvasAgentContextBuilder.Context context) {
        if (context.clarify() != null) {
            return plans.complete(placeholder.id(), "clarify", "", context.clarify(), null, null, null,
                            clock.instant().plus(PLAN_TTL))
                    .then(Mono.defer(() -> plans.findById(placeholder.id())
                            .map(row -> new PlanOutcome(row, false))));
        }
        return frozenText.executeIndependent(exchange,
                        List.of(ChatMessage.system(CanvasAgentPrompts.systemPrompt()),
                                ChatMessage.user(CanvasAgentPrompts.userPrompt(context.contextJson(),
                                        placeholder.instruction()))),
                        CanvasAgentContextBuilder.OUTPUT_MAX_TOKENS, CreditFeature.CREATION_ASSISTANT,
                        MODEL_TIMEOUT, TextCompletionResult::content)
                .flatMap(traced -> {
                    String output = traced.value();
                    try {
                        String action = CanvasAgentPlan.parseAction(output);
                        return plans.complete(placeholder.id(), "ready",
                                        "已生成修改计划（" + action.length() + " 字节动作）", null, action,
                                        traced.runId(), null, clock.instant().plus(PLAN_TTL))
                                .then(Mono.defer(() -> plans.findById(placeholder.id())
                                        .map(row -> new PlanOutcome(row, false))));
                    } catch (IllegalArgumentException invalid) {
                        log.warn("canvas agent invalid plan runId={} cause={}", traced.runId(),
                                invalid.getMessage());
                        return plans.complete(placeholder.id(), "failed", "", null, null, traced.runId(),
                                        "CANVAS_AGENT_INVALID_PLAN", clock.instant().plus(PLAN_TTL))
                                .then(Mono.error(new IntelligenceException(502, "CANVAS_AGENT_INVALID_PLAN",
                                        "模型返回不合法计划：" + invalid.getMessage())));
                    }
                })
                .onErrorResume(error -> error instanceof IntelligenceException
                        ? Mono.error(error)
                        : failPreparing(placeholder.id(), "CANVAS_AGENT_TIMEOUT").then(Mono.error(
                                new IntelligenceException(504, "CANVAS_AGENT_TIMEOUT",
                                        "计划生成超时，保留运行追踪"))));
    }

    /** 模型调用失败/超时：占位行标失败（runId 可能缺失——崩溃恢复由僵尸收口处理）。 */
    private Mono<Void> failPreparing(UUID id, String errorCode) {
        return plans.complete(id, "failed", "", null, null, null, errorCode, clock.instant().plus(PLAN_TTL))
                .then();
    }

    /** 读取（API-14）：僵尸 preparing>120s CAS 标失败；返回权威行。 */
    public Mono<CanvasAgentPlanRepository.AgentPlanRow> get(String accountId, UUID planId) {
        return plans.findById(planId)
                .filter(row -> row.accountId().equals(accountId))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND",
                        "计划不存在")))
                .flatMap(row -> {
                    if ("preparing".equals(row.status())
                            && row.createdAt().toInstant().isBefore(clock.instant().minus(ZOMBIE_PREPARING))) {
                        return plans.failZombie(planId, clock.instant().minus(ZOMBIE_PREPARING))
                                .then(plans.findById(planId));
                    }
                    return Mono.just(row);
                });
    }

    private Mono<List<Map<String, Object>>> shotsOf(UUID storyboardId) {
        return db.sql("SELECT id::text, visual, narration, planned_seconds, camera_move FROM video_shot "
                        + "WHERE storyboard_id=CAST(:sb AS uuid) ORDER BY seq")
                .bind("sb", storyboardId.toString())
                .map((row, meta) -> Map.<String, Object>of(
                        "id", row.get(0, String.class),
                        "visual", String.valueOf(row.get(1, String.class)),
                        "narration", String.valueOf(row.get(2, String.class)),
                        "plannedSeconds", row.get(3, Integer.class),
                        "cameraMove", row.get(4, String.class)))
                .all().collectList();
    }

    static String hashOf(String accountId, CreatePlanRequest request) {
        String canonical = String.join("|", accountId, request.draftId().toString(),
                request.storyboardId().toString(),
                String.valueOf(request.expectedEditVersion()),
                String.valueOf(request.expectedCanvasRevision()), request.instruction(),
                request.selectedNodeIds() == null ? "" : String.join(",", request.selectedNodeIds()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String toJsonArray(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception error) {
            return "[]";
        }
    }
}
