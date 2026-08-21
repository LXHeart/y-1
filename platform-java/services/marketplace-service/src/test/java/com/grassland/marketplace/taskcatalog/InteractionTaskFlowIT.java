package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient.VerificationAnalysis;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 点赞互动任务全链 IT（任务书 #23 / ADR-D13）：受控值集 + 交叉校验（R1/R2）→ 提交契约 platformHandle（R3） →
 * 核验检查表（R4：复用 link/platform、互动 evidence 分支、新增 interaction_screenshot、跳过
 * ai_visual）。 互动任务不带资金字段 → accept 走直连路径（无 Saga）。bean 覆盖集合与
 * ApplicationControllerIT 完全一致以复用上下文。
 */
@SuppressWarnings("unchecked")
class InteractionTaskFlowIT extends MarketplaceItSupport {

	private static final String H = "X-Grassland-Identity";

	@MockitoBean
	private com.grassland.marketplace.workflow.FinanceEscrowClient financeClient;

	@MockitoBean
	private DisputeChecker disputeChecker;

	@MockitoBean
	private IntelligenceMediaClient mediaClient;

	@MockitoBean
	private LinkReachabilityChecker linkChecker;

	@MockitoBean
	private IntelligenceVerificationClient verificationClient;

	@MockitoBean
	private TrustDisputeClient trustDisputeClient;

	@MockitoBean
	private com.grassland.marketplace.workflow.IntelligenceSubmissionSafetyClient submissionSafety;

	@MockitoBean
	private com.grassland.marketplace.workflow.IntelligenceOfficialVerificationClient officialClient;

	@org.junit.jupiter.api.BeforeEach
	void stubOfficialClient() {
		// P1 骨架（ADR-D04）默认态：官方数据源未配置 → 检查项省略
		when(officialClient.fetchOfficialData(any(), any(), any(), any(), any()))
				.thenReturn(reactor.core.publisher.Mono.empty());
	}

	@org.junit.jupiter.api.BeforeEach
	void stubCommentSafety() {
		// 默认放行（fail-open 语义等价）；blocked 用例单独覆写
		// guardSubmission 恒发射（新契约）：默认 skip 态 = 无 advisory 明细、不拦截
		org.mockito.Mockito.when(submissionSafety.guardSubmission(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenReturn(reactor.core.publisher.Mono
						.just(com.grassland.marketplace.workflow.IntelligenceSubmissionSafetyClient.skip()));
	}

	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	private SubmissionAttachmentRepository attachmentRepo;

	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	private com.grassland.marketplace.workflow.saga.MerchantRejectionReviewWorkflowStarter rejectionStarter;

	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	private com.grassland.marketplace.workflow.saga.SettlementWorkflowStarter settlementStarter;

	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	private com.grassland.marketplace.workflow.saga.AcceptanceWorkflowStarter acceptanceStarter;

	@org.springframework.test.context.bean.override.mockito.MockitoSpyBean
	private com.grassland.marketplace.reputation.ReputationService reputationService;

	// ---------- R1/R2：值集 + 交叉校验 ----------

	@Test
	void contentFormValueSetAndCrossBindingValidated() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();

		// 未知值 → 400（受控值集）
		createTask(merchant, org, Map.of("organizationId", org, "title", "直播任务", "contentForm", "livestream"))
				.expectStatus().isBadRequest();

		// interaction 无配置块 → 400（单边违反）
		createTask(merchant, org, Map.of("organizationId", org, "title", "互动任务", "contentForm", "interaction"))
				.expectStatus().isBadRequest();

		// 非 interaction 带块 → 400
		Map<String, Object> wrongBlock = new LinkedHashMap<>();
		wrongBlock.put("organizationId", org);
		wrongBlock.put("title", "图文带块");
		wrongBlock.put("contentForm", "image");
		wrongBlock.put("requirements",
				Map.of("interaction", Map.of("targetUrl", "https://www.xiaohongshu.com/post/1", "actionType", "like")));
		createTask(merchant, org, wrongBlock).expectStatus().isBadRequest();

		// 合法组合 → 201，requirements 回带 interaction 块
		Map<String, Object> resp = createTask(merchant, org, interactionBody(org)).expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> requirements = (Map<String, Object>) ((Map<String, Object>) resp.get("data"))
				.get("requirements");
		Map<String, Object> interaction = (Map<String, Object>) requirements.get("interaction");
		assertThat(interaction).containsEntry("targetUrl", "https://www.xiaohongshu.com/post/1")
				.containsEntry("actionType", "like");
	}

	@Test
	void interactionBlockInvalidTargetOrActionRejected() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		// 内网地址 → 400（复用 LinkUrlGuard，不新写一套）
		Map<String, Object> ssrf = interactionBodyWith("http://127.0.0.1:8080/admin", "like");
		createTask(merchant, org, ssrf).expectStatus().isBadRequest();
		// 非法动作类型（受控值外）→ 400；comment 自缺口清偿之九起合法
		Map<String, Object> badAction = interactionBodyWith("https://www.xiaohongshu.com/post/1", "share");
		createTask(merchant, org, badAction).expectStatus().isBadRequest();
		Map<String, Object> orgMismatch = interactionBodyWith("https://www.xiaohongshu.com/post/1", "comment");
		orgMismatch.put("organizationId", org);
		createTask(merchant, org, orgMismatch).expectStatus().isCreated();
	}

	// ---------- R3：提交契约 ----------

	@Test
	void interactionSubmissionRequiresPlatformHandle() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org);
		String app = applyAndAccept(recommender, task, merchant, org);

		// 缺 platformHandle → 400
		submitRaw(recommender, task, app, null).expectStatus().isBadRequest();

		// 带 handle → 201，contentUrl=目标链接，platformHandle 落库
		Map<String, Object> resp = submitRaw(recommender, task, app, "@seedhunter").expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> data = (Map<String, Object>) resp.get("data");
		assertThat(data.get("platformHandle")).isEqualTo("@seedhunter");
		assertThat(data.get("contentUrl")).isEqualTo("https://www.xiaohongshu.com/post/1");
	}

	// ---------- R4：核验检查表 ----------

	@Test
	void interactionVerificationWithoutScreenshotsFailsCompletenessAndSkipsAiVisual() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org);
		String app = applyAndAccept(recommender, task, merchant, org);

		stubLinkPassed();
		String submission = submit(recommender, task, app, "@seedhunter");

		String checks = awaitRunChecks(submission);
		assertThat(checks).contains("\"evidence_completeness\"").contains("failed");
		assertThat(checks).doesNotContain("\"ai_visual\"");
		assertThat(checks).doesNotContain("\"interaction_screenshot\""); // 无截图跳过模型，不烧调用
		verify(verificationClient, never()).analyze(anyString(), anyList(), anyString(), any(), any());
	}

	@Test
	void interactionScreenshotCheckUsesModelWithInteractionContext() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org);
		String app = applyAndAccept(recommender, task, merchant, org);

		UUID mediaId = UUID.randomUUID();
		when(mediaClient.metadata(org, mediaId, "application", app))
				.thenReturn(Mono.just(mediaMeta(mediaId, recommender, app)));
		stubLinkPassed();
		when(verificationClient.analyzeInteraction(eq(org), eq(List.of(mediaId)), anyString(), any(), any(),
				eq("https://www.xiaohongshu.com/post/1"), eq("like"), eq("@seedhunter"), any()))
				.thenReturn(Mono.just(new VerificationAnalysis("passed",
						List.of(new IntelligenceVerificationClient.MediaResult(mediaId, "passed", "三项均成立")))));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contentUrl", "https://www.xiaohongshu.com/post/1");
		body.put("platformHandle", "@seedhunter");
		body.put("mediaIds", List.of(mediaId.toString()));
		Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
				.header(H, sign(recommender, "recommender")).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		String submission = (String) ((Map<String, Object>) resp.get("data")).get("id");

		verify(verificationClient, timeout(8_000)).analyzeInteraction(eq(org), eq(List.of(mediaId)), anyString(), any(),
				any(), eq("https://www.xiaohongshu.com/post/1"), eq("like"), eq("@seedhunter"), isNull());
		verify(verificationClient, never()).analyze(anyString(), anyList(), anyString(), any(), any());
		String checks = awaitRunChecks(submission);
		assertThat(checks).contains("\"interaction_screenshot\"").contains("passed");
		assertThat(checks).contains("\"evidence_completeness\"").contains("passed");
		assertThat(checks).doesNotContain("\"ai_visual\"");
		assertThat(verificationStatus(submission)).isEqualTo("passed");
	}

	@Test
	void inconclusiveModelResultAggregatesToManualQueue() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org);
		String app = applyAndAccept(recommender, task, merchant, org);

		UUID mediaId = UUID.randomUUID();
		when(mediaClient.metadata(org, mediaId, "application", app))
				.thenReturn(Mono.just(mediaMeta(mediaId, recommender, app)));
		stubLinkPassed();
		when(verificationClient.analyzeInteraction(anyString(), anyList(), anyString(), any(), any(), anyString(),
				anyString(), anyString(), any()))
				.thenReturn(Mono.just(new VerificationAnalysis("inconclusive",
						List.of(new IntelligenceVerificationClient.MediaResult(mediaId, "inconclusive", "截图模糊")))));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contentUrl", "https://www.xiaohongshu.com/post/1");
		body.put("platformHandle", "@seedhunter");
		body.put("mediaIds", List.of(mediaId.toString()));
		Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
				.header(H, sign(recommender, "recommender")).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		String submission = (String) ((Map<String, Object>) resp.get("data")).get("id");

		assertThat(verificationStatus(submission)).as("不确定即人工（VERIFICATION 待判定队列）").isEqualTo("inconclusive");
	}

	// ---------- helpers ----------

	private static Map<String, Object> interactionBody(String org) {
		Map<String, Object> body = interactionBodyWith("https://www.xiaohongshu.com/post/1", "like");
		body.put("organizationId", org); // 与签发断言同 org，否则 requireScope 403
		return body;
	}

	private static Map<String, Object> interactionBodyWith(String targetUrl, String actionType) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("organizationId", UUID.randomUUID().toString());
		body.put("title", "互动任务");
		body.put("contentForm", "interaction");
		body.put("requirements", Map.of("interaction", Map.of("targetUrl", targetUrl, "actionType", actionType)));
		return body;
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec createTask(String merchant,
			String org, Map<String, Object> body) {
		if (!body.containsKey("organizationId")) {
			body.put("organizationId", org);
		}
		return client().post().uri("/api/tasks").header(H, sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
	}

	// ---------- 缺口清偿之九：评论类互动 ----------

	@Test
	void commentTaskSubmissionPersistsCommentText() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "comment");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();

		submitRaw(recommender, task, app, "@seedhunter", "这家店的桂花拿铁真的很惊艳！").expectStatus().isCreated().expectBody()
				.jsonPath("$.data.commentText").isEqualTo("这家店的桂花拿铁真的很惊艳！");

		String saved = db
				.sql("SELECT comment_text FROM engagement_submission"
						+ " WHERE application_id = CAST(:app AS uuid) ORDER BY created_at DESC LIMIT 1")
				.bind("app", app).map(r -> r.get("comment_text", String.class)).one().block();
		assertThat(saved).isEqualTo("这家店的桂花拿铁真的很惊艳！");
	}

	/**
	 * P1 骨架（ADR-D04）——official_data 检查项三态：数据齐备（账号一致+已发布+评论可见）= passed 且 detail
	 * 带归一指标；账号不一致 = failed；未配置（默认 stub empty）= 检查项省略。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void officialDataCheckTriStatesFromGatewayData() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "comment");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();

		// 数据齐备 → passed（detail 带指标）
		when(officialClient.fetchOfficialData(any(), any(), any(), any(), any())).thenReturn(reactor.core.publisher.Mono
				.just(new com.grassland.marketplace.workflow.IntelligenceOfficialVerificationClient.OfficialData(null,
						true, true, true, Map.of("likes", 120L, "comments", 3L))));
		Map<String, Object> created = submitRaw(recommender, task, app, "@seedhunter", "官方数据核验用评论").expectStatus()
				.isCreated().expectBody(Map.class).returnResult().getResponseBody();
		String submission = ((Map<String, Object>) created.get("data")).get("id").toString();
		client().get().uri("/api/tasks/{t}/applications/{a}/submissions", task, app)
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.submissions[0].verification.checks[?(@.type=='official_data')].status")
				.isEqualTo("passed");

		// 账号不一致 → failed
		when(officialClient.fetchOfficialData(any(), any(), any(), any(), any())).thenReturn(reactor.core.publisher.Mono
				.just(new com.grassland.marketplace.workflow.IntelligenceOfficialVerificationClient.OfficialData(null,
						false, true, null, Map.of())));
		client().post()
				.uri("/api/tasks/{t}/applications/{a}/submissions/{s}/verification/checks", task, app, submission)
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.checks[?(@.type=='official_data')].status").isEqualTo("failed");

		// 未配置（默认 empty）→ 检查项省略：重新核验后 checks 里无 official_data
		when(officialClient.fetchOfficialData(any(), any(), any(), any(), any()))
				.thenReturn(reactor.core.publisher.Mono.empty());
		client().post()
				.uri("/api/tasks/{t}/applications/{a}/submissions/{s}/verification/checks", task, app, submission)
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.checks[?(@.type=='official_data')]").doesNotExist();
	}

	@Test
	void commentTaskRequiresCommentText() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "comment");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();

		submitRaw(recommender, task, app, "@seedhunter", null).expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error").isEqualTo("评论互动任务必须填写评论内容");
	}

	@Test
	void nonCommentTaskRejectsCommentText() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "like");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();

		submitRaw(recommender, task, app, "@seedhunter", "不该带的评论文本").expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error").isEqualTo("仅评论互动任务可提交评论内容");
	}

	/**
	 * 遗留清偿——评论人工复核队列闭环：advisory（low/medium）命中不拦截但落 open 复核行； 运营判 violation（必填
	 * note）后商家交付物列表回带 commentFlagged；重判 confirmed 后标记消除。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void advisoryCommentFlowsIntoOpsReviewQueueAndViolationFlagsMerchantList() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "comment");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();
		when(submissionSafety.guardSubmission(any(), any(), any())).thenReturn(reactor.core.publisher.Mono.just(
				new com.grassland.marketplace.workflow.IntelligenceSubmissionSafetyClient.SubmissionCheck(
						new com.grassland.marketplace.workflow.IntelligenceSubmissionSafetyClient.FieldCheck(false,
								List.of(new com.grassland.marketplace.workflow.IntelligenceSubmissionSafetyClient.AdvisoryFinding(
										"contact", "medium", "疑似站外导流用语"))),
						null, "lexicon-v1")));

		Map<String, Object> created = submitRaw(recommender, task, app, "@seedhunter", "加我薇信买同款").expectStatus()
				.isCreated().expectBody(Map.class).returnResult().getResponseBody();
		String submissionId = ((Map<String, Object>) created.get("data")).get("id").toString();

		// open 复核行落库（findings 快照 + 词库版本 + 字段）
		Map<String, Object> row = db
				.sql("SELECT status, field, comment_text, findings::text AS findings,"
						+ " lexicon_version FROM comment_safety_review"
						+ " WHERE submission_id = CAST(:s AS uuid) AND field = 'comment'")
				.bind("s", submissionId)
				.map(r -> Map.<String, Object>of("status", r.get("status", String.class), "field",
						r.get("field", String.class), "comment_text",
						r.get("comment_text", String.class), "findings", r.get("findings", String.class),
						"lexicon_version", r.get("lexicon_version", String.class)))
				.one().block();
		assertThat(row.get("status")).isEqualTo("open");
		assertThat(row.get("comment_text")).isEqualTo("加我薇信买同款");
		assertThat((String) row.get("findings")).contains("contact");
		assertThat(row.get("lexicon_version")).isEqualTo("lexicon-v1");

		// 运营队列可见（customer_service）；普通用户 403
		client().get().uri("/api/ops/comment-reviews").header(H, sign(recommender, "recommender")).exchange()
				.expectStatus().isForbidden();
		Map<String, Object> queue = client().get().uri("/api/ops/comment-reviews")
				.header(H, signWithRole("ops-cs-1", "customer_service")).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> queueData = (Map<String, Object>) queue.get("data");
		List<Map<String, Object>> items = (List<Map<String, Object>>) queueData.get("items");
		var queued = items.stream().filter(item -> submissionId.equals(item.get("submissionId"))).findFirst();
		assertThat(queued).isPresent();
		assertThat(queued.get().get("commentText")).isEqualTo("加我薇信买同款");

		// violation 无 note → 400
		client().post().uri("/api/ops/comment-reviews/{s}/comment/review", submissionId)
				.header(H, signWithRole("ops-cs-1", "customer_service")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("decision", "violation")).exchange().expectStatus().isBadRequest();

		// 判 violation → 商家列表 commentFlagged=true
		client().post().uri("/api/ops/comment-reviews/{s}/comment/review", submissionId)
				.header(H, signWithRole("ops-cs-1", "customer_service")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("decision", "violation", "note", "站外导流违规模式")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("violation");

		client().get().uri("/api/tasks/{t}/applications/{a}/submissions", task, app)
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.submissions[0].commentFlagged").isEqualTo(true);

		// 重判 confirmed（可修正）→ 标记消除（字段不再出现）
		client().post().uri("/api/ops/comment-reviews/{s}/comment/review", submissionId)
				.header(H, signWithRole("ops-cs-1", "customer_service")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("decision", "confirmed", "note", "复核后确认无导流意图")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("confirmed");

		client().get().uri("/api/tasks/{t}/applications/{a}/submissions", task, app)
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.submissions[0].commentFlagged").doesNotExist();
	}

	/** 干净评论（无 advisory 明细）不进复核队列——队列只承接词库存疑项。 */
	@Test
	void cleanCommentDoesNotEnterReviewQueue() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "comment");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();
		// 默认 stub = skip 态（无明细）

		submitRaw(recommender, task, app, "@seedhunter", "这家店的桂花拿铁真的很惊艳！").expectStatus().isCreated();

		Integer reviews = db
				.sql("SELECT COUNT(*)::int AS c FROM comment_safety_review"
						+ " WHERE submission_id IN (SELECT id FROM engagement_submission"
						+ " WHERE application_id = CAST(:app AS uuid))")
				.bind("app", app).map(r -> r.get("c", Integer.class)).one().block();
		assertThat(reviews).isZero();
	}

	@Test
	void blockedCommentRejectedBySafetyGuard() {
		String merchant = UUID.randomUUID().toString();
		String orgId = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, orgId, "comment");
		String app = applyAndAccept(recommender, task, merchant, orgId);
		stubLinkPassed();
		org.mockito.Mockito.when(submissionSafety.guardSubmission(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenReturn(reactor.core.publisher.Mono.error(
						new com.grassland.marketplace.security.MarketplaceException(400, "评论内容未通过内容安全检查，请修改后提交")));

		submitRaw(recommender, task, app, "@seedhunter", "违规内容").expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error").isEqualTo("评论内容未通过内容安全检查，请修改后提交");
	}

	/** 履约硬门槛（ADR-D16 D6 登记项落地）：备注 high 命中 → 400（非评论任务同样拦截）。 */
	@Test
	void blockedSubmissionNoteRejectedBySafetyGuard() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "like");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();
		when(submissionSafety.guardSubmission(any(), any(), any())).thenReturn(reactor.core.publisher.Mono.error(
				new com.grassland.marketplace.security.MarketplaceException(400, "备注未通过内容安全检查，请修改后提交")));

		submitRaw(recommender, task, app, "@seedhunter").expectStatus().isBadRequest().expectBody()
				.jsonPath("$.error").isEqualTo("备注未通过内容安全检查，请修改后提交");

		// 拦截发生在事务前 → 无 submission 残留
		Integer submissions = db
				.sql("SELECT COUNT(*)::int AS c FROM engagement_submission"
						+ " WHERE application_id = CAST(:app AS uuid)")
				.bind("app", app).map(r -> r.get("c", Integer.class)).one().block();
		assertThat(submissions).isZero();
	}

	/** 备注 advisory（low/medium）不拦截，按 field='note' 落复核行（V48：评论与备注各留一行）。 */
	@Test
	@SuppressWarnings("unchecked")
	void advisoryNoteFlowsIntoFieldScopedReviewRow() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "like");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();
		when(submissionSafety.guardSubmission(any(), any(), any())).thenReturn(reactor.core.publisher.Mono.just(
				new com.grassland.marketplace.workflow.IntelligenceSubmissionSafetyClient.SubmissionCheck(null,
						new com.grassland.marketplace.workflow.IntelligenceSubmissionSafetyClient.FieldCheck(false,
								List.of(new com.grassland.marketplace.workflow.IntelligenceSubmissionSafetyClient.AdvisoryFinding(
										"absolute_claims", "medium", "广告法极限词，建议改为具体描述"))),
						"lexicon-v1")));

		Map<String, Object> created = submitRaw(recommender, task, app, "@seedhunter").expectStatus()
				.isCreated().expectBody(Map.class).returnResult().getResponseBody();
		String submissionId = ((Map<String, Object>) created.get("data")).get("id").toString();

		Map<String, Object> row = db
				.sql("SELECT field, comment_text FROM comment_safety_review"
						+ " WHERE submission_id = CAST(:s AS uuid)")
				.bind("s", submissionId)
				.map(r -> Map.<String, Object>of("field", r.get("field", String.class), "comment_text",
						r.get("comment_text", String.class)))
				.one().block();
		assertThat(row.get("field")).isEqualTo("note");
		assertThat(row.get("comment_text")).isEqualTo("已完成互动");

		// 运营按字段路径复核
		client().post().uri("/api/ops/comment-reviews/{s}/note/review", submissionId)
				.header(H, signWithRole("ops-cs-2", "customer_service")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("decision", "confirmed", "note", "备注措辞可接受")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.field").isEqualTo("note");
	}

	/** 备注上限 500（与 intelligence 端点同限；超长在词库检查前 400）。 */
	@Test
	void overlongSubmissionNoteRejectedByContract() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String task = publishInteractionTask(merchant, org, "like");
		String app = applyAndAccept(recommender, task, merchant, org);
		stubLinkPassed();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contentUrl", "https://www.xiaohongshu.com/post/1");
		body.put("platformHandle", "@seedhunter");
		body.put("note", "长".repeat(501));
		client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
				.header(H, sign(recommender, "recommender")).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
				.exchange().expectStatus().isBadRequest();
	}

	private String publishInteractionTask(String merchant, String org) {
		return publishInteractionTask(merchant, org, "like");
	}

	private String publishInteractionTask(String merchant, String org, String actionType) {
		Map<String, Object> body = interactionBodyWith("https://www.xiaohongshu.com/post/1", actionType);
		body.put("organizationId", org); // 与签发断言同 org，否则 requireScope 403
		Map<String, Object> resp = createTask(merchant, org, body).expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
		String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
		db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
				+ "WHERE id = CAST(:id AS uuid)").bind("id", taskId).then().block();
		return taskId;
	}

	private String applyAndAccept(String recommender, String task, String merchant, String org) {
		Map<String, Object> applied = client().post().uri("/api/tasks/" + task + "/applications")
				.header(H, sign(recommender, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("note", "申请")).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
		String appId = (String) ((Map<String, Object>) applied.get("data")).get("id");
		// 互动任务无资金字段 → 非资金型，accept 直连（无 Saga）
		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/accept")
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isOk();
		return appId;
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec submitRaw(String recommender,
			String task, String app, String platformHandle) {
		return submitRaw(recommender, task, app, platformHandle, null);
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec submitRaw(String recommender,
			String task, String app, String platformHandle, String commentText) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contentUrl", "https://www.xiaohongshu.com/post/1");
		body.put("note", "已完成互动");
		if (platformHandle != null) {
			body.put("platformHandle", platformHandle);
		}
		if (commentText != null) {
			body.put("commentText", commentText);
		}
		return client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
				.header(H, sign(recommender, "recommender")).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
				.exchange();
	}

	private String submit(String recommender, String task, String app, String platformHandle) {
		Map<String, Object> resp = submitRaw(recommender, task, app, platformHandle).expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) resp.get("data")).get("id");
	}

	private void stubLinkPassed() {
		when(linkChecker.check(anyString()))
				.thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "链接可达")));
	}

	/** 轮询该 submission 的最新核验 run checks JSON（提交自动触发核验，异步完成）。 */
	private String awaitRunChecks(String submissionId) {
		long deadline = System.currentTimeMillis() + 10_000L;
		String checks = null;
		while (System.currentTimeMillis() < deadline) {
			checks = db
					.sql("SELECT checks::text AS c FROM engagement_verification_run"
							+ " WHERE submission_id = CAST(:s AS uuid) ORDER BY run_number DESC LIMIT 1")
					.bind("s", submissionId).map(r -> r.get("c", String.class)).one().block();
			if (checks != null) {
				return checks;
			}
			sleep(150L);
		}
		throw new AssertionError("verification run not recorded for " + submissionId);
	}

	private String verificationStatus(String submissionId) {
		long deadline = System.currentTimeMillis() + 10_000L;
		String status = null;
		while (System.currentTimeMillis() < deadline) {
			status = db.sql("SELECT status FROM engagement_verification" + " WHERE submission_id = CAST(:s AS uuid)")
					.bind("s", submissionId).map(r -> r.get("status", String.class)).one().block();
			if (status != null) {
				return status;
			}
			sleep(150L);
		}
		throw new AssertionError("verification not recorded for " + submissionId + " (last=" + status + ")");
	}

	private IntelligenceMediaClient.MediaMetadata mediaMeta(UUID id, String owner, String applicationId) {
		return new IntelligenceMediaClient.MediaMetadata(id, owner, "engagement_attachment", "application",
				applicationId, "active", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				"image/png", 1234L, Instant.parse("2026-12-31T00:00:00Z"));
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
