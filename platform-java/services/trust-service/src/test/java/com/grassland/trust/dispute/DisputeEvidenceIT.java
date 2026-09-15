package com.grassland.trust.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.trust.TrustItSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 争议证据域集成测试（GL-P2-TRUST-001 Stage 1 / T1+T2；任务书 #103 C103-12 补齐写侧守卫）。
 *
 * <p>
 * 覆盖：开争议带证据（点亮 evidence_ref + 落库 + outbox DisputeEvidenceSubmitted）、 POST
 * /evidence 追加（当事方鉴权）、GET /evidence 始终脱敏（raw 不出响应）、
 * 客服经审判快照查看证据时写证据访问审计（D-10）、证据项长度/枚举校验边界（10,000/500， 无半写）、质证期结束后的
 * answer/rebuttal/done 409、旁观者 answer/rebuttal 403。
 */
class DisputeEvidenceIT extends TrustItSupport {

	@Test
	@DisplayName("开争议带证据：点亮 evidence_ref + 落 dispute_evidence + 发 DisputeEvidenceSubmitted")
	void openWithEvidenceLightsEvidenceRefAndEmitsOutbox() {
		String merchant = UUID.randomUUID().toString();
		String org = MARKETPLACE_ORG;
		String eng = UUID.randomUUID().toString();

		String disputeId = openWithEvidence(merchant, org, eng,
				List.of(Map.of("kind", "text", "contentRef", "对方未履约，联系13812345678", "caption", "情况说明"),
						Map.of("kind", "screenshot", "contentRef", "media-xyz", "caption", "聊天截图")));

		// evidence_ref 死字段被点亮
		String evidenceRef = db.sql("SELECT evidence_ref FROM dispute_case WHERE id = CAST(:id AS uuid)")
				.bind("id", disputeId).map(r -> r.get("evidence_ref", String.class)).one().block();
		assertThat(evidenceRef).isEqualTo("set:" + disputeId);

		// 两条证据落库
		long count = evidenceCount(disputeId);
		assertThat(count).isEqualTo(2);

		String redactedSnapshot = db
				.sql("SELECT redacted_ref FROM dispute_evidence"
						+ " WHERE dispute_id = CAST(:id AS uuid) AND kind = 'text'")
				.bind("id", disputeId).map(row -> row.get("redacted_ref", String.class)).one().block();
		assertThat(redactedSnapshot).contains("138****5678").doesNotContain("13812345678");

		// 两条 DisputeEvidenceSubmitted 事件（每条证据一个，确定性 eventId 幂等）
		assertThat(outboxCount("DisputeEvidenceSubmitted", disputeId)).isEqualTo(2);
	}

	@Test
	@DisplayName("POST /evidence 追加证据：当事方 201 / 非当事方 403")
	void submitEndpointAppendsEvidence() {
		String merchant = UUID.randomUUID().toString();
		String org = MARKETPLACE_ORG;
		String eng = UUID.randomUUID().toString();
		String disputeId = open(merchant, org, eng);

		// 当事商家追加 → 201
		client().post().uri("/api/trust/disputes/" + disputeId + "/evidence")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("items", List.of(Map.of("kind", "text", "contentRef", "补充证据")))).exchange()
				.expectStatus().isCreated().expectBody().jsonPath("$.data.submitted").isEqualTo(1);
		assertThat(evidenceCount(disputeId)).isEqualTo(1);

		// 非当事方（别的组织商家）→ 403
		client().post().uri("/api/trust/disputes/" + disputeId + "/evidence")
				.header("X-Grassland-Identity",
						sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("items", List.of(Map.of("kind", "text", "contentRef", "越权")))).exchange()
				.expectStatus().isForbidden();
		assertThat(evidenceCount(disputeId)).isEqualTo(1); // 未增长
	}

	@Test
	@DisplayName("GET /evidence 始终脱敏：手机号掩码、截图只回句柄、raw 不出响应")
	void listEvidenceReturnsRedactedNeverRaw() {
		String merchant = UUID.randomUUID().toString();
		String org = MARKETPLACE_ORG;
		String eng = UUID.randomUUID().toString();
		String disputeId = openWithEvidence(merchant, org, eng,
				List.of(Map.of("kind", "text", "contentRef", "联系13812345678", "caption", "t"),
						Map.of("kind", "screenshot", "contentRef", "media-raw-bytes-id", "caption", "s")));

		String body = client().get().uri("/api/trust/disputes/" + disputeId + "/evidence")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish")).exchange()
				.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains("138****5678"); // 手机号掩码
		assertThat(body).doesNotContain("13812345678"); // raw 手机号不出现
		assertThat(body).contains("media:");
		assertThat(body).doesNotContain("media-raw-bytes-id"); // 截图句柄不可反推出原 media id
	}

	@Test
	@DisplayName("客服经审判快照查看证据：返回脱敏证据 + 写证据访问审计")
	void csAdjudicationSnapshotIncludesEvidenceAndAuditsAccess() {
		String merchant = UUID.randomUUID().toString();
		String org = MARKETPLACE_ORG;
		String eng = UUID.randomUUID().toString();
		String disputeId = openWithEvidence(merchant, org, eng,
				List.of(Map.of("kind", "text", "contentRef", "证据13900001111")));

		// 客服读审判快照（含证据）
		client().get().uri("/api/trust/disputes/" + disputeId + "/adjudication")
				.header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now())).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.evidence[0].content")
				.value(c -> assertThat((String) c).contains("139****1111")).jsonPath("$.data.evidence[0].content")
				.value(c -> assertThat((String) c).doesNotContain("13900001111"));

		// 证据访问审计：客服查看 → 每条证据一条 audit
		long auditCount = db
				.sql("SELECT COUNT(*)::int AS c FROM dispute_evidence_access_audit"
						+ " WHERE dispute_id = CAST(:id AS uuid) AND viewer_role = 'customer_service'")
				.bind("id", disputeId).map(r -> r.get("c", Integer.class)).one().block();
		assertThat(auditCount).isEqualTo(1);
	}

	@Test
	@DisplayName("证据访问审计查询：客服可组合筛选，普通用户越权，参数有界")
	void evidenceAccessAuditCanBeQueriedByAuthorizedOperators() {
		String merchant = UUID.randomUUID().toString();
		String disputeId = openWithEvidence(merchant, MARKETPLACE_ORG, UUID.randomUUID().toString(),
				List.of(Map.of("kind", "text", "contentRef", "访问审计查询样本")));
		String viewer = UUID.randomUUID().toString();

		client().get().uri("/api/trust/disputes/" + disputeId + "/adjudication")
				.header("X-Grassland-Identity", signCs(viewer, Instant.now())).exchange().expectStatus().isOk();

		String evidenceId = db
				.sql("SELECT evidence_id::text FROM dispute_evidence_access_audit"
						+ " WHERE dispute_id = CAST(:id AS uuid) AND viewer_account_id = CAST(:viewer AS uuid)")
				.bind("id", disputeId).bind("viewer", viewer).map(row -> row.get(0, String.class)).one().block();

		client().get()
				.uri(builder -> builder.path("/api/admin/trust/evidence-access-audits")
						.queryParam("disputeId", disputeId).queryParam("evidenceId", evidenceId)
						.queryParam("viewerAccountId", viewer).queryParam("viewerRole", "customer_service")
						.queryParam("from", "2020-01-01T00:00:00Z").queryParam("to", "2099-01-01T00:00:00Z")
						.queryParam("limit", "20").build())
				.header("X-Grassland-Identity", signCs(viewer, Instant.now())).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.length()").isEqualTo(1).jsonPath("$.data[0].disputeId")
				.isEqualTo(disputeId).jsonPath("$.data[0].evidenceId").isEqualTo(evidenceId)
				.jsonPath("$.data[0].viewerAccountId").isEqualTo(viewer).jsonPath("$.data[0].purpose")
				.isEqualTo("adjudication").jsonPath("$.meta.limit").isEqualTo(20);

		client().get().uri("/api/admin/trust/evidence-access-audits?disputeId=" + disputeId)
				.header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange()
				.expectStatus().isForbidden();
		client().get().uri("/api/admin/trust/evidence-access-audits?disputeId=bad-id")
				.header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange()
				.expectStatus().isForbidden();
		client().get().uri("/api/admin/trust/evidence-access-audits?limit=201")
				.header("X-Grassland-Identity", signCs(viewer, Instant.now())).exchange().expectStatus().isBadRequest();
		client().get().uri("/api/admin/trust/evidence-access-audits?disputeId=bad-id")
				.header("X-Grassland-Identity", signCs(viewer, Instant.now())).exchange().expectStatus().isBadRequest();
	}

	@Test
	@DisplayName("证据项校验边界：contentRef 10000 过/10001 与空 400、caption 500 过/501 400、非法 kind 400，无半写")
	void evidenceItemValidationBoundariesEnforcedWithoutPartialWrites() {
		String merchant = UUID.randomUUID().toString();
		String disputeId = open(merchant, MARKETPLACE_ORG, UUID.randomUUID().toString());

		// 上限内（10,000 / 500）→ 201
		client().post().uri("/api/trust/disputes/" + disputeId + "/evidence")
				.header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("items",
						List.of(Map.of("kind", "text", "contentRef", "x".repeat(10_000), "caption", "c".repeat(500)))))
				.exchange().expectStatus().isCreated();
		assertThat(evidenceCount(disputeId)).isEqualTo(1);

		// contentRef 超限 / 空白、caption 超限、非法 kind → 400，且不落任何半写
		List<Map<String, Object>> badPayloads = List.of(Map.of("kind", "text", "contentRef", "x".repeat(10_001)),
				Map.of("kind", "text", "contentRef", "   "),
				Map.of("kind", "text", "contentRef", "合法正文", "caption", "c".repeat(501)),
				Map.of("kind", "audio", "contentRef", "合法正文"));
		for (Map<String, Object> item : badPayloads) {
			client().post().uri("/api/trust/disputes/" + disputeId + "/evidence")
					.header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("items", List.of(item))).exchange()
					.expectStatus().isBadRequest();
		}
		assertThat(evidenceCount(disputeId)).isEqualTo(1); // 只有第一次成功写入
	}

	@Test
	@DisplayName("质证期结束后：answer/rebuttal/evidence-done 全部 409，无新证据")
	void evidenceWritesRejectedAfterEvidenceWindowClosed() {
		String merchant = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String eng = UUID.randomUUID().toString();
		String disputeId = open(merchant, MARKETPLACE_ORG, eng);

		// 窗口内被诉方答辩 → 201
		client().post().uri("/api/trust/disputes/" + disputeId + "/evidence")
				.header("X-Grassland-Identity", sign(recommender, "recommender", null, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("phase", "answer", "items", List.of(Map.of("kind", "text", "contentRef", "窗口内答辩"))))
				.exchange().expectStatus().isCreated();

		// 案件进入评审（质证期结束；状态机口径——到期开庭由 workflow Timer 驱动）
		// → answer(重复)/rebuttal/done 全 409
		db.sql("UPDATE dispute_case SET status = 'voting' WHERE id = CAST(:id AS uuid)").bind("id", disputeId).then()
				.block();
		client().post().uri("/api/trust/disputes/" + disputeId + "/evidence")
				.header("X-Grassland-Identity", sign(recommender, "recommender", null, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("phase", "answer", "items", List.of(Map.of("kind", "text", "contentRef", "过期后答辩"))))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.error")
				.isEqualTo("举证质证期已结束，无法再提交答辩");
		client().post().uri("/api/trust/disputes/" + disputeId + "/evidence")
				.header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("phase", "rebuttal", "items", List.of(Map.of("kind", "text", "contentRef", "过期后补充"))))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.error")
				.isEqualTo("举证质证期已结束，无法再补充证据");
		client().post().uri("/api/trust/disputes/" + disputeId + "/evidence-done")
				.header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange()
				.expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("该争议当前不在举证质证期");
		assertThat(evidenceCount(disputeId)).isEqualTo(1);
	}

	@Test
	@DisplayName("旁观者（非当事方）answer/rebuttal → 403，原始证据不越权落库")
	void bystanderCannotSubmitAnswerOrRebuttal() {
		String merchant = UUID.randomUUID().toString();
		String eng = UUID.randomUUID().toString();
		String disputeId = open(merchant, MARKETPLACE_ORG, eng);
		String stranger = UUID.randomUUID().toString();
		denyAuthorization();

		for (String phase : List.of("answer", "rebuttal")) {
			client().post().uri("/api/trust/disputes/" + disputeId + "/evidence")
					.header("X-Grassland-Identity", sign(stranger, "recommender", null, "basic_publish"))
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("phase", phase, "items", List.of(Map.of("kind", "text", "contentRef", "旁观者内容"))))
					.exchange().expectStatus().isForbidden();
		}
		assertThat(evidenceCount(disputeId)).isZero();
	}

	// ---------- helpers ----------

	@SuppressWarnings("unchecked")
	private String open(String merchant, String org, String eng) {
		Map<String, Object> resp = client().post().uri("/api/trust/disputes")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng)).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) resp.get("data")).get("id");
	}

	@SuppressWarnings("unchecked")
	private String openWithEvidence(String merchant, String org, String eng, List<Map<String, Object>> evidence) {
		Map<String, Object> resp = client().post().uri("/api/trust/disputes")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng, "evidence", evidence))
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) resp.get("data")).get("id");
	}

	private long evidenceCount(String disputeId) {
		return db.sql("SELECT COUNT(*)::int AS c FROM dispute_evidence WHERE dispute_id = CAST(:id AS uuid)")
				.bind("id", disputeId).map(r -> r.get("c", Integer.class)).one().block().longValue();
	}

	private long outboxCount(String eventType, String disputeId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM trust_outbox"
						+ " WHERE event_type = :et AND payload->>'disputeId' = :id")
				.bind("et", eventType).bind("id", disputeId).map(r -> r.get("c", Integer.class)).one().block()
				.longValue();
	}

	/** 签客服断言（role=customer_service，按平台角色判定，见 TrustCallerResolver 注释）。 */
	private String signCs(String accountId, Instant reauthenticatedAt) {
		Instant now = Instant.now();
		return userSigner("edge-bff", "grassland-trust").sign(new IdentityAssertion(accountId, null, "sid-" + accountId,
				null, null, "cookie-session", "level2", reauthenticatedAt, "r", "t", "grassland-trust", now,
				now.plusSeconds(60), null, null, "customer_service"));
	}
}
