package com.grassland.trust.dispute;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.adjudication.CaseEvidenceRedactor;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.trust.precedent.PrecedentService;
import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import com.grassland.trust.workflow.AdjudicationWorkflowStarter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * 争议 HTTP 入口（草场 Epic 6 Slice 6A / HLD 10.5；6C 扩活跃查询 + 终局状态）。
 *
 * <ul>
 * <li>POST /api/trust/disputes — 开争议（requireMerchantOrRecommender；org 取
 * caller；幂等：每 engagement 至多一个活跃争议； outbox {@code DisputeOpened}；201 首次 / 200
 * 既有）。</li>
 * <li>POST /api/trust/disputes/{id}/decide — 手动裁决（requireMerchant + org
 * 自查；open→final 终局；outbox {@code DisputeDecided}）。 授权 provisional——真裁决来自后续审判
 * slice。</li>
 * <li>GET /api/trust/engagements/{engagementRef}/open-dispute —
 * 活跃（未终局）争议查询（marketplace DisputeChecker 调； 接受 marketplace 服务断言或商家；200 body 或
 * 404）。终局争议不在此查得 → 结算不再 held。</li>
 * </ul>
 *
 * <p>
 * 身份靠 {@link TrustCallerResolver}（BFF/服务断言）；org 归属自查（HLD 7.4）。错误统一由
 * {@code TrustErrorHandler} 处理。
 */
@RestController
public class DisputeController {

	private static final Logger log = LoggerFactory.getLogger(DisputeController.class);

	private final TrustCallerResolver callers;
	private final DisputeCaseRepository disputes;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final MarketplaceEngagementAuthorizationClient authorizer;
	private final DeferredDisputeRequestRepository deferredRequests;
	private final MerchantRejectionFinalizer merchantRejectionFinalizer;
	private final AdjudicationProperties adjudicationProps;
	private final DisputeEvidenceService evidenceService;
	private final CaseEvidenceRedactor evidenceRedactor;
	private final AdjudicationWorkflowStarter workflowStarter;
	private final PrecedentService precedents;
	/** 读争议的受众口径（当事方含被诉推荐官 / marketplace 服务 / 客服），见 {@link DisputeAudience}。 */
	private final com.grassland.trust.security.DisputeAudience audience;

	public DisputeController(TrustCallerResolver callers, DisputeCaseRepository disputes, OutboxRepository outbox,
			TransactionalOperator transactions, MarketplaceEngagementAuthorizationClient authorizer,
			DeferredDisputeRequestRepository deferredRequests, MerchantRejectionFinalizer merchantRejectionFinalizer,
			AdjudicationProperties adjudicationProps, DisputeEvidenceService evidenceService,
			CaseEvidenceRedactor evidenceRedactor, AdjudicationWorkflowStarter workflowStarter,
			PrecedentService precedents, com.grassland.trust.security.DisputeAudience audience) {
		this.callers = callers;
		this.disputes = disputes;
		this.outbox = outbox;
		this.transactions = transactions;
		this.authorizer = authorizer;
		this.deferredRequests = deferredRequests;
		this.merchantRejectionFinalizer = merchantRejectionFinalizer;
		this.adjudicationProps = adjudicationProps;
		this.evidenceService = evidenceService;
		this.evidenceRedactor = evidenceRedactor;
		this.workflowStarter = workflowStarter;
		this.precedents = precedents;
		this.audience = audience;
	}

	@PostMapping("/api/trust/disputes")
	public Mono<ResponseEntity<Map<String, Object>>> open(@RequestBody OpenDisputeRequest body,
			ServerHttpRequest request) {
		// 安全收口（Slice 12）：先验签身份 + marketplace 授权（确认调用方是该 application 的当事方、并取 canonical
		// task organization），**再**查既有活跃争议——否则非参与方可读/复用他人履约的既有争议。
		// organization 不再取自断言（推荐官本就无 org；merchant 的 org 须与 task 一致，由 marketplace 裁定）。
		//
		// D-03 slice 2：marketplace 服务断言可代商家开 merchant_rejection 争议（商家在确认窗口拒绝核实通过履约）。
		// marketplace 已 loadOwnedTask 校验商家 ownership，故跳过 authorizer，直接用 payload 的
		// openedByAccountId/org。
		return callers.resolve(request).flatMap(caller -> {
			if (caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE)) {
				return openForMarketplaceService(body, caller.organizationId())
						.map(o -> response(o.created() ? HttpStatus.CREATED : HttpStatus.OK, toBody(o.dispute())));
			}
			if (!caller.isMerchant() && !caller.isRecommender()) {
				return fail(403, "需要商家或推荐官身份");
			}
				// 终端用户路径：authorizer 校验当事方 + 取 canonical org。
				return authorizer.authorize(body.engagementRef(), caller.accountId(), caller.activeIdentityType())
						.switchIfEmpty(fail(403, "无权对该履约开争议"))
						.flatMap(auth -> openOrDefer(auth.engagementRef(), auth.organizationId(), caller.accountId(),
								caller.activeIdentityType(), body.reason(), body.evidence(), auth.premiumSupportAtAccept(),
								auth.resultAnchorAt(), body.channel(), auth.taskPlatform(),
								// 方案 α（V16）：merchant 开争议 → 被诉方=该推荐官（授权响应固化，无 org 也可达）；
								// recommender 开争议 → 被诉方是商家组织（org 维度可查），不落账号。
								"merchant".equals(caller.activeIdentityType()) ? auth.recommenderAccountId() : null));
		});
	}

	/**
	 * marketplace 服务断言代商家开 merchant_rejection 争议（D-03 §2）。仅允许该 kind；openedBy/org
	 * 取请求体。
	 */
	private Mono<Opened> openForMarketplaceService(OpenDisputeRequest body, String assertionOrganizationId) {
		if (!"merchant_rejection".equals(body.kind())) {
			return fail(403, "服务断言仅可开 merchant_rejection 争议");
		}
		if (body.openedByAccountId() == null || body.organizationId() == null) {
			return fail(400, "缺少 openedByAccountId / organizationId");
		}
		if (!body.organizationId().equals(assertionOrganizationId)) {
			return fail(403, "服务断言组织与请求不一致");
		}
		return disputes.findActiveByEngagementRef(body.engagementRef())
				.flatMap(existing -> "merchant_rejection".equals(existing.kind())
						? Mono.just(new Opened(existing, false))
						: Mono.<Opened>error(new TrustException(409, "该履约已有普通活跃争议")))
				.switchIfEmpty(transactions.transactional(disputes
						.create(body.engagementRef(), body.organizationId(), body.openedByAccountId(), "merchant",
								body.reason(), "merchant_rejection", Boolean.TRUE.equals(body.premiumSupportAtAccept()))
						.map(d -> new Opened(d, true))
						.flatMap(
								opened -> outbox.append(envelope("DisputeOpened", opened.dispute())).thenReturn(opened))
						.flatMap(opened -> evidenceService
								.submit(opened.dispute().id(), body.openedByAccountId(), "merchant", body.evidence())
								.thenReturn(opened))))
				// create 撞唯一键 → 空（并发对手已开案）。必须回读，否则返回空 200 体，
				// marketplace 侧 TrustDisputeClient 解析不到 data.id 会抛错 → 商家收到 500。
				.switchIfEmpty(Mono.defer(() -> disputes.findActiveByEngagementRef(body.engagementRef())
						.flatMap(existing -> "merchant_rejection".equals(existing.kind())
								? Mono.just(new Opened(existing, false))
								: Mono.<Opened>error(new TrustException(409, "该履约已有普通活跃争议")))
						.switchIfEmpty(Mono.error(new TrustException(409, "开争议失败，请重试")))));
	}

	/**
	 * 用户普通争议：无活跃案则即时创建；推荐官遇 merchant_rejection 时持久化 deferred request。
	 * {@code resultAnchorAt}（任务书 #70 卡B）= 履约最近一次结果性事件时刻，仅约束「创建新争议」 的异议窗口——活跃争议幂等返回
	 * / deferred 路径一律不受影响（D6）。
	 * 任务书 #74 卡 A（D6）：通道由提异议方自选且提交后不可改（channel 参数）；
	 * 卡 B：court 通道受理即落质证截止并自动启动审判 workflow（质证段先行）。
	 */
	private Mono<ResponseEntity<Map<String, Object>>> openOrDefer(String engagementRef, String organizationId,
			String openedBy, String role, String reason, List<OpenDisputeRequest.EvidenceItem> evidence,
			boolean premiumSupport, Instant resultAnchorAt, String channel, String taskPlatform,
			String respondentAccountId) {
		return disputes.findActiveByEngagementRef(engagementRef).flatMap(active -> {
			if ("merchant_rejection".equals(active.kind())) {
				if (!"recommender".equals(role)) {
					return fail(409, "该履约已有商家履约异议，须等待客服终审");
				}
				return deferredRequests.findBySourceAndRecommender(active.id(), openedBy)
						.map(existing -> response(HttpStatus.OK, deferredBody(existing)))
						.switchIfEmpty(transactions
								.transactional(deferredRequests.createOrFind(active, openedBy, reason))
								.then(Mono.defer(
										() -> deferredRequests.findBySourceAndRecommender(active.id(), openedBy)))
								.map(created -> response(HttpStatus.ACCEPTED, deferredBody(created))));
			}
			return Mono.just(response(HttpStatus.OK, toBody(active)));
		}).switchIfEmpty(
				// GL-P2-TRUST-001：检查冷却期（终局后恶意重复开争议）
				checkDisputeCooldown(engagementRef).flatMap(cooldownElapsed -> {
					if (!cooldownElapsed) {
						long effective = adjudicationProps.disputeCooldownSecondsEffective();
						// 秒级覆盖（dev/e2e）下 effective 可能 < 1h，按小时显示会截断成 0；按量级择单位。
						String wait = effective >= 3600L ? (effective / 3600L) + " 小时" : Math.max(1L, effective) + " 秒";
						return fail(409, String.format("该履约近期已有终局争议，需等待 %s 后才能再次开争议（冷却期防恶意重复）", wait));
					}
					// 任务书 #70 卡B（PRD §7.1）：异议须在核实结果公布后 48h 内提出。
					return checkDisputeWindow(resultAnchorAt).flatMap(withinWindow -> {
						if (!withinWindow) {
							long windowEffective = adjudicationProps.disputeOpenWindowSecondsEffective();
							String window = windowEffective >= 3600L
									? (windowEffective / 3600L) + " 小时"
									: Math.max(1L, windowEffective) + " 秒";
							return fail(409, String.format("核实结果已公布超过 %s，异议期已过，无法开启争议（如有特殊情况请联系平台客服）", window));
						}
						return createNewDispute(engagementRef, organizationId, openedBy, role, reason, evidence,
								premiumSupport, channel, taskPlatform, respondentAccountId);
					});
				}));
	}

	private Map<String, Object> deferredBody(DeferredDisputeRequest request) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("status", request.status());
		m.put("requestId", request.id());
		m.put("engagementRef", request.engagementRef());
		m.put("reason", evidenceRedactor.maskText(request.reason()));
		m.put("disputeId", request.promotedDisputeId() == null ? "" : request.promotedDisputeId());
		m.put("workflowId", request.adjudicationWorkflowId() == null ? "" : request.adjudicationWorkflowId());
		return m;
	}

	private ResponseEntity<Map<String, Object>> response(HttpStatus status, Map<String, Object> data) {
		return ResponseEntity.status(status).body(Map.of("success", true, "data", data));
	}

	@GetMapping("/api/trust/dispute-requests/{requestId}")
	public Mono<ResponseEntity<Map<String, Object>>> getRequest(@PathVariable String requestId,
			ServerHttpRequest request) {
		return callers.resolvePartyOrService(request, TrustCallerResolver.MARKETPLACE_SERVICE)
				.flatMap(caller -> deferredRequests.findById(requestId).switchIfEmpty(fail(404, "争议请求不存在"))
						.filter(r -> r.recommenderAccountId().equals(caller.accountId()) || caller.isCustomerService()
								|| caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE))
						.switchIfEmpty(fail(403, "无权查询该争议请求")).map(r -> response(HttpStatus.OK, deferredBody(r))));
	}

	/**
	 * 方案 α（任务书 #74 §四.1，通知深链 /me/disputes 的数据面）：当前账号参与的争议列表。
	 * 三路并集见 {@link DisputeCaseRepository#findForAccount}——我开启的 / 我方组织被诉的 /
	 * 我是落库被诉推荐官的。响应附 {@code viewerRole}（claimant/respondent/bystander），
	 * 前端据此渲染答辩/质证操作区，不依赖泄露 openedByAccountId。
	 */
	@GetMapping("/api/trust/disputes/me")
	public Mono<ResponseEntity<Map<String, Object>>> myDisputes(ServerHttpRequest request) {
		return callers.resolve(request).flatMap(caller -> {
			if (!caller.isMerchant() && !caller.isRecommender() && !caller.isCustomerService()) {
				return fail(403, "需要商家或推荐官身份");
			}
			return disputes.findForAccount(caller.accountId(), caller.organizationId(), 100).collectList()
					.map(list -> ResponseEntity.ok(Map.of("success", true, "data",
							Map.of("items", list.stream().map(d -> toPartyBody(d, caller)).toList()))));
		});
	}

	/**
	 * 方案 α：争议详情（当事方可读；受众口径统一在 {@link DisputeAudience}，含被诉推荐官第五路）。
	 * 脱敏口径同 {@link #toBody}——不回 openedByAccountId，身份经 {@code viewerRole} 派生。
	 */
	@GetMapping("/api/trust/disputes/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> detail(@PathVariable String id, ServerHttpRequest request) {
		return callers.resolve(request).flatMap(caller -> disputes.findById(id)
				.switchIfEmpty(fail(404, "争议不存在"))
				.filterWhen(d -> audience.canRead(caller, d))
				.switchIfEmpty(fail(403, "无权查询该争议"))
				.map(d -> ResponseEntity.ok(Map.of("success", true, "data", toPartyBody(d, caller)))));
	}

	@PostMapping("/api/trust/disputes/{id}/decide")
	public Mono<ResponseEntity<Map<String, Object>>> decide(@PathVariable String id,
			@RequestBody DecideDisputeRequest body, ServerHttpRequest request) {
		return callers.requireMerchant(request)
				.flatMap(caller -> disputes.findById(id).switchIfEmpty(fail(404, "争议不存在")).flatMap(d -> {
					if (!d.organizationId().equals(caller.organizationId())) {
						return fail(403, "无权操作该争议");
					}
					if ("merchant_rejection".equals(d.kind())) {
						return fail(409, "商家履约异议须由客服终审");
					}
					// 任务书 #74 卡 A：客服直裁不给商家自裁口（cs_direct 停 open 态，须由
					// final-decision（客服+MFA）或 SLA 自动终局收尾）。
					if (OpenDisputeRequest.CHANNEL_CS_DIRECT.equals(d.effectiveChannel())) {
						return fail(409, "客服直裁争议须由平台客服终审");
					}
					return transactions.transactional(
							disputes.decide(id, body.decision()).switchIfEmpty(fail(409, "该争议已裁决")).flatMap(
									decided -> outbox.append(envelope("DisputeDecided", decided)).thenReturn(decided)))
							.flatMap(decided ->
									// 任务书 #74 卡 G：终局即判例入库（商家手动 decide 经由；幂等）。
									precedents.record(id).onErrorResume(e -> Mono.empty()).thenReturn(decided))
							.map(finalized -> ResponseEntity.ok(Map.of("success", true, "data", toBody(finalized))));
				}));
	}

	/**
	 * 活跃（未终局）争议查询（marketplace DisputeChecker 调）：200 body 或 404。服务 principal 信任；商家查须
	 * org 自查。
	 */
	@GetMapping("/api/trust/engagements/{engagementRef}/open-dispute")
	public Mono<ResponseEntity<Map<String, Object>>> openDispute(@PathVariable String engagementRef,
			ServerHttpRequest request) {
		return callers.resolveMerchantOrService(request, TrustCallerResolver.MARKETPLACE_SERVICE)
				.flatMap(caller -> disputes.findActiveByEngagementRef(engagementRef).switchIfEmpty(fail(404, "无活跃争议"))
						.filter(d -> caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE)
								|| d.organizationId().equals(caller.organizationId()))
						.switchIfEmpty(fail(403, "无权查询该组织争议"))
						.map(d -> ResponseEntity.ok(Map.of("success", true, "data", toBody(d)))));
	}

	/**
	 * D-03 §2 客服 SLA 超时自动终局（内部，仅 marketplace 服务）：merchant_rejection 争议客服未在 SLA 内裁定
	 * → 默认按系统核实结果（for_recommender）结算，避免裁定侧悬置。无 MFA（系统动作，非客服覆盖）。 非
	 * merchant_rejection / 已终局 → 幂等 200（不动）。
	 */
	@PostMapping("/api/trust/internal/disputes/{id}/auto-finalize")
	public Mono<ResponseEntity<Map<String, Object>>> autoFinalize(@PathVariable String id, ServerHttpRequest request) {
		return callers.resolve(request).filter(c -> c.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE))
				.switchIfEmpty(fail(403, "仅 marketplace 服务可调用自动终局"))
				.flatMap(caller -> disputes.findById(id).switchIfEmpty(fail(404, "争议不存在"))
						.filter(d -> d.organizationId().equals(caller.organizationId()))
						.switchIfEmpty(fail(403, "服务断言组织与案件不一致")).flatMap(d -> {
							if (!"merchant_rejection".equals(d.kind())) {
								return fail(409, "仅 merchant_rejection 争议可自动终局");
							}
							if ("final".equals(d.status())) {
								return Mono.just(new MerchantRejectionFinalizer.Finalization(d, null, null));
							}
							return merchantRejectionFinalizer.finalizeCase(d, "for_recommender", null);
						}))
				.map(result -> ResponseEntity.ok(Map.of("success", true, "data", toBody(result.finalized()))));
	}

	private EventEnvelope envelope(String eventType, DisputeCase d) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("disputeId", d.id());
		payload.put("engagementRef", d.engagementRef());
		payload.put("organizationId", d.organizationId());
		payload.put("openedByAccountId", d.openedByAccountId());
		payload.put("openedByRole", d.openedByRole());
		payload.put("status", d.status());
		payload.put("kind", d.kind());
		// 任务书 #74 卡 A/B：通道与质证截止随事件下发（identity 文案按 channel 分流；marketplace
		// TrustEventProcessor 派生 EngagementDisputed 时透传）。
		payload.put("channel", d.effectiveChannel());
		if (d.evidenceDeadline() != null) {
			payload.put("evidenceDeadline", d.evidenceDeadline().toString());
		}
		if (d.decision() != null) {
			payload.put("decision", d.decision());
		}
		return new EventEnvelope(UUID.randomUUID().toString(), eventType, "DisputeCase", d.id(), d.version(),
				Instant.now(), null, payload);
	}

	private Map<String, Object> toBody(DisputeCase d) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", d.id());
		m.put("engagementRef", d.engagementRef());
		m.put("organizationId", d.organizationId());
		m.put("openedByAlias", evidenceRedactor.pseudonym(d.id(), d.openedByAccountId()));
		m.put("openedByRole", d.openedByRole());
		m.put("status", d.status());
		m.put("kind", d.kind());
		m.put("channel", d.effectiveChannel());
		m.put("reason", evidenceRedactor.maskText(d.reason()));
		m.put("decision", d.decision());
		m.put("decidedAt", d.decidedAt() == null ? null : d.decidedAt().toString());
		m.put("round", d.round());
		m.put("version", d.version());
		m.put("appealState", d.appealState());
		m.put("finalDecision", d.finalDecision());
		m.put("premiumSupport", d.premiumSupport());
		m.put("supportPriority", d.supportPriority());
		m.put("supportBadge", d.premiumSupport() ? "premium" : "standard");
		m.put("csDueAt", d.csDueAt() == null ? null : d.csDueAt().toString());
		m.put("evidenceDeadline", d.evidenceDeadline() == null ? null : d.evidenceDeadline().toString());
		m.put("respondentAnswered", d.respondentAnswered());
		m.put("claimantDoneAt", d.claimantDoneAt() == null ? null : d.claimantDoneAt().toString());
		m.put("respondentDoneAt", d.respondentDoneAt() == null ? null : d.respondentDoneAt().toString());
		m.put("taskPlatform", d.taskPlatform());
		m.put("createdAt", d.createdAt() == null ? null : d.createdAt().toString());
		return m;
	}

	/**
	 * 方案 α（/me 与 /{id}）：toBody + 服务端派生的查看者角色。前端不能拿 openedByAccountId 自判
	 * （脱敏红线不回该字段），claimant=我开启 / respondent=我是被诉方（org 匹配或 respondent 列命中）/
	 * bystander=客服等其他受众。
	 */
	private Map<String, Object> toPartyBody(DisputeCase d,
			com.grassland.trust.security.TrustCallerResolver.Caller viewer) {
		Map<String, Object> m = toBody(d);
		String viewerRole;
		if (viewer.accountId() != null && viewer.accountId().equals(d.openedByAccountId())) {
			viewerRole = "claimant";
		} else if (viewer.accountId() != null && viewer.accountId().equals(d.respondentAccountId())) {
			viewerRole = "respondent";
		} else if (viewer.organizationId() != null && viewer.organizationId().equals(d.organizationId())) {
			viewerRole = "respondent";
		} else {
			viewerRole = "bystander";
		}
		m.put("viewerRole", viewerRole);
		return m;
	}

	private record Opened(DisputeCase dispute, boolean created) {
	}

	/** GL-P2-TRUST-001：检查争议冷却期。终局争议需等待冷却期后才可再次开争议。 */
	private Mono<Boolean> checkDisputeCooldown(String engagementRef) {
		// 测试环境可通过 dispute-cooldown-hours=0 跳过冷却期校验
		long effectiveCooldown = adjudicationProps.disputeCooldownSecondsEffective();
		if (effectiveCooldown == 0) {
			return Mono.just(true); // 冷却期配置为 0 = 跳过校验（测试环境）
		}
		return disputes.findLastFinalizedByEngagementRef(engagementRef).map(lastFinalized -> {
			if (lastFinalized == null || lastFinalized.decidedAt() == null) {
				return true; // 无终局争议，冷却期通过
			}
			Instant cooldownDeadline = lastFinalized.decidedAt().plusSeconds(effectiveCooldown);
			return Instant.now().isAfter(cooldownDeadline) || Instant.now().equals(cooldownDeadline);
		}).defaultIfEmpty(true);
	}

	/**
	 * 任务书 #70 卡B（PRD §7.1）：异议窗口。核实结果公布（anchor）超过窗口 → 拒绝创建新争议。
	 * anchor=null（无任何结果性事件，覆盖存量与未提交未确认边缘）→ fail-open 不设限； 窗口 0=禁用（测试哨兵）。结构照
	 * {@link #checkDisputeCooldown(String)}。
	 */
	private Mono<Boolean> checkDisputeWindow(Instant resultAnchorAt) {
		long effectiveWindow = adjudicationProps.disputeOpenWindowSecondsEffective();
		if (effectiveWindow == 0) {
			return Mono.just(true); // 异议窗口配置为 0 = 跳过校验（测试环境）
		}
		if (resultAnchorAt == null) {
			log.debug("dispute open window skipped: no result anchor for engagement");
			return Mono.just(true); // fail-open：无结果性事件不设限
		}
		Instant deadline = resultAnchorAt.plusSeconds(effectiveWindow);
		return Mono.just(!Instant.now().isAfter(deadline));
	}

	/** 创建新争议（无活跃争议且冷却期通过）。任务书 #74 卡 A/B：落通道 + cs_due_at/质证截止 + 自动启动 workflow。 */
	private Mono<ResponseEntity<Map<String, Object>>> createNewDispute(String engagementRef, String organizationId,
			String openedBy, String role, String reason, List<OpenDisputeRequest.EvidenceItem> evidence,
			boolean premiumSupport, String channel, String taskPlatform, String respondentAccountId) {
		String effectiveChannel = (channel == null || channel.isBlank()) ? OpenDisputeRequest.CHANNEL_COURT : channel;
		// 卡 A：cs_due_at = 受理时刻 + SLA；卡 B：质证截止 = 受理时刻 + 质证窗（court 通道）。
		Instant now = Instant.now();
		Instant csDueAt = OpenDisputeRequest.CHANNEL_CS_DIRECT.equals(effectiveChannel)
				? now.plusSeconds(adjudicationProps.csDirectSlaSecondsEffective())
				: null;
		Instant evidenceDeadline = OpenDisputeRequest.CHANNEL_CS_DIRECT.equals(effectiveChannel)
				? null
				: now.plusSeconds(adjudicationProps.evidenceWindowSecondsEffective());
		return transactions
				.transactional(disputes
						.createCase(UUID.randomUUID().toString(), engagementRef, organizationId, openedBy, role,
								reason, "standard", premiumSupport, effectiveChannel, csDueAt, evidenceDeadline,
								respondentAccountId)
						.flatMap(created -> outbox.append(envelope("DisputeOpened", created)).thenReturn(created))
						.flatMap(created -> evidenceService.submit(created.id(), openedBy, role, evidence)
								.thenReturn(created)))
				.then(Mono.defer(() -> disputes.findActiveByEngagementRef(engagementRef)))
				.flatMap(created -> disputes.updateTaskPlatform(created.id(), taskPlatform).thenReturn(created))
				.flatMap(created -> {
					if ("merchant_rejection".equals(created.kind())) {
						// create 并发输给 merchant rejection：按同一 deferred 语义恢复。
						if (!"recommender".equals(role)) {
							return fail(409, "该履约已有商家履约异议，须等待客服终审");
						}
						return transactions.transactional(deferredRequests.createOrFind(created, openedBy, reason))
								.then(Mono.defer(
										() -> deferredRequests.findBySourceAndRecommender(created.id(), openedBy)))
								.map(request -> response(HttpStatus.ACCEPTED, deferredBody(request)));
					}
					// 卡 B（court）：开争议即启动审判 workflow（质证段先行；手动 adjudicate 保留为自愈入口）。
					// 卡 A（cs_direct）：启动 SLA workflow，到点未裁自动终局。二者皆 best-effort——失败不回滚开案
					// （workflowId 固定幂等；SLA 到点扫描由 dispatcher/人工兜底见任务书）。
					return startChannelWorkflows(created)
							.onErrorResume(e -> {
								log.warn("dispute workflow start failed disputeId={} channel={}", created.id(),
										effectiveChannel, e);
								return Mono.empty();
							})
							.thenReturn(created)
							.map(d -> {
								boolean ownCreation = openedBy.equals(d.openedByAccountId());
								return response(ownCreation ? HttpStatus.CREATED : HttpStatus.OK, toBody(d));
							});
				});
	}

	/** 按通道启动对应 workflow（best-effort，调用方兜底 WARN）。 */
	private Mono<Void> startChannelWorkflows(DisputeCase created) {
		if (OpenDisputeRequest.CHANNEL_CS_DIRECT.equals(created.effectiveChannel())) {
			return workflowStarter.startCsSla(created.id(), adjudicationProps.csDirectSlaSecondsEffective()).then();
		}
		if ("court".equals(created.effectiveChannel())) {
			return workflowStarter.start(created.id()).then();
		}
		return Mono.empty();
	}

	private static <T> Mono<T> fail(int status, String message) {
		return Mono.error(new TrustException(status, message));
	}
}
