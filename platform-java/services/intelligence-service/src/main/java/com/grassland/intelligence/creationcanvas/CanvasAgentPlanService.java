package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.creationcontext.CreationContextSnapshotRepository;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Claim the operation, freeze authorized context in a short transaction, then
 * invoke the existing text execution loop.
 */
@Service
public class CanvasAgentPlanService {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);
	static final Duration ZOMBIE_PREPARING = Duration.ofSeconds(120);
	static final Duration PLAN_TTL = Duration.ofMinutes(30);
	private final CanvasAgentPlanRepository plans;
	private final CanvasAgentContextBuilder contexts;
	private final DatabaseClient db;
	private final FrozenTextExecutionService frozenText;
	private final CanvasProjectAccess projects;
	private final CreationContextSnapshotRepository snapshots;
	private final TransactionalOperator transactions;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public CanvasAgentPlanService(CanvasAgentPlanRepository plans, CanvasAgentContextBuilder contexts,
			DatabaseClient db, FrozenTextExecutionService frozenText, CanvasProjectAccess projects,
			CreationContextSnapshotRepository snapshots, TransactionalOperator transactions) {
		this(plans, contexts, db, frozenText, projects, snapshots, transactions, Clock.systemUTC());
	}
	CanvasAgentPlanService(CanvasAgentPlanRepository plans, CanvasAgentContextBuilder contexts, DatabaseClient db,
			FrozenTextExecutionService frozenText, CanvasProjectAccess projects,
			CreationContextSnapshotRepository snapshots, TransactionalOperator transactions, Clock clock) {
		this.plans = plans;
		this.contexts = contexts;
		this.db = db;
		this.frozenText = frozenText;
		this.projects = projects;
		this.snapshots = snapshots;
		this.transactions = transactions;
		this.clock = clock;
	}

	public record CreatePlanRequest(UUID operationId, UUID draftId, UUID storyboardId, List<String> selectedNodeIds,
			Long expectedEditVersion, Long expectedCanvasRevision, String instruction) {
	}
	public record PlanOutcome(CanvasAgentPlanRepository.AgentPlanRow plan, boolean preparing) {
	}
	private record Prepared(CanvasAgentPlanRepository.AgentPlanRow plan, CanvasAgentContextBuilder.Context context,
			UUID snapshotId, int imageCount, int shotCount) {
	}

	public Mono<PlanOutcome> create(ServerWebExchange exchange,
			com.grassland.intelligence.security.IntelligenceCallerResolver.Caller caller, CreatePlanRequest request) {
		String accountId = caller.accountId();
		if (request == null || request.operationId() == null || request.draftId() == null
				|| request.storyboardId() == null || request.instruction() == null || request.instruction().isBlank()
				|| request.selectedNodeIds() == null)
			return Mono.error(invalid("计划请求不完整"));
		if (request.instruction().trim().codePoints().count() > 2000 || request.selectedNodeIds().size() > 20)
			return Mono.error(new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED", "指令最多 2000 字，最多选择 20 个节点"));
		if (request.selectedNodeIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9:_-]{1,96}"))
				|| new HashSet<>(request.selectedNodeIds()).size() != request.selectedNodeIds().size()
				|| !version(request.expectedEditVersion()) || !version(request.expectedCanvasRevision()))
			return Mono.error(invalid("选择或版本无效"));
		String hash = hashOf(accountId, request);
		return plans.findByAccountAndOperation(accountId, request.operationId()).flatMap(row -> replay(row, hash))
				.switchIfEmpty(Mono
						.defer(() -> prepare(accountId, request, hash).flatMap(prepared -> prepared.context() == null
								? replay(prepared.plan(), hash)
								: runModel(exchange, caller, prepared))));
	}

	private Mono<Prepared> prepare(String accountId, CreatePlanRequest request, String hash) {
		var placeholder = new CanvasAgentPlanRepository.AgentPlanRow(UUID.randomUUID(), accountId,
				request.operationId(), hash, request.draftId(), request.storyboardId(), 0,
				request.expectedEditVersion(), request.expectedCanvasRevision(), "preparing",
				json(request.selectedNodeIds()), request.instruction(), "", null, null, null, null, null, null, null,
				clock.instant().plus(PLAN_TTL).atOffset(ZoneOffset.UTC));
		// The plan insert/unique-key wait comes BEFORE all parent locks, matching the
		// apply lock order.
		return plans.insertPlaceholder(placeholder).flatMap(inserted -> {
			if (inserted == 0)
				return plans.findByAccountAndOperation(accountId, request.operationId())
						.map(row -> new Prepared(row, null, null, 0, 0));
			return projects
					.lockProjects(accountId,
							List.of(new CanvasProjectAccess.ProjectKey(request.draftId(), request.storyboardId())))
					.flatMap(locked -> {
						var project = locked.getFirst();
						projects.checkWritable(project, CanvasProjectAccess.WriteKind.PLAN_CREATE);
						if (project.canvas() == null)
							return Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "请先保存当前画布"));
						if (project.storyboard().editVersion() != request.expectedEditVersion()
								|| project.canvas().revision() != request.expectedCanvasRevision())
							return Mono.error(
									new IntelligenceException(409, "CANVAS_VERSION_CONFLICT", "画布已变化，请保存并重新提出修改"));
						int imageCount = imageCount(project.storyboard().requestPayload());
						return shotsOf(request.storyboardId())
								.flatMap(
										shots -> constraints(project, imageCount)
												.flatMap(constraints -> contexts.buildAuthorized(accountId,
														request.draftId().toString(), request
																.storyboardId().toString(),
														request.selectedNodeIds(), project.canvas().documentJson(),
														shots, constraints))
												.flatMap(context -> plans
														.freezeBaseline(placeholder.id(), project.draft().version(),
																project.storyboard().editVersion(),
																project.canvas().revision())
														.map(plan -> new Prepared(plan, context,
																project.storyboard().contextSnapshotId(), imageCount,
																shots.size()))));
					});
		}).as(transactions::transactional);
	}

	private Mono<String> constraints(CanvasProjectAccess.LockedProject project, int imageCount) {
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("imageCount", imageCount);
		context.put("cameraMoves", com.grassland.intelligence.videoproduction.VideoStoryboardEditService.CAMERA_MOVES
				.stream().sorted().toList());
		context.put("storyboardStatus", project.storyboard().status());
		if (project.storyboard().contextSnapshotId() == null)
			return Mono.just(json(context));
		return snapshots.findById(project.storyboard().contextSnapshotId())
				.filter(snapshot -> project.draft().ownerAccountId().equals(snapshot.accountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "任务快照不可用")))
				.map(snapshot -> {
					Map<String, Object> task = new LinkedHashMap<>();
					for (String field : List.of("title", "description", "requirements", "contentRequirements",
							"prohibitions", "targetQuestion"))
						if (snapshot.taskSnapshot().containsKey(field))
							task.put(field, snapshot.taskSnapshot().get(field));
					context.put("frozenTask", task);
					context.put("platformRules", snapshot.platformRulesSnapshot());
					context.put("storeBranding", snapshot.storeBrandingSnapshot());
					context.put("platform", snapshot.platformId());
					context.put("contentForm", snapshot.contentFormId());
					return json(context);
				});
	}

	private Mono<PlanOutcome> runModel(ServerWebExchange exchange,
			com.grassland.intelligence.security.IntelligenceCallerResolver.Caller caller, Prepared prepared) {
		var plan = prepared.plan();
		var context = prepared.context();
		if (context.clarify() != null)
			return plans
					.complete(plan.id(), "clarify", "", context.clarify(), null, null, null,
							clock.instant().plus(PLAN_TTL))
					.then(plans.findById(plan.id())).map(row -> new PlanOutcome(row, false));
		List<ChatMessage> messages = List.of(ChatMessage.system(CanvasAgentPrompts.systemPrompt()),
				ChatMessage.user(CanvasAgentPrompts.userPrompt(context.contextJson(), plan.instruction())));
		java.util.function.Function<TextCompletionResult, String> transform = completion -> {
			String action = CanvasAgentPlan.parseAction(completion.content());
			CanvasAgentPlan.validateAgainstContext(action, new HashSet<>(context.selectedShotIds()),
					prepared.imageCount(), prepared.shotCount());
			return action;
		};
		return Mono
				.defer(() -> prepared.snapshotId() == null
						? frozenText.executeIndependent(exchange, caller, messages,
								CanvasAgentContextBuilder.OUTPUT_MAX_TOKENS, CreditFeature.CREATION_ASSISTANT,
								MODEL_TIMEOUT, transform)
						: frozenText.executeTraced(exchange, caller, prepared.snapshotId(), messages,
								CanvasAgentContextBuilder.OUTPUT_MAX_TOKENS, CreditFeature.CREATION_ASSISTANT,
								MODEL_TIMEOUT, transform))
				.flatMap(traced -> plans.complete(plan.id(), "ready", "修改计划已准备好，请检查差异", null, traced.value(),
						traced.runId(), null, clock.instant().plus(PLAN_TTL)).then(plans.findById(plan.id())))
				.map(row -> new PlanOutcome(row, false)).onErrorResume(error -> {
					boolean invalid = error instanceof IllegalArgumentException;
					boolean timeout = error instanceof java.util.concurrent.TimeoutException
							|| error instanceof IntelligenceException original && original.status() == 504;
					IntelligenceException failure = error instanceof IntelligenceException original && !timeout
							? original
							: new IntelligenceException(invalid ? 502 : timeout ? 504 : 503,
									invalid
											? "CANVAS_AGENT_INVALID_PLAN"
											: timeout ? "CANVAS_AGENT_TIMEOUT" : "AI_PROVIDER_UNAVAILABLE",
									invalid ? "模型返回不合法计划" : timeout ? "计划生成超时，保留运行追踪" : "模型暂不可用，请稍后重试");
					UUID runId = exchange == null
							? null
							: exchange.getAttribute(FrozenTextExecutionService.RUN_ID_ATTRIBUTE);
					return plans.complete(plan.id(), "failed", "", null, null, runId, failure.code(),
							clock.instant().plus(PLAN_TTL)).then(Mono.error(failure));
				});
	}

	private Mono<PlanOutcome> replay(CanvasAgentPlanRepository.AgentPlanRow row, String hash) {
		if (!row.requestHash().equals(hash))
			return Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "操作键已用于不同请求"));
		return get(row.accountId(), row.id())
				.map(current -> new PlanOutcome(current, "preparing".equals(current.status())));
	}

	public Mono<CanvasAgentPlanRepository.AgentPlanRow> get(String accountId, UUID planId) {
		return plans.findById(planId).filter(row -> row.accountId().equals(accountId))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND", "计划不存在")))
				.flatMap(row -> readable(row).then(Mono.defer(() -> {
					if ("preparing".equals(row.status())
							&& !row.createdAt().toInstant().isAfter(clock.instant().minus(ZOMBIE_PREPARING)))
						return plans.failZombie(planId, clock.instant().minus(ZOMBIE_PREPARING))
								.then(plans.findById(planId));
					if ("ready".equals(row.status()) && !row.expiresAt().toInstant().isAfter(clock.instant()))
						return plans.expireReady(planId, clock.instant()).then(plans.findById(planId));
					return Mono.just(row);
				})));
	}

	private Mono<Void> readable(CanvasAgentPlanRepository.AgentPlanRow plan) {
		return db.sql(
				"SELECT EXISTS(SELECT 1 FROM creation_draft d JOIN video_storyboard_workspace w ON w.draft_id=d.id "
						+ "JOIN video_storyboard s ON s.id=w.storyboard_id WHERE d.id=:draft AND s.id=:sb "
						+ "AND d.deleted_at IS NULL AND d.owner_account_id=:account AND s.account_id=:account AND w.account_id=:account)")
				.bind("draft", plan.draftId()).bind("sb", plan.storyboardId()).bind("account", plan.accountId())
				.map(row -> row.get(0, Boolean.class)).one()
				.flatMap(allowed -> allowed
						? Mono.empty()
						: Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND", "项目不存在")));
	}

	private Mono<List<Map<String, Object>>> shotsOf(UUID id) {
		return db.sql(
				"SELECT id::text,visual,narration,planned_seconds,camera_move FROM video_shot WHERE storyboard_id=:id ORDER BY seq")
				.bind("id", id)
				.map(row -> Map.<String, Object>of("id", row.get(0, String.class), "visual", row.get(1, String.class),
						"narration", row.get(2, String.class), "plannedSeconds", row.get(3, Integer.class),
						"cameraMove", row.get(4, String.class)))
				.all().collectList();
	}
	private static int imageCount(String payload) {
		try {
			var images = MAPPER.readTree(payload).path("images");
			int count = 0;
			if (images.isArray())
				for (var image : images)
					if (image.isTextual() && !image.asText().isBlank())
						count++;
			return count;
		} catch (Exception error) {
			throw invalid("分镜图片输入不可读");
		}
	}
	static String hashOf(String accountId, CreatePlanRequest request) {
		try {
			String canonical = String.join("|", accountId, request.draftId().toString(),
					request.storyboardId().toString(), String.valueOf(request.expectedEditVersion()),
					String.valueOf(request.expectedCanvasRevision()), request.instruction(),
					String.join(",", request.selectedNodeIds()));
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}
	private static boolean version(Long value) {
		return value != null && value >= 1 && value <= 9_007_199_254_740_991L;
	}
	private static String json(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception error) {
			throw invalid("上下文无法序列化");
		}
	}
	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "CANVAS_INVALID_INPUT", message);
	}
}
