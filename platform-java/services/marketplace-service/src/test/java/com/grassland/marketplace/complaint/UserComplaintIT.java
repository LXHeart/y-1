package com.grassland.marketplace.complaint;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 用户举报/投诉工单端到端（PRD §11.8 / V49）：提交契约与防重复 → 我的投诉 → 客服队列与处置
 * （办结必填结论、可重判）；鉴权（无断言 401、非客服 403）。
 */
class UserComplaintIT extends MarketplaceItSupport {

	private static final String H = "X-Grassland-Identity";

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM user_complaint").then().block();
	}

	@Test
	@DisplayName("提交 → 201；同对象同原因未办结 → 409；不同原因可再提交")
	void submitDeduplicatesOpenComplaints() {
		String reporter = UUID.randomUUID().toString();

		client().post().uri("/api/complaints").header(H, sign(reporter, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "task", "targetId", "task-1", "reason", "spam",
						"description", "任务涉嫌刷单"))
				.exchange().expectStatus().isCreated()
				.expectBody().jsonPath("$.data.status").isEqualTo("open");

		client().post().uri("/api/complaints").header(H, sign(reporter, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "task", "targetId", "task-1", "reason", "spam",
						"description", "再次举报同一问题"))
				.exchange().expectStatus().isEqualTo(409);

		client().post().uri("/api/complaints").header(H, sign(reporter, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "task", "targetId", "task-1", "reason", "fraud",
						"description", "补充诈骗线索"))
				.exchange().expectStatus().isCreated();
	}

	@Test
	@DisplayName("契约校验：非法类型/原因/空描述/超长 → 400；无断言 → 401")
	void contractValidation() {
		client().post().uri("/api/complaints").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "task", "reason", "spam", "description", "x"))
				.exchange().expectStatus().isUnauthorized();

		String reporter = sign(UUID.randomUUID().toString(), "recommender");
		client().post().uri("/api/complaints").header(H, reporter)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "unknown", "reason", "spam", "description", "描述"))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/api/complaints").header(H, reporter)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "task", "reason", "weird", "description", "描述"))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/api/complaints").header(H, reporter)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "task", "reason", "spam", "description", "   "))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/api/complaints").header(H, reporter)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "task", "reason", "spam", "description", "长".repeat(501)))
				.exchange().expectStatus().isBadRequest();
	}

	@Test
	@SuppressWarnings("unchecked")
	@DisplayName("客服处置全流程：队列可见 → 受理 → 办结必填结论 → 我的投诉回带结论；可重判")
	void opsHandlingLifecycle() {
		String reporter = UUID.randomUUID().toString();
		Map<String, Object> created = client().post().uri("/api/complaints")
				.header(H, sign(reporter, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "content", "targetId", "post-9", "reason",
						"inappropriate_content", "description", "内容含违规表达"))
				.exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		String complaintId = ((Map<String, Object>) created.get("data")).get("id").toString();

		// 普通用户不可进处置台；客服可见队列
		client().get().uri("/api/ops/complaints").header(H, sign(reporter, "recommender"))
				.exchange().expectStatus().isForbidden();
		client().get().uri("/api/ops/complaints").header(H, signWithRole("ops-cs", "customer_service"))
				.exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.items.length()").isEqualTo(1)
				.jsonPath("$.data.items[0].reporterAccountId").isEqualTo(reporter);

		// 受理中
		client().post().uri("/api/ops/complaints/{id}/handle", complaintId)
				.header(H, signWithRole("ops-cs", "customer_service"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("action", "processing"))
				.exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("processing");

		// 办结缺结论 → 400
		client().post().uri("/api/ops/complaints/{id}/handle", complaintId)
				.header(H, signWithRole("ops-cs", "customer_service"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("action", "resolved"))
				.exchange().expectStatus().isBadRequest();

		// 办结带结论 → 我的投诉回带
		client().post().uri("/api/ops/complaints/{id}/handle", complaintId)
				.header(H, signWithRole("ops-cs", "customer_service"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("action", "resolved", "note", "已核实并下架相关内容"))
				.exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("resolved");

		Map<String, Object> mine = client().get().uri("/api/complaints/mine")
				.header(H, sign(reporter, "recommender"))
				.exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		var items = (java.util.List<Map<String, Object>>) ((Map<String, Object>) mine.get("data")).get("items");
		assertThat(items).hasSize(1);
		assertThat(items.getFirst().get("status")).isEqualTo("resolved");
		assertThat(items.getFirst().get("resolutionNote")).isEqualTo("已核实并下架相关内容");

		// 办结后可再举报（防重只拦未办结）
		client().post().uri("/api/complaints").header(H, sign(reporter, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("targetType", "content", "targetId", "post-9", "reason",
						"inappropriate_content", "description", "同对象再次违规"))
				.exchange().expectStatus().isCreated();
	}
}
