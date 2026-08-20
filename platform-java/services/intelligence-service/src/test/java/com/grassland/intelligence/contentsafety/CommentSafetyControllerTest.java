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

/** {@link CommentSafetyController} 内部词库审核端点单测（缺口清偿之九）。 */
class CommentSafetyControllerTest {

    private final IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);
    private final ContentSafetyService safety = mock(ContentSafetyService.class);

    private WebTestClient client() {
        return WebTestClient.bindToController(new CommentSafetyController(callers, safety)).build();
    }

    @Test
    void missingAssertionIsRejected() {
        when(callers.requireServicePrincipal(any(), any()))
                .thenReturn(Mono.error(new com.grassland.intelligence.security.IntelligenceException(401, "未登录")));
        client().post().uri("/internal/content-safety/comment-check")
                .bodyValue(Map.of("text", "正常评论"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void highSeverityFindingBlocksSubmissionForMarketplacePrincipal() {
        when(callers.requireServicePrincipal(any(), any()))
                .thenReturn(Mono.just(callerForMarketplace()));
        when(safety.checkShallow("违规评论"))
                .thenReturn(new SafetyReport(List.of(
                        new SafetyReport.Finding("illegal", "high", "违禁", 0, "", false)),
                        "lexicon-v1", false, List.of()));

        client().post().uri("/internal/content-safety/comment-check")
                .bodyValue(Map.of("text", "违规评论"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.blocked").isEqualTo(true)
                .jsonPath("$.data.findings").isEqualTo(1)
                .jsonPath("$.data.lexiconVersion").isEqualTo("lexicon-v1");
    }

    @Test
    void lowSeverityFindingsDoNotBlock() {
        when(callers.requireServicePrincipal(any(), any()))
                .thenReturn(Mono.just(callerForMarketplace()));
        when(safety.checkShallow("轻微问题评论"))
                .thenReturn(new SafetyReport(List.of(
                        new SafetyReport.Finding("absolute_claims", "low", "最好", 0, "", false)),
                        "lexicon-v1", false, List.of()));

        client().post().uri("/internal/content-safety/comment-check")
                .bodyValue(Map.of("text", "轻微问题评论"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.blocked").isEqualTo(false)
                .jsonPath("$.data.findings").isEqualTo(1);
    }

    @Test
    void blankOrOverlongTextIsRejected() {
        when(callers.requireServicePrincipal(any(), any()))
                .thenReturn(Mono.just(callerForMarketplace()));

        client().post().uri("/internal/content-safety/comment-check")
                .bodyValue(Map.of("text", "  "))
                .exchange().expectStatus().isBadRequest();
        client().post().uri("/internal/content-safety/comment-check")
                .bodyValue(Map.of("text", "长".repeat(501)))
                .exchange().expectStatus().isBadRequest();
    }

    private static IntelligenceCallerResolver.Caller callerForMarketplace() {
        // 服务 principal 调用方（callerKind=service + principal=marketplace）
        return new IntelligenceCallerResolver.Caller(
                "service:marketplace", null, null, null, null, "service", "marketplace", null);
    }

    /** 断言辅助：data 非空。 */
    @Test
    void envelopeShape() {
        when(callers.requireServicePrincipal(any(), any()))
                .thenReturn(Mono.just(callerForMarketplace()));
        when(safety.checkShallow(any())).thenReturn(SafetyReport.emptyShallow());

        Map<String, Object> body = client().post().uri("/internal/content-safety/comment-check")
                .bodyValue(Map.of("text", "干净评论"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).containsEntry("success", true);
        assertThat(body.get("data")).isNotNull();
    }
}
