package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** {@link SubmissionSafetyController} 履约提交硬门槛内部端点单测（ADR-D16 D6 登记项落地）。 */
class SubmissionSafetyControllerTest {

	private final IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);
	private final ContentSafetyService safety = mock(ContentSafetyService.class);

	private WebTestClient client() {
		return WebTestClient.bindToController(new SubmissionSafetyController(callers, safety)).build();
	}

	@Test
	void missingAssertionIsRejected() {
		when(callers.requireServicePrincipal(any(), any()))
				.thenReturn(Mono.error(new com.grassland.intelligence.security.IntelligenceException(401, "未登录")));
		client().post().uri("/internal/content-safety/submission-check")
				.bodyValue(Map.of("note", "正常备注"))
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void highSeverityNoteBlocksWhileCleanCommentPasses() {
		when(callers.requireServicePrincipal(any(), any()))
				.thenReturn(Mono.just(callerForMarketplace()));
		when(safety.checkShallow("正常评论")).thenReturn(SafetyReport.emptyShallow());
		when(safety.checkShallow("违规备注")).thenReturn(new SafetyReport(List.of(
				new SafetyReport.Finding("illegal", "high", "违禁", 0, "", false)),
				"lexicon-v1", false, List.of()));

		client().post().uri("/internal/content-safety/submission-check")
				.bodyValue(Map.of("commentText", "正常评论", "note", "违规备注"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.fields.comment.blocked").isEqualTo(false)
				.jsonPath("$.data.fields.note.blocked").isEqualTo(true)
				.jsonPath("$.data.lexiconVersion").isEqualTo("lexicon-v1");
	}

	@Test
	void advisoryDetailsExcludeMatchedTermAndHighHits() {
		when(callers.requireServicePrincipal(any(), any()))
				.thenReturn(Mono.just(callerForMarketplace()));
		when(safety.checkShallow("全网最好喝，加我微信")).thenReturn(new SafetyReport(List.of(
				new SafetyReport.Finding("absolute_claims", "medium", "最好", 0, "广告法极限词", false),
				new SafetyReport.Finding("diversion", "low", "微信", 5, "疑似导流", false)),
				"lexicon-v1", false, List.of()));

		client().post().uri("/internal/content-safety/submission-check")
				.bodyValue(Map.of("note", "全网最好喝，加我微信"))
				.exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.fields.note.blocked").isEqualTo(false)
				.jsonPath("$.data.fields.note.findings").isEqualTo(2)
				.jsonPath("$.data.fields.note.details[0].category").isEqualTo("absolute_claims")
				.jsonPath("$.data.fields.note.details[0].severity").isEqualTo("medium")
				.jsonPath("$.data.fields.note.details[0].match").doesNotExist()
				.jsonPath("$.data.fields.note.details[1].category").isEqualTo("diversion");
	}

	@Test
	void blankOnlyOrOverlongFieldsAreRejected() {
		when(callers.requireServicePrincipal(any(), any()))
				.thenReturn(Mono.just(callerForMarketplace()));

		client().post().uri("/internal/content-safety/submission-check")
				.bodyValue(Map.of("note", "  "))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/internal/content-safety/submission-check")
				.bodyValue(Map.of("commentText", "长".repeat(501)))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/internal/content-safety/submission-check")
				.bodyValue(Map.of())
				.exchange().expectStatus().isBadRequest();
	}

	@Test
	void envelopeShape() {
		when(callers.requireServicePrincipal(any(), any()))
				.thenReturn(Mono.just(callerForMarketplace()));
		when(safety.checkShallow(any())).thenReturn(SafetyReport.emptyShallow());

		Map<String, Object> body = client().post().uri("/internal/content-safety/submission-check")
				.bodyValue(Map.of("note", "干净备注"))
				.exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		assertThat(body).containsEntry("success", true);
		assertThat(body.get("data")).isNotNull();
	}

	private static IntelligenceCallerResolver.Caller callerForMarketplace() {
		// 服务 principal 调用方（callerKind=service + principal=marketplace）
		return new IntelligenceCallerResolver.Caller(
				"service:marketplace", null, null, null, null, "service", "marketplace", null);
	}
}
