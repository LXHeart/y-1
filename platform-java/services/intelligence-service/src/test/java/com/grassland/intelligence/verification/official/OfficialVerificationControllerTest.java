package com.grassland.intelligence.verification.official;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import org.springframework.test.web.reactive.server.WebTestClient;

/** {@link OfficialVerificationController} 内部端点单测（P1 骨架，ADR-D04）。 */
class OfficialVerificationControllerTest {

	private final IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);

	@Test
	void missingAssertionIsRejected() {
		when(callers.requireServicePrincipal(any(), any()))
				.thenReturn(Mono.error(new com.grassland.intelligence.security.IntelligenceException(401, "未登录")));
		client(null).post().uri("/internal/verification/official-data")
				.bodyValue(Map.of("platform", "douyin", "contentUrl", "https://www.douyin.com/x")).exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	void unconfiguredGatewayReportsConfiguredFalse() {
		when(callers.requireServicePrincipal(any(), any())).thenReturn(Mono.just(marketplaceCaller()));

		client(null).post().uri("/internal/verification/official-data")
				.bodyValue(Map.of("platform", "douyin", "contentUrl", "https://www.douyin.com/x")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.configured").isEqualTo(false);
	}

	@Test
	void gatewayDataIsPassedThroughWithMetrics() {
		when(callers.requireServicePrincipal(any(), any())).thenReturn(Mono.just(marketplaceCaller()));
		OfficialVerificationGateway gateway = mock(OfficialVerificationGateway.class);
		when(gateway.fetch("douyin", "https://www.douyin.com/x", "@seed", "好评")).thenReturn(Mono.just(
				new OfficialVerificationGateway.OfficialData(true, true, true, Map.of("likes", 120L, "comments", 3L))));

		client(gateway).post().uri("/internal/verification/official-data")
				.bodyValue(Map.of("platform", "douyin", "contentUrl", "https://www.douyin.com/x", "platformHandle",
						"@seed", "commentText", "好评"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.configured").isEqualTo(true)
				.jsonPath("$.data.accountMatch").isEqualTo(true).jsonPath("$.data.published").isEqualTo(true)
				.jsonPath("$.data.commentFound").isEqualTo(true).jsonPath("$.data.metrics.likes").isEqualTo(120);
	}

	/** 网关故障（fetch empty）→ unavailable（调用方转 inconclusive，不伪装成结论）。 */
	@Test
	void gatewayFailureReportsUnavailable() {
		when(callers.requireServicePrincipal(any(), any())).thenReturn(Mono.just(marketplaceCaller()));
		OfficialVerificationGateway gateway = mock(OfficialVerificationGateway.class);
		when(gateway.fetch(any(), any(), any(), any())).thenReturn(Mono.empty());

		client(gateway).post().uri("/internal/verification/official-data")
				.bodyValue(Map.of("platform", "douyin", "contentUrl", "https://www.douyin.com/x")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.configured").isEqualTo(true)
				.jsonPath("$.data.unavailable").isEqualTo(true);
	}

	@Test
	void blankPlatformOrUrlIsBadRequest() {
		when(callers.requireServicePrincipal(any(), any())).thenReturn(Mono.just(marketplaceCaller()));
		client(null).post().uri("/internal/verification/official-data")
				.bodyValue(Map.of("platform", "", "contentUrl", "https://www.douyin.com/x")).exchange().expectStatus()
				.isBadRequest();
	}

	private WebTestClient client(OfficialVerificationGateway gateway) {
		ObjectProvider<OfficialVerificationGateway> provider = new ObjectProvider<>() {
			@Override
			public OfficialVerificationGateway getObject() {
				if (gateway == null) {
					throw new IllegalStateException("none");
				}
				return gateway;
			}

			@Override
			public OfficialVerificationGateway getIfAvailable() {
				return gateway;
			}
		};
		return WebTestClient.bindToController(new OfficialVerificationController(callers, provider)).build();
	}

	private static IntelligenceCallerResolver.Caller marketplaceCaller() {
		return new IntelligenceCallerResolver.Caller("service:marketplace", null, null, null, null, "service",
				"marketplace", null);
	}

}
