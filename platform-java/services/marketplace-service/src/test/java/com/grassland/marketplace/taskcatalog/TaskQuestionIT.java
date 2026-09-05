package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 任务书 #62 卡7：任务目标问题（P4 拍板）。
 *
 * <p>
 * 覆盖：知乎任务可携带并回显/冻进 {@code task_version}；报名 accept 时 questionText 进
 * {@code task_context_snapshot}（V51 重建触发器白名单）；非知乎携带 422；无问题的任务
 * <b>响应与快照都不新增键</b>（零回归红线，全局约束 1）。
 *
 * <p>
 * <b>零外呼</b>：{@code questionRef} 只是本地正则提取的 questionId 存档，服务端对知乎不发任何 请求（#62
 * §3.7）。本 IT 不 stub 任何知乎上游，正是因为压根没有上游。
 */
@SuppressWarnings("unchecked")
class TaskQuestionIT extends MarketplaceItSupport {

	private static final String QUESTION = "为什么大厂都在弃用 Kubernetes？";
	private static final String QUESTION_REF = "1999041081275355787";

	@DynamicPropertySource
	static void deterministicFullReview(DynamicPropertyRegistry registry) {
		// 与 TaskControllerIT 同口径：抽样免审会让部分商家创建即上架，快照用例需要确定性人工 approve。
		registry.add("marketplace.task-review.policy-enabled", () -> "false");
	}

	@Test
	void zhihuTaskCarriesQuestionThroughResponseAndVersionSnapshot() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();

		Map<String, Object> body = body(org, "知乎回答任务", "zhihu");
		body.put("questionText", QUESTION);
		body.put("questionRef", QUESTION_REF);
		Map<String, Object> task = created(merchant, org, body);

		assertThat(task.get("questionText")).isEqualTo(QUESTION);
		assertThat(task.get("questionRef")).isEqualTo(QUESTION_REF);

		// task_version 只在 approve 上架时 appendVersion——创建即断言会拿到空表。
		String taskId = approve(task);
		Map<String, Object> frozen = db
				.sql("SELECT question_text, question_ref FROM task_version WHERE task_id=CAST(:id AS uuid)"
						+ " ORDER BY version DESC LIMIT 1")
				.bind("id", taskId)
				.map(row -> Map.<String, Object>of("text", String.valueOf(row.get("question_text", String.class)),
						"ref", String.valueOf(row.get("question_ref", String.class))))
				.one().block();
		assertThat(frozen).containsEntry("text", QUESTION).containsEntry("ref", QUESTION_REF);
	}

	/**
	 * accept 时触发器把 questionText 冻进 task_context_snapshot（intelligence 生成侧的权威来源）。
	 */
	@Test
	void acceptedApplicationFreezesQuestionIntoTaskContextSnapshot() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> body = body(org, "知乎回答任务-快照", "zhihu");
		body.put("questionText", QUESTION);
		body.put("questionRef", QUESTION_REF);
		String taskId = approve(created(merchant, org, body));

		String snapshot = acceptApplicationAndReadSnapshot(taskId);
		assertThat(snapshot).contains("\"questionText\"").contains(QUESTION).contains(QUESTION_REF);
	}

	/** 零回归：不带问题的任务，响应无键、快照 JSON 里也不出现 questionText（不是 null 值）。 */
	@Test
	void taskWithoutQuestionOmitsFieldsEverywhere() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> task = created(merchant, org, body(org, "知乎文章任务", "zhihu"));

		assertThat(task).doesNotContainKey("questionText").doesNotContainKey("questionRef");

		String snapshot = acceptApplicationAndReadSnapshot(approve(task));
		assertThat(snapshot).doesNotContain("questionText").doesNotContain("questionRef");
	}

	/** 非知乎平台携带目标问题 → 422（well-formed 但语义不可处理），不静默丢弃。 */
	@Test
	void nonZhihuPlatformWithQuestionIsUnprocessable() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> body = body(org, "小红书任务", "xiaohongshu");
		body.put("questionText", QUESTION);

		client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isEqualTo(422)
				.expectBody().jsonPath("$.success").isEqualTo(false).jsonPath("$.error").isEqualTo("目标问题仅支持知乎平台任务");
	}

	/** 只有 questionRef 没有 questionText 不算「携带问题」（无原文无法生成回答），非知乎也放行。 */
	@Test
	void bareQuestionRefWithoutTextIsNotTreatedAsQuestion() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> body = body(org, "小红书任务-裸 ref", "xiaohongshu");
		body.put("questionRef", QUESTION_REF);

		client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated();
	}

	/** 目标问题引用必须是知乎 questionId（纯数字）——挡住把整条链接塞进 ref 的调用方。 */
	@Test
	void nonNumericQuestionRefIsRejected() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> body = body(org, "知乎任务-坏 ref", "zhihu");
		body.put("questionText", QUESTION);
		body.put("questionRef", "https://www.zhihu.com/question/1999041081275355787");

		client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest();
	}

	// ---------- helpers ----------

	private static Map<String, Object> body(String org, String title, String platform) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("organizationId", org);
		m.put("title", title);
		// 任务书 #77 卡 B（D2）三字段必填造数
		m.put("platform", platform);
		m.put("storeId", UUID.randomUUID().toString());
		m.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
		return m;
	}

	private Map<String, Object> created(String merchant, String org, Map<String, Object> body) {
		Map<String, Object> response = client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private String approve(Map<String, Object> task) {
		String taskId = (String) task.get("id");
		client().post().uri("/api/admin/tasks/" + taskId + "/review/approve")
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "platform_admin"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", ((Number) task.get("version")).intValue())).exchange()
				.expectStatus().isOk();
		return taskId;
	}

	/**
	 * 直接改 status 触发 {@code freeze_application_task_context()}（与
	 * InternalCreationContextControllerIT 同姿态——本用例断言的是触发器白名单，不是 accept 端点的业务闸）。
	 */
	private String acceptApplicationAndReadSnapshot(String taskId) {
		String appId = UUID.randomUUID().toString();
		db.sql("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents)"
				+ " VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), 'pending', 0)")
				.bind("id", appId).bind("task", taskId).bind("rec", UUID.randomUUID().toString()).then().block();
		db.sql("UPDATE task_application SET status='accepted', decided_at=now(),"
				+ " reputation_level_at_accept=1, reputation_policy_version_at_accept=1,"
				+ " settlement_delay_days_at_accept=2, commission_bonus_bps_at_accept=0,"
				+ " premium_support_at_accept=false WHERE id=CAST(:id AS uuid)").bind("id", appId).then().block();
		return db.sql("SELECT task_context_snapshot::text FROM task_application WHERE id=CAST(:id AS uuid)")
				.bind("id", appId).map(row -> row.get(0, String.class)).one().block();
	}
}
