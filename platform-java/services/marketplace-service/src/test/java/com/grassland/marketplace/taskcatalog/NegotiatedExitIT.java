package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 协商退出状态机 IT（任务书 #97 C97-03 / D97-04/05/07）。
 *
 * <p>
 * TC97-009 发起→确认→里程碑部分结算终态 exit_kind=negotiated；TC97-010 拒绝/超时/撤回后合作照常；
 * TC97-011 终态竞态单边胜出（商家取消先到 → 申请自动 cancelled）；TC97-012 无里程碑零补偿全额释放； TC97-013
 * 开放争议互斥与越权 403；TC97-014 动作契约 exit 组在商家结算视图回显。
 */
@SuppressWarnings("unchecked")
class NegotiatedExitIT extends MarketplaceItSupport {

	private static final String H = "X-Grassland-Identity";

	@MockitoBean
	private FinanceEscrowClient financeClient;

	@MockitoBean
	private DisputeChecker disputeChecker;

	@Autowired
	private EngagementExitExpireDispatcher expireDispatcher;

	@BeforeEach
	void stubFinance() {
		when(financeClient.reserve(anyString(), anyString(), anyLong(), anyString()))
				.thenReturn(Mono.just(ReserveResult.reserved(500L)));
		when(financeClient.captureVerified(anyString(), anyString(), anyLong(), anyString(), anyLong()))
				.thenReturn(Mono.just(FinanceEscrowClient.CaptureOutcome.capturedNow()));
		when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());
		when(financeClient.freebieRefund(anyString(), anyString())).thenReturn(Mono.empty());
		when(disputeChecker.hasOpenDispute(anyString(), anyString())).thenReturn(false);
	}

	// ---------- TC97-009：发起→确认→里程碑部分结算 ----------

	@Test
	void negotiatedExitConfirmSettlesByConfirmedMilestones() {
		String merchant = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org, 2, 500L);
		String app = applyAndAccept(recommender, task, merchant, org, 500L);
		insertConfirmedMilestone(app, recommender, merchant, "deliverable");

		// TC97-014（契约侧）：商家发起 → 商家视图待对方回应（互斥 blockedReason=exit_request_open），
		// 推荐官（对方）视图待退出确认（72h 倒计时=响应窗）。
		Map<String, Object> opened = requestExit(merchant, "merchant", org, task, app, "档期冲突希望协商提前终止合作");
		assertThat(opened.get("status")).isEqualTo("pending");
		Map<String, Object> merchantView = settlementBody(merchant, task, app);
		assertThat(merchantView.get("nextActionGroup")).isEqualTo("exit_await_response");
		assertThat(merchantView.get("blockedReason")).isEqualTo("exit_request_open");
		Map<String, Object> recommenderView = settlementContract(recommender, app);
		assertThat(recommenderView.get("nextActionGroup")).isEqualTo("exit_pending_confirm");

		// 推荐官确认（对方）→ capture 里程碑金额 + release 余款 + 终态 + 名额回收 + 金额回填里程碑行。
		client().post()
				.uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests/" + opened.get("exitRequestId")
						+ "/confirm")
				.header(H, sign(recommender, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("confirmed");

		Map<String, Object> row = appRow(app);
		assertThat(row.get("status")).isEqualTo("withdrawn");
		assertThat(row.get("exit_kind")).isEqualTo("negotiated");
		// deliverable=6000bps → 500×0.6=300 给推荐官，余款 200 释放返商家（默认取消条款）。
		verify(financeClient).captureVerified(eq(org), eq(app), eq(500L), eq(recommender), eq(300L));
		verify(financeClient).release(eq(org), eq(app));
		assertThat(milestoneAmount(app)).isEqualTo(300L);
		// 名额回收：协商退出终态后 occupiedSlots 归零（maxSlots=2）。
		assertThat(remainingSlots(merchant, org, task)).isEqualTo(2);
	}

	// ---------- TC97-010：拒绝 / 超时 / 撤回后合作照常 ----------

	@Test
	void rejectTimeoutAndCancelKeepEngagementRunning() {
		String merchant = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org, 1, 500L);
		String app = applyAndAccept(recommender, task, merchant, org, 500L);

		// 拒绝：申请关闭、合作继续。
		Map<String, Object> first = requestExit(recommender, "recommender", null, task, app, "时间排不开");
		client().post()
				.uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests/" + first.get("exitRequestId")
						+ "/reject")
				.header(H, sign(merchant, "merchant", org, "finance_transaction")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("rejected");
		assertThat(appRow(app).get("status")).isEqualTo("accepted");

		// 超时：响应窗已过 → dispatcher 扫描置 expired；此后确认 409，合作照常。
		Map<String, Object> second = requestExit(recommender, "recommender", null, task, app, "希望再次尝试协商退出");
		db.sql("UPDATE exit_request SET respond_deadline_at = now() - interval '1 second'"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", second.get("exitRequestId")).then().block();
		expireDispatcher.dispatch();
		assertThat(exitRow((String) second.get("exitRequestId")).get("status")).isEqualTo("expired");
		assertThat(appRow(app).get("status")).isEqualTo("accepted");
		client().post()
				.uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests/" + second.get("exitRequestId")
						+ "/confirm")
				.header(H, sign(merchant, "merchant", org, "finance_transaction")).exchange().expectStatus()
				.isEqualTo(409);

		// 撤回：发起方 pending 可撤，合作继续。
		Map<String, Object> third = requestExit(recommender, "recommender", null, task, app, "再试一次协商");
		client().post()
				.uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests/" + third.get("exitRequestId")
						+ "/cancel")
				.header(H, sign(recommender, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("cancelled");
		assertThat(appRow(app).get("status")).isEqualTo("accepted");
	}

	// ---------- TC97-011：终态竞态单边胜出（商家取消先到 → 申请自动 cancelled） ----------

	@Test
	void merchantCancelTerminalPrecedesOpenExitRequest() {
		String merchant = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org, 2, 500L);
		String app = applyAndAccept(recommender, task, merchant, org, 500L);

		Map<String, Object> opened = requestExit(recommender, "recommender", null, task, app, "协商退出中希望提前终止");
		assertThat(opened.get("status")).isEqualTo("pending");

		// 商家取消任务：无里程碑 → 全额退 + 报名 refunded；开放申请自动 cancelled（D97-05）。
		Integer taskVersion = db.sql("SELECT version FROM task WHERE id = CAST(:id AS uuid)").bind("id", task)
				.map(r -> r.get("version", Integer.class)).one().block();
		client().post().uri("/api/tasks/" + task + "/cancel")
				.header(H, sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", taskVersion)).exchange()
				.expectStatus().isOk();
		Map<String, Object> row = appRow(app);
		assertThat(row.get("status")).isEqualTo("refunded");
		assertThat(exitRow((String) opened.get("exitRequestId")).get("status")).isEqualTo("cancelled");
	}

	// ---------- TC97-012：无里程碑协商退出 = 零补偿全额释放 ----------

	@Test
	void negotiatedExitWithoutMilestonesReleasesInFull() {
		String merchant = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org, 1, 500L);
		String app = applyAndAccept(recommender, task, merchant, org, 500L);

		// 列表预演：无已确认里程碑 → totalCents=0（服务端算，前端不复算）。
		Map<String, Object> opened = requestExit(merchant, "merchant", org, task, app, "内容方向调整，协商终止");
		List<Map<String, Object>> list = exitList(recommender, task, app);
		assertThat(list.get(0).get("status")).isEqualTo("pending");
		Map<String, Object> preview = (Map<String, Object>) list.get(0).get("settlementPreview");
		assertThat(preview.get("totalCents")).isEqualTo(0);

		client().post().uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests/"
				+ opened.get("exitRequestId") + "/confirm").header(H, sign(recommender, "recommender")).exchange()
				.expectStatus().isOk();
		Map<String, Object> row = appRow(app);
		assertThat(row.get("status")).isEqualTo("withdrawn");
		assertThat(row.get("exit_kind")).isEqualTo("negotiated");
		verify(financeClient, never()).captureVerified(anyString(), anyString(), anyLong(), anyString(), anyLong());
		verify(financeClient).release(eq(org), eq(app));
	}

	// ---------- TC97-013：开放争议互斥 + 越权 403 ----------

	@Test
	void openDisputeBlocksRequestAndOnlyCounterpartyMayRespond() {
		String merchant = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org, 1, 500L);
		String app = applyAndAccept(recommender, task, merchant, org, 500L);

		// 开放争议（DisputeChecker 真 bean 语义）→ 发起被拒，先走争议。
		when(disputeChecker.hasOpenDispute(org, app)).thenReturn(true);
		client().post().uri("/api/tasks/" + task + "/applications/" + app + "/exit")
				.header(H, sign(recommender, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "negotiated", "reason", "档期冲突希望协商退出合作")).exchange().expectStatus()
				.isEqualTo(409).expectBody().jsonPath("$.error")
				.value(msg -> assertThat(String.valueOf(msg)).contains("争议"));

		// 无争议后正常发起；发起方自确认（双方确认制）→ 403；无关第三方 → 403
		// （IT 基座 authorize 默认放行，第三方单独覆写为拒绝——真实部署语义）。
		when(disputeChecker.hasOpenDispute(org, app)).thenReturn(false);
		Map<String, Object> opened = requestExit(recommender, "recommender", null, task, app, "档期冲突无法继续履约");
		client().post()
				.uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests/" + opened.get("exitRequestId")
						+ "/confirm")
				.header(H, sign(recommender, "recommender")).exchange().expectStatus().isForbidden();
		String stranger = UUID.randomUUID().toString();
		when(storeAuthorization.authorize(eq(stranger), anyString(), any(), anyString()))
				.thenReturn(Mono.error(new com.grassland.marketplace.security.MarketplaceException(403, "无权管理该组织资源")));
		client().post().uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests/"
				+ opened.get("exitRequestId") + "/confirm").header(H, sign(stranger, "recommender")).exchange()
				.expectStatus().isForbidden();
		// 撤回仅发起方：他人 403。
		client().post()
				.uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests/" + opened.get("exitRequestId")
						+ "/cancel")
				.header(H, sign(merchant, "merchant", org, "finance_transaction")).exchange().expectStatus()
				.isForbidden();
	}

	// ---------- helpers ----------

	private Map<String, Object> requestExit(String initiator, String role, String org, String task, String app,
			String reason) {
		String auth = "merchant".equals(role)
				? sign(initiator, "merchant", org, "finance_transaction")
				: sign(initiator, "recommender");
		Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications/" + app + "/exit")
				.header(H, auth).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "negotiated", "reason", reason)).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) resp.get("data");
	}

	private List<Map<String, Object>> exitList(String viewer, String task, String app) {
		Map<String, Object> resp = client().get().uri("/api/tasks/" + task + "/applications/" + app + "/exit-requests")
				.header(H, sign(viewer, "recommender")).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		return (List<Map<String, Object>>) resp.get("data");
	}

	/** 推荐官（本人报名）视角的结算契约视图。 */
	private Map<String, Object> settlementContract(String recommender, String app) {
		Map<String, Object> resp = client().get().uri("/api/applications/" + app + "/settlement")
				.header(H, sign(recommender, "recommender")).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		return (Map<String, Object>) resp.get("data");
	}

	/** C96-06 结算契约视图（GET /api/applications/{id}/settlement）：商家视角（manager）。 */
	private Map<String, Object> settlementBody(String merchant, String task, String app) {
		Map<String, Object> resp = client().get().uri("/api/applications/" + app + "/settlement")
				.header(H, sign(merchant, "merchant")).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody();
		return (Map<String, Object>) resp.get("data");
	}

	private Map<String, Object> appRow(String app) {
		return db.sql("SELECT status::text, exit_kind::text FROM task_application WHERE id = CAST(:id AS uuid)")
				.bind("id", app).map(r -> Map.<String, Object>of("status", r.get("status", String.class), "exit_kind",
						String.valueOf(r.get("exit_kind", String.class))))
				.one().block();
	}

	private Map<String, Object> exitRow(String exitId) {
		return db.sql("SELECT status::text FROM exit_request WHERE id = CAST(:id AS uuid)").bind("id", exitId)
				.map(r -> Map.<String, Object>of("status", r.get("status", String.class))).one().block();
	}

	private Long milestoneAmount(String app) {
		return db
				.sql("SELECT amount_cents FROM engagement_milestone WHERE application_id = CAST(:app AS uuid)"
						+ " AND confirmed_at IS NOT NULL")
				.bind("app", app).map(r -> r.get("amount_cents", Long.class)).one().block();
	}

	private Integer remainingSlots(String merchant, String org, String task) {
		Map<String, Object> resp = client().get().uri("/api/tasks?organizationId=" + org)
				.header(H, sign(merchant, "merchant", org, "finance_transaction")).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
		return (Integer) data.stream().filter(t -> task.equals(t.get("id"))).findFirst()
				.map(t -> ((Map<String, Object>) t.get("progress"))).map(p -> p.get("remainingSlots")).orElse(null);
	}

	private void insertConfirmedMilestone(String app, String recommender, String merchant, String kind) {
		db.sql("""
				INSERT INTO engagement_milestone(id, application_id, kind, version, proposed_by, confirmed_by, confirmed_at)
				VALUES (CAST(:id AS uuid), CAST(:app AS uuid), :kind, 1,
				        CAST(:proposed AS uuid), CAST(:confirmed AS uuid), now())
				""")
				.bind("id", UUID.randomUUID().toString()).bind("app", app).bind("kind", kind)
				.bind("proposed", recommender).bind("confirmed", merchant).then().block();
	}

	private String publishBountyTask(String merchant, String org, Integer maxSlots, long bountyCents) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("organizationId", org);
		body.put("title", "赏金任务");
		body.put("platform", "xiaohongshu");
		body.put("storeId", UUID.randomUUID().toString());
		body.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
		body.put("maxSlots", maxSlots);
		body.put("bountyCents", bountyCents);
		Map<String, Object> resp = client().post().uri("/api/tasks")
				.header(H, sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
		db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now())"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", taskId).then().block();
		return taskId;
	}

	private String applyAndAccept(String recommender, String task, String merchant, String org, long bounty) {
		Map<String, Object> applied = client().post().uri("/api/tasks/" + task + "/applications")
				.header(H, sign(recommender, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("note", "申请")).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
		String app = (String) ((Map<String, Object>) applied.get("data")).get("id");
		client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
				.header(H, sign(merchant, "merchant", org, "finance_transaction")).exchange().expectStatus()
				.isAccepted();
		long deadline = System.currentTimeMillis() + 10_000L;
		while (System.currentTimeMillis() < deadline) {
			String status = appStatus(app);
			if ("accepted".equals(status)) {
				return app;
			}
			if (status != null && !"reserving".equals(status)) {
				throw new AssertionError("acceptance did not reach accepted (last=" + status + ")");
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("acceptance did not reach accepted in time");
	}

	private String appStatus(String app) {
		return db.sql("SELECT status FROM task_application WHERE id = CAST(:id AS uuid)").bind("id", app)
				.map(r -> r.get("status", String.class)).one().block();
	}
}
