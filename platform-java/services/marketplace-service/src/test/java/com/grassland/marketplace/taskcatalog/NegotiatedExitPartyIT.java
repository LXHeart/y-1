package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 协商退出「相反业务方」确认回归（审查修复 02 / R05，验收 TC02-05/06）。
 *
 * <p>
 * 确认/拒绝的合法主体必须是与 request.initiatedRole 相反的业务方： 商家发起 → 仅该报名的推荐官本人可响应；推荐官发起 →
 * 仅对该任务/门店具当前管理权限的商家方可响应。 同侧（发起账号本人、同商家其他管理者）、其他推荐官、无资源权限者一律 403； confirmed
 * 后重入（幂等续传）也走同一授权，不得绕过。业务方由服务端资源归属 （app.recommenderAccountId /
 * TaskResourceAuthorization.requireScope）解析，不信任请求体 role。
 */
@SuppressWarnings("unchecked")
class NegotiatedExitPartyIT extends MarketplaceItSupport {

	private static final String H = "X-Grassland-Identity";

	@MockitoBean
	private FinanceEscrowClient financeClient;

	@MockitoBean
	private DisputeChecker disputeChecker;

	@Autowired
	private TaskApplicationRepository apps;

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

	/** 显式把某账号的门店/组织授权置为拒绝（负向矩阵：无资源权限者）。 */
	private void denyStoreAuthorization(String account) {
		when(storeAuthorization.authorize(eq(account), anyString(), any(), anyString()))
				.thenReturn(Mono.error(new com.grassland.marketplace.security.MarketplaceException(403, "无权管理该组织资源")));
	}

	// ---------- TC02-05：同商家两名管理者不能互为「双方」 ----------

	@Test
	void secondManagerOfSameMerchantCannotConfirmMerchantInitiatedExit() {
		String merchant = UUID.randomUUID().toString();
		String otherManager = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org);
		String app = applyAndAccept(rec, task, merchant, org);
		String exit = requestExit(merchant, "merchant", org, task, app).get("exitRequestId").toString();

		client().post().uri(confirmUri(task, app, exit))
				.header(H, sign(otherManager, "merchant", org, "finance_transaction")).exchange().expectStatus()
				.isForbidden();

		assertThat(appStatus(app)).as("同侧确认被拒后合作照常").isEqualTo("accepted");
		assertThat(exitStatus(exit)).isEqualTo("pending");
	}

	@Test
	void sameSideRejectIsAlsoForbidden() {
		String merchant = UUID.randomUUID().toString();
		String otherManager = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org);
		String app = applyAndAccept(rec, task, merchant, org);
		String exit = requestExit(merchant, "merchant", org, task, app).get("exitRequestId").toString();

		client().post().uri(rejectUri(task, app, exit))
				.header(H, sign(otherManager, "merchant", org, "finance_transaction")).exchange().expectStatus()
				.isForbidden();
		assertThat(exitStatus(exit)).isEqualTo("pending");
	}

	/** confirmed 后同侧重入：403（授权先于幂等续传分支，不借续传绕过）。 */
	@Test
	void confirmedReentryDoesNotBypassPartyCheck() {
		String merchant = UUID.randomUUID().toString();
		String otherManager = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org);
		String app = applyAndAccept(rec, task, merchant, org);
		String exit = requestExit(merchant, "merchant", org, task, app).get("exitRequestId").toString();

		// 合法对方（该报名的推荐官本人）确认 → 合作终态化。
		client().post().uri(confirmUri(task, app, exit)).header(H, sign(rec, "recommender")).exchange().expectStatus()
				.isOk();
		assertThat(appStatus(app)).isEqualTo("withdrawn");
		assertThat(exitStatus(exit)).isEqualTo("confirmed");

		// 同商家另一管理者重入（confirm 续传路径）→ 403，不触发第二次结算。
		client().post().uri(confirmUri(task, app, exit))
				.header(H, sign(otherManager, "merchant", org, "finance_transaction")).exchange().expectStatus()
				.isForbidden();
		assertThat(exitEvents(exit)).isEqualTo(2); // Requested + ExitedNegotiated，无第三条
	}

	// ---------- TC02-06：双向发起，对方可确认；跨组织/非本人/其他推荐官拒绝 ----------

	@Test
	void recommenderInitiatedExitCannotBeConfirmedByRecommenderSide() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org);
		String app = applyAndAccept(rec, task, merchant, org);
		String exit = requestExit(rec, "recommender", null, task, app).get("exitRequestId").toString();

		// 推荐官本人自确认 → 403（发起方）。
		client().post().uri(confirmUri(task, app, exit)).header(H, sign(rec, "recommender")).exchange().expectStatus()
				.isForbidden();
		// 其他推荐官 → 403（资源授权显式拒绝：无该任务管理权限）。
		String strangerRec = UUID.randomUUID().toString();
		denyStoreAuthorization(strangerRec);
		client().post().uri(confirmUri(task, app, exit)).header(H, sign(strangerRec, "recommender")).exchange()
				.expectStatus().isForbidden();
		assertThat(exitStatus(exit)).isEqualTo("pending");
	}

	@Test
	void recommenderInitiatedExitConfirmableByLegalMerchantManager() {
		String merchant = UUID.randomUUID().toString();
		String manager = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org);
		String app = applyAndAccept(rec, task, merchant, org);
		String exit = requestExit(rec, "recommender", null, task, app).get("exitRequestId").toString();

		// 同组织任一具管理权限的商家方（manager）= 合法对方。
		client().post().uri(confirmUri(task, app, exit))
				.header(H, sign(manager, "merchant", org, "finance_transaction")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("confirmed");
		assertThat(appStatus(app)).isEqualTo("withdrawn");
		assertThat(exitKind(app)).isEqualTo("negotiated");
	}

	@Test
	void merchantInitiatedExitConfirmableByTheApplicationRecommender() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org);
		String app = applyAndAccept(rec, task, merchant, org);
		String exit = requestExit(merchant, "merchant", org, task, app).get("exitRequestId").toString();

		client().post().uri(confirmUri(task, app, exit)).header(H, sign(rec, "recommender")).exchange().expectStatus()
				.isOk();
		assertThat(appStatus(app)).isEqualTo("withdrawn");
	}

	@Test
	void crossOrganizationManagerIsRejected() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishBountyTask(merchant, org);
		String app = applyAndAccept(rec, task, merchant, org);
		String exit = requestExit(rec, "recommender", null, task, app).get("exitRequestId").toString();

		// 跨组织商家：资源授权显式拒绝（identity 不会给另一 org 的 manager 发本组织 scope）。
		String otherOrgManager = UUID.randomUUID().toString();
		denyStoreAuthorization(otherOrgManager);
		client().post().uri(confirmUri(task, app, exit))
				.header(H, sign(otherOrgManager, "merchant", UUID.randomUUID().toString(), "finance_transaction"))
				.exchange().expectStatus().isForbidden();
		assertThat(appStatus(app)).isEqualTo("accepted");
	}

	// ---------- helpers ----------

	private String confirmUri(String task, String app, String exit) {
		return "/api/tasks/" + task + "/applications/" + app + "/exit-requests/" + exit + "/confirm";
	}

	private String rejectUri(String task, String app, String exit) {
		return "/api/tasks/" + task + "/applications/" + app + "/exit-requests/" + exit + "/reject";
	}

	private Map<String, Object> requestExit(String initiator, String role, String org, String task, String app) {
		String auth = "merchant".equals(role)
				? sign(initiator, "merchant", org, "finance_transaction")
				: sign(initiator, "recommender");
		Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications/" + app + "/exit")
				.header(H, auth).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "negotiated", "reason", "档期冲突希望协商退出合作")).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) resp.get("data");
	}

	private String publishBountyTask(String merchant, String org) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("organizationId", org);
		body.put("title", "业务方确认回归任务");
		body.put("platform", "xiaohongshu");
		body.put("storeId", UUID.randomUUID().toString());
		body.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
		body.put("maxSlots", 1);
		body.put("bountyCents", 500L);
		Map<String, Object> resp = client().post().uri("/api/tasks")
				.header(H, sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
		db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now())"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", taskId).then().block();
		return taskId;
	}

	private String applyAndAccept(String recommender, String task, String merchant, String org) {
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

	private String exitKind(String app) {
		return db.sql("SELECT exit_kind FROM task_application WHERE id = CAST(:id AS uuid)").bind("id", app)
				.map(r -> r.get("exit_kind", String.class)).one().block();
	}

	private String exitStatus(String exit) {
		return db.sql("SELECT status FROM exit_request WHERE id = CAST(:id AS uuid)").bind("id", exit)
				.map(r -> r.get("status", String.class)).one().block();
	}

	private long exitEvents(String exit) {
		return db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox" + " WHERE payload->>'exitRequestId' = :id")
				.bind("id", exit).map(r -> r.get("c", Integer.class)).one().block().longValue();
	}
}
