package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #101 C101-04（API101-05～07、V78）：改编建议一次调用、差异预览与原子应用。 TC101-015～019。模型经
 * WireMock（/chat/completions 合成 JSON）。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class TextProposalIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000010a";
	private static final String ACCOUNT_B = "00000000-0000-4000-8000-00000000010b";
	private static final com.github.tomakehurst.wiremock.WireMockServer FINANCE = new com.github.tomakehurst.wiremock.WireMockServer(
			0);
	static {
		FINANCE.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void finance(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("credits.finance.base-url", FINANCE::baseUrl);
		registry.add("marketplace.service.base-url", FINANCE::baseUrl);
	}

	private String draftId;
	private int draftVersion;

	@BeforeEach
	void seedDraftAndClean() {
		FINANCE.resetAll();
		FINANCE.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + ACCOUNT + "/ai-entitlement")).willReturn(
				okJson("{\"success\":true,\"data\":{\"accountId\":\"00000000-0000-4000-8000-00000000010a\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		FINANCE.stubFor(post(urlEqualTo("/internal/credits/consume")).willReturn(okJson(
				"{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,\"transactionId\":\"11111111-1111-4111-8111-111111111111\"}}")));
		FINANCE.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
		db.sql("DELETE FROM creation_text_proposal").then().then(db.sql("DELETE FROM creation_source_document").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :account").bind("account", ACCOUNT)
						.then())
				.block(java.time.Duration.ofSeconds(10));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "建议 IT 草稿");
		body.put("platform", "wechat-official");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content", "第一段：门店三年，人均 68 元。\n\n第二段：招牌面 32 元，日销两百碗。");
		Map<String, Object> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		draftId = ((Map<?, ?>) response.get("data")).get("id").toString();
		draftVersion = (Integer) ((Map<?, ?>) response.get("data")).get("version");
		attachPlatformTextCredential();
		QWEN.resetAll();
	}

	private void stubModel(String jsonBody) {
		// content 必须是 JSON 字符串（模型返回的文本），先做字符串转义。
		String escaped;
		try {
			escaped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jsonBody);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
		String response = "{\"choices\":[{\"message\":{\"content\":" + escaped
				+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
		QWEN.stubFor(post(urlEqualTo("/chat/completions"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(response)));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> prepareProposal(String account, String action, int expectedVersion) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", expectedVersion);
		body.put("action", action);
		Map<String, Object> response = client().post().uri("/api/creation-studio/text-proposals")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	/** TC101-015：同建议请求并发／断线重放——一次模型调用、一份建议、run 可追踪。 */
	@Test
	void sameRequestReplaysWithoutSecondModelCall() {
		stubModel("{\"body\":\"改编后的正文\",\"changes\":[\"保留事实，调整结构\"],\"title\":null,\"summary\":null}");
		String requestId = UUID.randomUUID().toString();
		Map<String, Object> body = Map.of("requestId", requestId, "draftId", draftId, "expectedDraftVersion",
				draftVersion, "action", "adapt-body");
		var first = client().post().uri("/api/creation-studio/text-proposals")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		var replay = client().post().uri("/api/creation-studio/text-proposals")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		String firstId = ((Map<?, ?>) first.get("data")).get("id").toString();
		assertThat(((Map<?, ?>) replay.get("data")).get("id").toString()).isEqualTo(firstId);
		assertThat(((Map<?, ?>) replay.get("data")).get("status")).isEqualTo("ready");
		// 一次模型调用
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
		assertThat(((Map<?, ?>) replay.get("data")).get("runId")).asString().isNotBlank();
		// 同键异参 409
		client().post().uri("/api/creation-studio/text-proposals").header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", requestId, "draftId", draftId, "expectedDraftVersion", draftVersion,
						"action", "adapt-body", "instructions", "不同的指示"))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
	}

	/** TC101-016/017：应用一次原子写入 + 同建议重复应用返回已应用版本，不追加版本。 */
	@Test
	@SuppressWarnings("unchecked")
	void applyIsAtomicAndReplayDoesNotAppendVersion() {
		stubModel("{\"body\":\"改编后的正文 v2\",\"changes\":[\"结构重排\"],\"title\":\"新标题\",\"summary\":null}");
		Map<String, Object> proposal = prepareProposal(ACCOUNT, "adapt-body", draftVersion);
		String proposalId = proposal.get("id").toString();

		var applied = client().post().uri("/api/creation-studio/text-proposals/" + proposalId + "/apply")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedDraftVersion", draftVersion,
						"fields", List.of("body", "title")))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> data = (Map<String, Object>) applied.get("data");
		assertThat(data.get("alreadyApplied")).isEqualTo(false);
		int appliedVersion = (Integer) data.get("appliedVersion");
		assertThat(appliedVersion).isGreaterThan(draftVersion);
		assertThat(((Map<String, Object>) data.get("project")).get("content")).isEqualTo("改编后的正文 v2");
		assertThat(((Map<String, Object>) data.get("project")).get("articleTitle")).isEqualTo("新标题");

		// 同建议再应用：返回已应用版本，不追加
		var replay = client().post().uri("/api/creation-studio/text-proposals/" + proposalId + "/apply")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedDraftVersion", appliedVersion,
						"fields", List.of("body")))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> replayData = (Map<String, Object>) replay.get("data");
		assertThat(replayData.get("alreadyApplied")).isEqualTo(true);
		assertThat(replayData.get("appliedVersion")).isEqualTo(appliedVersion);

		// 版本历史保留旧稿
		Long versions = db.sql("SELECT count(*) FROM creation_draft_version WHERE draft_id = CAST(:id AS uuid)")
				.bind("id", draftId).map(row -> row.get(0, Long.class)).one().block(java.time.Duration.ofSeconds(10));
		assertThat(versions).isGreaterThanOrEqualTo(1);
	}

	/** TC101-018：suggest-metadata 只选 summary 应用——正文与标题不变。 */
	@Test
	@SuppressWarnings("unchecked")
	void metadataApplyOnlyTouchesSummary() {
		stubModel("{\"title\":\"候选标题\",\"summary\":\"候选摘要\",\"body\":null,\"changes\":[]}");
		Map<String, Object> proposal = prepareProposal(ACCOUNT, "suggest-metadata", draftVersion);
		var applied = client().post().uri("/api/creation-studio/text-proposals/" + proposal.get("id") + "/apply")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedDraftVersion", draftVersion,
						"fields", List.of("summary")))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> project = (Map<String, Object>) ((Map<String, Object>) applied.get("data")).get("project");
		assertThat(project.get("articleTitle")).isNull();
		assertThat((String) project.get("content")).contains("人均 68 元");
		Map<String, Object> workspace = (Map<String, Object>) project.get("workspace");
		Map<String, Object> delivery = (Map<String, Object>) workspace.get("delivery");
		assertThat(delivery.get("summary")).isEqualTo("候选摘要");
	}

	/** TC101-019：模型编造未请求字段 → 建议失败，不可应用。 */
	@Test
	@SuppressWarnings("unchecked")
	void fabricatedFieldsFailTheProposal() {
		stubModel("{\"title\":\"t\",\"summary\":\"s\",\"body\":\"不该出现的正文\",\"changes\":[]}");
		Map<String, Object> proposal = prepareProposal(ACCOUNT, "suggest-metadata", draftVersion);
		assertThat(proposal.get("status")).isEqualTo("failed");
		client().post().uri("/api/creation-studio/text-proposals/" + proposal.get("id") + "/apply")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedDraftVersion", draftVersion,
						"fields", List.of("summary")))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
	}

	/** 版本过期（expectedDraftVersion ≠ 当前）→ 409；跨账号 404。 */
	@Test
	void versionAndOwnerBoundaries() {
		stubModel("{\"body\":\"b\",\"changes\":[],\"title\":null,\"summary\":null}");
		client().post().uri("/api/creation-studio/text-proposals").header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						draftVersion + 5, "action", "adapt-body"))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
		// 跨账号读他人建议 404
		Map<String, Object> proposal = prepareProposal(ACCOUNT, "adapt-body", draftVersion);
		client().get().uri("/api/creation-studio/text-proposals/" + proposal.get("id"))
				.header("X-Grassland-Identity", sign(ACCOUNT_B, null)).exchange().expectStatus().isNotFound();
	}
}
