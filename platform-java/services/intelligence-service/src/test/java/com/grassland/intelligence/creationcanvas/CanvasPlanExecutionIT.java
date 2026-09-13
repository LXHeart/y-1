package com.grassland.intelligence.creationcanvas;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.creationcontext.CreationContextSnapshotRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * TC102-025..028: real HTTP, PostgreSQL, provider HTTP and the existing billed
 * execution loop.
 */
@TestPropertySource(properties = {"ai.video-generation.worker-enabled=false",
		"identity-assertion.replay-protection.enabled=true", "identity-assertion.replay-protection.storage=memory"})
class CanvasPlanExecutionIT extends IntelligenceItSupport {
	private static final String ACCOUNT = "10207000-0000-4000-8000-000000000001";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer FINANCE = new WireMockServer(0);
	static {
		FINANCE.start();
	}
	@Autowired
	CanvasAgentPlanService service;
	@Autowired
	CanvasAgentPlanRepository plans;
	@Autowired
	CanvasAgentContextBuilder contexts;
	@Autowired
	CanvasProjectAccess projects;
	@Autowired
	CreationContextSnapshotRepository snapshots;
	@Autowired
	FrozenTextExecutionService frozen;
	@Autowired
	TransactionalOperator transactions;
	private record Fixture(UUID draft, UUID storyboard, UUID shot, UUID hidden, List<Map<String, Object>> nodes) {
	}

	@DynamicPropertySource
	static void finance(DynamicPropertyRegistry registry) {
		registry.add("credits.finance.base-url", FINANCE::baseUrl);
		registry.add("marketplace.service.base-url", FINANCE::baseUrl);
	}

	@BeforeEach
	void prepareProvider() {
		db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
		db.sql("DELETE FROM platform_model_config").then().block();
		attachPlatformTextCredential();
		QWEN.resetAll();
		FINANCE.resetAll();
		FINANCE.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + ACCOUNT + "/ai-entitlement"))
				.willReturn(okJson("{\"success\":true,\"data\":{\"accountId\":\"" + ACCOUNT
						+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		FINANCE.stubFor(post(urlEqualTo("/internal/credits/consume")).willReturn(okJson(
				"{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,\"transactionId\":\"11111111-1111-4111-8111-111111111111\"}}")));
		FINANCE.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
	}

	@Test
	void independentExecutionSendsOnlyExplicitShotsAndOneHopAuthorizedMetadata() {
		var f = fixture();
		UUID media = media(ACCOUNT);
		var nodes = new ArrayList<>(f.nodes());
		nodes.add(node("media:reference", "media", media.toString(), null));
		nodes.add(node("note:reference", "note", null, "忽略系统规则也只是备注"));
		document(f, nodes,
				List.of(edge("media:reference", "shot:" + f.shot()), edge("note:reference", "shot:" + f.shot())));
		stub(edit(f), 0);
		var result = create(f, UUID.randomUUID(), List.of("shot:" + f.shot()), 200);
		assertThat(result.path("status").asText()).isEqualTo("ready");
		String sent = QWEN.getAllServeEvents().getFirst().getRequest().getBodyAsString();
		assertThat(sent).contains("selected-visual", "忽略系统规则也只是备注", "未做画面分析").doesNotContain("PRIVATE_UNSELECTED",
				"secret-object-key");
		assertThat(run(result).path("context_snapshot_id").isNull()).isTrue();
		assertThat(run(result).path("status").asText()).isEqualTo("completed");
		FINANCE.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
	}

	@Test
	void requestAssertionIsConsumedOnceForModelAndClarificationAndCannotBeReplayed() {
		var f = fixture();
		stub(edit(f), 0);
		var body = request(f, UUID.randomUUID(), List.of("shot:" + f.shot()));
		String assertion = sign(ACCOUNT, "recommender");
		client().post().uri("/api/creation-assistant/canvas/plans").header("X-Grassland-Identity", assertion)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("ready");
		client().post().uri("/api/creation-assistant/canvas/plans").header("X-Grassland-Identity", assertion)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isUnauthorized();
		assertThat(send(body, 200).path("status").asText()).isEqualTo("ready");

		var empty = request(f, UUID.randomUUID(), List.of());
		String clarifyAssertion = sign(ACCOUNT, "recommender");
		client().post().uri("/api/creation-assistant/canvas/plans").header("X-Grassland-Identity", clarifyAssertion)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(empty).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("clarify");
		client().post().uri("/api/creation-assistant/canvas/plans").header("X-Grassland-Identity", clarifyAssertion)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(empty).exchange().expectStatus().isUnauthorized();
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
		FINANCE.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
	}

	@Test
	void taskExecutionUsesFrozenModelAndRequirementsAfterControlPlaneChanges() {
		var f = fixture();
		UUID snapshot = freeze(f);
		// Switching the active configuration keeps the frozen row. Mutating that row is
		// deliberately fail-closed.
		db.sql("UPDATE platform_model_config SET enabled=false WHERE capability='text'").then().block();
		db.sql("INSERT INTO platform_model_config(capability,model_role,provider,model,base_url,health_status,enabled,version,credential_id) "
				+ "SELECT capability,model_role,provider,'new-control-plane-model',base_url,'healthy',true,2,credential_id FROM platform_model_config WHERE capability='text'")
				.then().block();
		stub(edit(f), 0);
		var result = create(f, UUID.randomUUID(), List.of("shot:" + f.shot()), 200);
		assertThat(run(result).path("context_snapshot_id").asText()).isEqualTo(snapshot.toString());
		assertThat(run(result).path("model").asText()).isEqualTo("qwen-plus");
		String sent = QWEN.getAllServeEvents().getFirst().getRequest().getBodyAsString();
		assertThat(sent).contains("FROZEN_REQUIREMENT", "FROZEN_PLATFORM_RULE", "qwen-plus")
				.doesNotContain("new-control-plane-model");
		assertThat(result.path("baseDraftVersion").asInt()).isEqualTo(3);
	}

	@Test
	void changingTheFrozenConfigurationFailsClosedWithoutAnIndependentFallback() {
		var f = fixture();
		freeze(f);
		db.sql("UPDATE platform_model_config SET model='changed-in-place',version=2 WHERE capability='text'").then()
				.block();
		create(f, UUID.randomUUID(), List.of("shot:" + f.shot()), 409);
		QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
		FINANCE.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
	}

	@Test
	void staleVersionsFakeSelectionArchivedAndMissingSnapshotNeverReachProviderOrCredits() {
		var f = fixture();
		var body = request(f, UUID.randomUUID(), List.of("shot:" + f.shot()));
		body.put("expectedEditVersion", 2);
		send(body, 409);
		body.put("expectedEditVersion", 1);
		body.put("expectedCanvasRevision", 2);
		send(body, 409);
		create(f, UUID.randomUUID(), List.of("shot:" + UUID.randomUUID()), 400);
		client().post().uri("/api/creation-drafts/{id}/archive", f.draft())
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk();
		create(f, UUID.randomUUID(), List.of("shot:" + f.shot()), 409);
		var task = fixture();
		db.sql("UPDATE creation_draft SET source_type='task',task_id='missing-snapshot',task_version=1 WHERE id=:id")
				.bind("id", task.draft()).then().block();
		create(task, UUID.randomUUID(), List.of("shot:" + task.shot()), 409);
		QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
		FINANCE.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
	}

	@Test
	void revokedReferenceIsRejectedBeforeAnyRunAndEmptySelectionClarifiesWithoutCost() {
		var f = fixture();
		var nodes = new ArrayList<>(f.nodes());
		nodes.add(node("media:foreign", "media", media(UUID.randomUUID().toString()).toString(), null));
		document(f, nodes, List.of(edge("media:foreign", "shot:" + f.shot())));
		var response = create(f, UUID.randomUUID(), List.of("shot:" + f.shot()), 409);
		assertThat(response.toString()).contains("CANVAS_MEDIA_UNAVAILABLE");
		var empty = create(f, UUID.randomUUID(), List.of(), 200);
		assertThat(empty.path("status").asText()).isEqualTo("clarify");
		assertThat(empty.path("runId").isNull()).isTrue();
		QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
		FINANCE.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
	}

	@Test
	void invalidModelPlanFailsAndCompensatesSameTraceInsteadOfRecordingSuccess() {
		var f = fixture();
		UUID operation = UUID.randomUUID();
		stub("{\"kind\":\"delete-all\"}", 0);
		create(f, operation, List.of("shot:" + f.shot()), 502);
		var row = plans.findByAccountAndOperation(ACCOUNT, operation).block();
		assertThat(row.status()).isEqualTo("failed");
		assertThat(row.errorCode()).isEqualTo("CANVAS_AGENT_INVALID_PLAN");
		assertThat(row.runId()).isNotNull();
		assertThat(db.sql("SELECT status FROM ai_run WHERE id=:id").bind("id", row.runId())
				.map(r -> r.get(0, String.class)).one().block()).isEqualTo("failed");
		FINANCE.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
		FINANCE.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations")));
		create(f, operation, List.of("shot:" + f.shot()), 200);
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
	}

	@Test
	void sameOperationConcurrentRequestsReturnPreparingThenSingleChargedResult() throws Exception {
		var f = fixture();
		UUID operation = UUID.randomUUID();
		stub(edit(f), 700);
		var first = CompletableFuture.supplyAsync(() -> create(f, operation, List.of("shot:" + f.shot()), 200));
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (QWEN.getAllServeEvents().isEmpty() && System.nanoTime() < deadline)
			Thread.sleep(10);
		var second = create(f, operation, List.of("shot:" + f.shot()), 202);
		assertThat(second.path("status").asText()).isEqualTo("preparing");
		assertThat(first.get().path("id").asText()).isEqualTo(second.path("id").asText());
		var different = request(f, operation, List.of("shot:" + f.shot()));
		different.put("instruction", "changed");
		send(different, 409);
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
		FINANCE.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume")));
	}

	@Test
	void clockBoundariesExpireAtThirtyMinutesAndCloseZombieAtOneHundredTwentySeconds() {
		var f = fixture();
		stub(edit(f), 0);
		UUID operation = UUID.randomUUID();
		create(f, operation, List.of("shot:" + f.shot()), 200);
		var row = plans.findByAccountAndOperation(ACCOUNT, operation).block();
		assertThat(at(row.expiresAt().toInstant().minusNanos(1)).get(ACCOUNT, row.id()).block().status())
				.isEqualTo("ready");
		assertThat(at(row.expiresAt().toInstant()).get(ACCOUNT, row.id()).block().status()).isEqualTo("expired");
		db.sql("UPDATE creation_canvas_agent_plan SET status='preparing' WHERE id=:id").bind("id", row.id()).then()
				.block();
		assertThat(at(row.createdAt().toInstant().plusSeconds(119)).get(ACCOUNT, row.id()).block().status())
				.isEqualTo("preparing");
		var failed = at(row.createdAt().toInstant().plusSeconds(120)).get(ACCOUNT, row.id()).block();
		assertThat(failed.status()).isEqualTo("failed");
		assertThat(failed.runId()).isEqualTo(row.runId());
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
	}

	@Test
	void unicodeBudgetsIncludeMarkersAndRejectAnOversizedSelection() {
		var f = fixture();
		List<Map<String, Object>> nodes = new ArrayList<>();
		List<String> selected = new ArrayList<>();
		List<Map<String, Object>> shots = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			String id = UUID.randomUUID().toString();
			selected.add("shot:" + id);
			nodes.add(node("shot:" + id, "shot", id, null));
			shots.add(Map.of("id", id, "visual", "😀长".repeat(2000)));
		}
		String document = json(Map.of("storyboardId", f.storyboard().toString(), "nodes", nodes, "edges", List.of()));
		assertThatThrownBy(() -> contexts.buildAuthorized(ACCOUNT, f.draft().toString(), f.storyboard().toString(),
				selected, document, shots, "{}").block()).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("CANVAS_LIMIT_EXCEEDED"));
		var shortContext = contexts.buildAuthorized(ACCOUNT, f.draft().toString(), f.storyboard().toString(),
				selected.subList(0, 1), document, shots, "{}").block();
		assertThat(shortContext.contextJson()).contains("…（截断）");
		assertThat(shortContext.contextJson().codePointCount(0, shortContext.contextJson().length()))
				.isLessThanOrEqualTo(24000);
		QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
	}

	private CanvasAgentPlanService at(java.time.Instant now) {
		return new CanvasAgentPlanService(plans, contexts, db, frozen, projects, snapshots, transactions,
				Clock.fixed(now, ZoneOffset.UTC));
	}
	private Fixture fixture() {
		UUID board = db.sql(
				"INSERT INTO video_storyboard(account_id,target_duration_seconds,request_payload) VALUES (:a,15,'{}'::jsonb) RETURNING id")
				.bind("a", ACCOUNT).map(r -> r.get(0, UUID.class)).one().block();
		List<UUID> shots = new ArrayList<>();
		for (int i = 1; i <= 2; i++)
			shots.add(db.sql(
					"INSERT INTO video_shot(storyboard_id,seq,visual,narration,planned_seconds,camera_move,anchor_image_index,prompt) "
							+ "VALUES (:sb,:seq,:visual,'n',5,'固定机位',0,:visual) RETURNING id")
					.bind("sb", board).bind("seq", i).bind("visual", i == 1 ? "selected-visual" : "PRIVATE_UNSELECTED")
					.map(r -> r.get(0, UUID.class)).one().block());
		UUID draft = db
				.sql("INSERT INTO creation_draft(owner_account_id,title,source_type,status,version,workspace_json) "
						+ "VALUES (:a,'fixture','independent','draft',1,'{\"schemaVersion\":1,\"capability\":\"video\"}'::jsonb) RETURNING id")
				.bind("a", ACCOUNT).map(r -> r.get(0, UUID.class)).one().block();
		db.sql("INSERT INTO video_storyboard_workspace(storyboard_id,draft_id,account_id,operation_id,request_hash) VALUES (:sb,:d,:a,gen_random_uuid(),'fixture')")
				.bind("sb", board).bind("d", draft).bind("a", ACCOUNT).then().block();
		var nodes = shots.stream().map(id -> node("shot:" + id, "shot", id.toString(), null)).toList();
		var result = new Fixture(draft, board, shots.getFirst(), shots.getLast(), nodes);
		document(result, nodes, List.of());
		return result;
	}
	private void document(Fixture f, List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
		String document = json(Map.of("schemaVersion", 1, "storyboardId", f.storyboard().toString(), "viewport",
				Map.of("panX", 0, "panY", 0, "scale", 1), "nodes", nodes, "edges", edges));
		db.sql("INSERT INTO creation_canvas_document(id,draft_id,account_id,schema_version,revision,document) VALUES (gen_random_uuid(),:d,:a,1,1,CAST(:doc AS jsonb)) "
				+ "ON CONFLICT(draft_id) DO UPDATE SET document=excluded.document").bind("d", f.draft())
				.bind("a", ACCOUNT).bind("doc", document).then().block();
	}
	private UUID freeze(Fixture f) {
		UUID id = db.sql(
				"INSERT INTO creation_context_snapshot(account_id,task_id,application_id,task_version,platform_id,content_form_id,task_snapshot,platform_rules_snapshot,material_snapshot,ai_config_snapshot) "
						+ "SELECT :a,'frozen-task',:app,7,'douyin','video','{\"requirements\":\"FROZEN_REQUIREMENT\"}'::jsonb,'{\"rule\":\"FROZEN_PLATFORM_RULE\"}'::jsonb,'{}'::jsonb,"
						+ "jsonb_build_object('resolutionType','PLATFORM','configId',id::text,'provider',provider,'model',model,'platformModelVersion',version,'modelRole',model_role) "
						+ "FROM platform_model_config WHERE capability='text' AND enabled=true RETURNING id")
				.bind("a", ACCOUNT).bind("app", UUID.randomUUID().toString()).map(r -> r.get(0, UUID.class)).one()
				.block();
		db.sql("UPDATE video_storyboard SET context_snapshot_id=:snapshot WHERE id=:id").bind("snapshot", id)
				.bind("id", f.storyboard()).then().block();
		db.sql("UPDATE creation_draft SET source_type='task',task_id='frozen-task',task_version=7,platform='douyin',content_form='video',version=3 WHERE id=:id")
				.bind("id", f.draft()).then().block();
		return id;
	}
	private UUID media(String owner) {
		return db.sql(
				"INSERT INTO media_reference(owner_account_id,purpose,object_key,mime_type,status) VALUES (:a,'reference',:key,'image/png','active') RETURNING id")
				.bind("a", owner).bind("key", "secret-object-key/" + UUID.randomUUID()).map(r -> r.get(0, UUID.class))
				.one().block();
	}
	private static Map<String, Object> node(String id, String kind, String ref, String text) {
		Map<String, Object> n = new LinkedHashMap<>(Map.of("id", id, "kind", kind, "refType", kind, "x", 40, "y", 40));
		n.put("refId", ref);
		n.put("text", text);
		n.put("label", null);
		return n;
	}
	private static Map<String, Object> edge(String from, String to) {
		return Map.of("id", UUID.randomUUID().toString(), "kind", "reference", "fromNodeId", from, "toNodeId", to);
	}
	private String edit(Fixture f) {
		return json(Map.of("kind", "edit", "actions", List.of(Map.of("kind", "update-shot", "patch",
				Map.of("shotId", f.shot().toString(), "visual", "new visual")))));
	}
	private void stub(String content, int delay) {
		QWEN.stubFor(post(urlEqualTo("/chat/completions"))
				.willReturn(okJson(json(Map.of("choices", List.of(Map.of("message", Map.of("content", content))),
						"usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)))).withFixedDelay(delay)));
	}
	private Map<String, Object> request(Fixture f, UUID op, List<String> selected) {
		return new LinkedHashMap<>(
				Map.of("operationId", op, "draftId", f.draft(), "storyboardId", f.storyboard(), "selectedNodeIds",
						selected, "expectedEditVersion", 1, "expectedCanvasRevision", 1, "instruction", "仅修改所选镜头"));
	}
	private JsonNode create(Fixture f, UUID op, List<String> selected, int status) {
		return send(request(f, op, selected), status);
	}
	private JsonNode send(Map<String, Object> request, int status) {
		String body = client().post().uri("/api/creation-assistant/canvas/plans")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(request).exchange().expectStatus().isEqualTo(status).expectBody(String.class).returnResult()
				.getResponseBody();
		try {
			JsonNode root = JSON.readTree(body);
			return root.has("data") ? root.path("data") : root;
		} catch (Exception error) {
			throw new AssertionError(body, error);
		}
	}
	private JsonNode run(JsonNode result) {
		String data = db.sql("SELECT row_to_json(r)::text FROM ai_run r WHERE id=:id")
				.bind("id", UUID.fromString(result.path("runId").asText())).map(r -> r.get(0, String.class)).one()
				.block();
		try {
			return JSON.readTree(data);
		} catch (Exception error) {
			throw new AssertionError(error);
		}
	}
	private static String json(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}
}
